package com.example.trojan_rideshare;

import android.util.Log;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ride creation and management (spec Feature 2). Method names match the class diagram;
 * inputs and Consumer callbacks are added because Firestore calls finish asynchronously.
 */
@SuppressWarnings({"unused", "RedundantSuppression"})
public class RideManager {
    private static final String TAG = "RideManager";
    private static final String RIDES = "rides";
    private static final String USERS = "users";
    private static final int MIN_RIDERS = 2;
    private static final int MAX_RIDERS = 6;
    private static final long LEAVE_CUTOFF_MILLIS = 3 * 60 * 60 * 1000L; // spec: leave up to 3 hours before
    private static final String FINISHED_RIDE_ERROR = "Completed and cancelled rides can't be changed";

    private final FirebaseFirestore db;
    private final AuthManager authManager;

    public RideManager() {
        db = FirebaseFirestore.getInstance();
        authManager = AuthManager.getInstance();
    }

    public void createRide(String title, Location origin, Location destination, Date departureTime,
                           int maxRiders, double totalExpectedCost, String note, Consumer<Boolean> callback) {
        User user = authManager.getCurrentUser();
        if (user == null) {
            fail(callback, "No user is logged in");
            return;
        }
        String error = validateDetails(title, departureTime, maxRiders, totalExpectedCost);
        if (error == null && !departureTime.after(new Date())) {
            error = "Departure must be in the future";
        }
        if (error != null) {
            fail(callback, error);
            return;
        }

        List<String> riders = new ArrayList<>();
        riders.add(user.getId());
        Ride ride = new Ride(UUID.randomUUID().toString(), user, title, origin, destination, departureTime,
                maxRiders, totalExpectedCost, note, riders, Status.ON_TIME.name());

        db.collection(RIDES).document(ride.getRideID())
                .set(toMap(ride))
                .addOnSuccessListener(aVoid -> callback.accept(true))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error saving ride to Firestore", e);
                    callback.accept(false);
                });
    }

    /**
     * Only the organizer may edit, and only the departure time and note: the ride date and all
     * other posted details stay fixed.
     * Completed and Cancelled rides can't be edited. Passes the updated ride, or null on failure.
     */
    public void editRide(Ride ride, Date departureTime, String note, Consumer<Ride> callback) {
        User user = authManager.getCurrentUser();
        if (user == null) {
            Log.e(TAG, "editRide failed: no user is logged in");
            callback.accept(null);
            return;
        }
        DocumentReference ref = db.collection(RIDES).document(ride.getRideID());
        db.runTransaction(transaction -> {
            Ride current = toRide(transaction.get(ref));
            requireOrganizer(current, user);
            if (isFinished(current)) {
                throw abort(FINISHED_RIDE_ERROR);
            }
            if (!isSameDate(current.getDepartureTime(), departureTime)) {
                throw abort("The ride date can't be changed");
            }
            Ride updated = new Ride(current.getRideID(), current.getOrganizer(), current.getTitle(),
                    current.getOrigin(), current.getDestination(), departureTime,
                    current.getMaxRiders(), current.getTotalExpectedCost(), note, current.getRiders(),
                    current.getRideStatus());
            transaction.set(ref, toMap(updated));
            return updated;
        }).addOnSuccessListener(callback::accept)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "editRide failed", e);
                    callback.accept(null);
                });
    }

    /** Only the organizer may cancel. */
    public void cancelRide(User user, Ride ride, Consumer<Boolean> callback) {
        if (user == null) {
            fail(callback, "No user is logged in");
            return;
        }
        DocumentReference ref = db.collection(RIDES).document(ride.getRideID());
        db.runTransaction(transaction -> {
            Ride current = toRide(transaction.get(ref));
            requireOrganizer(current, user);
            requireAllowedChange(current, Status.CANCELLED);
            transaction.set(ref, toMap(rebuild(current, current.getOrganizer(), current.getRiders(), Status.CANCELLED)));
            return null;
        }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "cancelRide failed", e);
                    callback.accept(false);
                });
    }

    /**
     * Only the organizer may change the activity status. Completed is only allowed once the
     * departure time has passed, and Completed and Cancelled can't be undone.
     */
    public void updateRideStatus(Status status, Ride ride, Consumer<Boolean> callback) {
        User user = authManager.getCurrentUser();
        if (status == Status.CANCELLED) {
            cancelRide(user, ride, callback);
            return;
        }
        if (user == null) {
            fail(callback, "No user is logged in");
            return;
        }
        DocumentReference ref = db.collection(RIDES).document(ride.getRideID());
        db.runTransaction(transaction -> {
            Ride current = toRide(transaction.get(ref));
            requireOrganizer(current, user);
            requireAllowedChange(current, status);
            transaction.set(ref, toMap(rebuild(current, current.getOrganizer(), current.getRiders(), status)));
            return null;
        }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "updateRideStatus failed", e);
                    callback.accept(false);
                });
    }

    /**
     * The current user leaves the ride (spec Feature 3). Not allowed on Completed rides or
     * within 3 hours of departure. If the organizer leaves: the ride is cancelled when they are
     * the only rider, otherwise the most recently joined rider becomes the organizer.
     */
    public void leaveRide(Ride ride, Consumer<Boolean> callback) {
        User user = authManager.getCurrentUser();
        if (user == null) {
            fail(callback, "No user is logged in");
            return;
        }
        String uid = user.getId();
        DocumentReference ref = db.collection(RIDES).document(ride.getRideID());
        db.runTransaction(transaction -> {
            Ride current = toRide(transaction.get(ref));
            if (!current.getRiders().contains(uid)) {
                throw abort("User is not a rider on this ride");
            }
            if (Status.COMPLETED.name().equals(current.getRideStatus())) {
                throw abort("Completed rides can't be left");
            }
            if (System.currentTimeMillis() > current.getDepartureTime().getTime() - LEAVE_CUTOFF_MILLIS) {
                throw abort("Rides can only be left up to 3 hours before departure");
            }

            Status status = Status.valueOf(current.getRideStatus());
            boolean isOrganizer = uid.equals(current.getOrganizer().getId());
            if (isOrganizer && current.getNumOfRiders() == 1) {
                transaction.set(ref, toMap(rebuild(current, current.getOrganizer(), current.getRiders(), Status.CANCELLED)));
                return null;
            }

            List<String> riders = new ArrayList<>(current.getRiders());
            riders.remove(uid);
            User organizer = current.getOrganizer();
            if (isOrganizer) {
                String newOrganizerId = riders.get(riders.size() - 1);
                organizer = toUser(transaction.get(db.collection(USERS).document(newOrganizerId)), newOrganizerId);
            }
            transaction.set(ref, toMap(rebuild(current, organizer, riders, status)));
            return null;
        }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "leaveRide failed", e);
                    callback.accept(false);
                });
    }

    /** Returns an error message, or null if the ride details are valid. */
    private static String validateDetails(String title, Date departureTime, int maxRiders, double totalExpectedCost) {
        if (title == null || title.trim().isEmpty()) {
            return "Title is required";
        }
        if (departureTime == null) {
            return "Departure time is required";
        }
        if (maxRiders < MIN_RIDERS || maxRiders > MAX_RIDERS) {
            return "Riders must be between " + MIN_RIDERS + " and " + MAX_RIDERS;
        }
        if (totalExpectedCost < 0) {
            return "Expected cost can't be negative";
        }
        return null;
    }

    /** Reads a dollar amount with up to 2 decimals. Returns -1 when the text isn't one. */
    static double parseCost(String text) {
        if (text == null || !text.matches("\\d+(\\.\\d{0,2})?|\\.\\d{1,2}")) {
            return -1;
        }
        return Double.parseDouble(text);
    }

    private static boolean isSameDate(Date first, Date second) {
        if (first == null || second == null) {
            return false;
        }
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        a.setTime(first);
        b.setTime(second);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** Completed and Cancelled are final. */
    static boolean isFinished(Ride ride) {
        return Status.COMPLETED.name().equals(ride.getRideStatus())
                || Status.CANCELLED.name().equals(ride.getRideStatus());
    }

    /** Returns an error message, or null if the ride may move to the given status at this time. */
    static String checkStatusChange(Ride ride, Status status, Date now) {
        if (isFinished(ride)) {
            return FINISHED_RIDE_ERROR;
        }
        if (status == Status.COMPLETED && now.before(ride.getDepartureTime())) {
            return "Rides can only be completed after departure";
        }
        return null;
    }

    private static void requireAllowedChange(Ride ride, Status status) throws FirebaseFirestoreException {
        String error = checkStatusChange(ride, status, new Date());
        if (error != null) {
            throw abort(error);
        }
    }

    private static void requireOrganizer(Ride ride, User user) throws FirebaseFirestoreException {
        if (!user.getId().equals(ride.getOrganizer().getId())) {
            throw abort("Only the organizer can manage this ride");
        }
    }

    private static Ride rebuild(Ride ride, User organizer, List<String> riders, Status status) {
        return new Ride(ride.getRideID(), organizer, ride.getTitle(), ride.getOrigin(), ride.getDestination(),
                ride.getDepartureTime(), ride.getMaxRiders(), ride.getTotalExpectedCost(), ride.getNote(),
                riders, status.name());
    }

    private static FirebaseFirestoreException abort(String message) {
        return new FirebaseFirestoreException(message, FirebaseFirestoreException.Code.ABORTED);
    }

    private static void fail(Consumer<Boolean> callback, String reason) {
        Log.e(TAG, reason);
        callback.accept(false);
    }

    // ---- Firestore mapping (not in the class diagram; search and history can reuse these) ----

    // Only part of the organizer is stored: User can't be read back automatically and holds the password hash
    static Map<String, Object> toMap(Ride ride) {
        User organizer = ride.getOrganizer();
        Map<String, Object> organizerMap = new HashMap<>();
        organizerMap.put("id", organizer.getId());
        organizerMap.put("email", organizer.getEmail());
        organizerMap.put("fullName", organizer.getFullName());
        organizerMap.put("nickname", organizer.getNickname());

        Map<String, Object> rideMap = new HashMap<>();
        rideMap.put("rideID", ride.getRideID());
        rideMap.put("title", ride.getTitle());
        rideMap.put("organizer", organizerMap);
        rideMap.put("departureTime", ride.getDepartureTime());
        rideMap.put("rideStatus", ride.getRideStatus());
        rideMap.put("capacityStatus", ride.getCapacityStatus());
        rideMap.put("numOfRiders", ride.getNumOfRiders());
        rideMap.put("maxRiders", ride.getMaxRiders());
        rideMap.put("riders", new ArrayList<>(ride.getRiders()));
        rideMap.put("totalExpectedCost", ride.getTotalExpectedCost());
        rideMap.put("note", ride.getNote());
        rideMap.put("origin", toMap(ride.getOrigin()));
        rideMap.put("destination", toMap(ride.getDestination()));
        return rideMap;
    }

    @SuppressWarnings("unchecked")
    static Ride toRide(DocumentSnapshot snapshot) throws FirebaseFirestoreException {
        if (!snapshot.exists()) {
            throw abort("Ride not found");
        }
        List<String> riders = new ArrayList<>();
        Object ridersRaw = snapshot.get("riders");
        if (ridersRaw instanceof List) {
            for (Object rider : (List<?>) ridersRaw) {
                if (rider != null) {
                    riders.add(String.valueOf(rider));
                }
            }
        }
        String rideId = snapshot.getString("rideID");
        if (rideId == null || rideId.isEmpty()) {
            rideId = snapshot.getId();
        }
        String title = snapshot.getString("title");
        String status = snapshot.getString("rideStatus");
        if (status == null || status.isEmpty()) {
            status = Status.ON_TIME.name();
        }
        try {
            Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw abort("Ride " + rideId + " has an unknown status");
        }
        return new Ride(
                rideId,
                toOrganizer(snapshot.get("organizer"), riders),
                title != null ? title : "",
                toLocation(snapshot.get("origin")),
                toLocation(snapshot.get("destination")),
                snapshot.getDate("departureTime"),
                getInt(snapshot, "maxRiders"),
                getDouble(snapshot, "totalExpectedCost"),
                snapshot.getString("note"),
                riders,
                status);
    }

    @SuppressWarnings("unchecked")
    private static User toOrganizer(Object raw, List<String> riders) {
        if (raw instanceof Map) {
            Map<String, Object> organizerMap = (Map<String, Object>) raw;
            return new User(
                    text(organizerMap.get("id")),
                    text(organizerMap.get("email")),
                    "",
                    text(organizerMap.get("fullName")),
                    text(organizerMap.get("nickname")),
                    0,
                    0);
        }
        String id = riders.isEmpty() ? "" : riders.get(0);
        return new User(id, "", "", "Unknown", "", 0, 0);
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static Map<String, Object> toMap(Location location) {
        Map<String, Object> locationMap = new HashMap<>();
        locationMap.put("POIName", location.getPOIName());
        locationMap.put("lat", location.getLat());
        locationMap.put("lon", location.getLon());
        return locationMap;
    }

    @SuppressWarnings("unchecked")
    private static Location toLocation(Object raw) throws FirebaseFirestoreException {
        if (!(raw instanceof Map)) {
            throw abort("Ride is missing a location");
        }
        Map<String, Object> locationMap = (Map<String, Object>) raw;
        if (!(locationMap.get("lat") instanceof Number) || !(locationMap.get("lon") instanceof Number)) {
            throw abort("Ride location is missing coordinates");
        }
        return new Location(
                text(locationMap.get("POIName")),
                ((Number) locationMap.get("lat")).doubleValue(),
                ((Number) locationMap.get("lon")).doubleValue());
    }

    // Reads a profile from the "users" collection written by AuthManager.registerUser
    private static User toUser(DocumentSnapshot snapshot, String uid) throws FirebaseFirestoreException {
        if (!snapshot.exists()) {
            throw abort("User " + uid + " not found");
        }
        return new User(
                uid,
                snapshot.getString("email"),
                "",
                snapshot.getString("fullName"),
                snapshot.getString("nickname"),
                getInt(snapshot, "graduationYear"),
                getInt(snapshot, "uscId"));
    }

    private static double getDouble(DocumentSnapshot snapshot, String field) {
        Double value = snapshot.getDouble(field);
        return value != null ? value : 0;
    }

    private static int getInt(DocumentSnapshot snapshot, String field) {
        Long value = snapshot.getLong(field);
        return value != null ? value.intValue() : 0;
    }
}

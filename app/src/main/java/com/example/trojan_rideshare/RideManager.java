package com.example.trojan_rideshare;

import android.util.Log;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.ArrayList;
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

    private final FirebaseFirestore db;
    private final AuthManager authManager;

    public RideManager() {
        db = FirebaseFirestore.getInstance();
        authManager = AuthManager.getInstance();
    }

    public void createRide(String title, Location origin, Location destination, Date departureTime,
                           int maxRiders, int totalExpectedCost, String note, Consumer<Boolean> callback) {
        User user = authManager.getCurrentUser();
        if (user == null) {
            fail(callback, "No user is logged in");
            return;
        }
        String error = validateDetails(title, departureTime, maxRiders, totalExpectedCost, 1);
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

    /** Only the organizer may edit. Passes the updated ride, or null on failure. */
    public void editRide(Ride ride, String title, Location origin, Location destination, Date departureTime,
                         int maxRiders, int totalExpectedCost, String note, Consumer<Ride> callback) {
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
            String error = validateDetails(title, departureTime, maxRiders, totalExpectedCost, current.getNumOfRiders());
            if (error != null) {
                throw abort(error);
            }
            Ride updated = new Ride(current.getRideID(), current.getOrganizer(), title, origin, destination,
                    departureTime, maxRiders, totalExpectedCost, note, current.getRiders(), current.getRideStatus());
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
            transaction.set(ref, toMap(rebuild(current, current.getOrganizer(), current.getRiders(), Status.CANCELLED)));
            return null;
        }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "cancelRide failed", e);
                    callback.accept(false);
                });
    }

    /** Only the organizer may change the activity status. Completed sets capacity to N/A. */
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
    private static String validateDetails(String title, Date departureTime, int maxRiders,
                                          int totalExpectedCost, int numOfRiders) {
        if (title == null || title.trim().isEmpty()) {
            return "Title is required";
        }
        if (departureTime == null) {
            return "Departure time is required";
        }
        if (maxRiders < MIN_RIDERS || maxRiders > MAX_RIDERS) {
            return "Riders must be between " + MIN_RIDERS + " and " + MAX_RIDERS;
        }
        if (maxRiders < numOfRiders) {
            return "Ride already has " + numOfRiders + " riders";
        }
        if (totalExpectedCost < 0) {
            return "Expected cost can't be negative";
        }
        return null;
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
        Map<String, Object> organizerMap = (Map<String, Object>) snapshot.get("organizer");
        User organizer = new User(
                (String) organizerMap.get("id"),
                (String) organizerMap.get("email"),
                "",
                (String) organizerMap.get("fullName"),
                (String) organizerMap.get("nickname"),
                0,
                0);
        List<String> riders = (List<String>) snapshot.get("riders");
        return new Ride(
                snapshot.getString("rideID"),
                organizer,
                snapshot.getString("title"),
                toLocation((Map<String, Object>) snapshot.get("origin")),
                toLocation((Map<String, Object>) snapshot.get("destination")),
                snapshot.getDate("departureTime"),
                getInt(snapshot, "maxRiders"),
                getInt(snapshot, "totalExpectedCost"),
                snapshot.getString("note"),
                riders != null ? riders : new ArrayList<>(),
                snapshot.getString("rideStatus"));
    }

    private static Map<String, Object> toMap(Location location) {
        Map<String, Object> locationMap = new HashMap<>();
        locationMap.put("POIName", location.getPOIName());
        locationMap.put("lat", location.getLat());
        locationMap.put("lon", location.getLon());
        return locationMap;
    }

    private static Location toLocation(Map<String, Object> locationMap) {
        return new Location(
                (String) locationMap.get("POIName"),
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

    private static int getInt(DocumentSnapshot snapshot, String field) {
        Long value = snapshot.getLong(field);
        return value != null ? value.intValue() : 0;
    }
}

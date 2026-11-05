package com.example.trojan_rideshare;

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Profile reads stay off the user document itself.
 * Public rides are stored at users/{userId}/publicRides/{rideId}, so teammates can add
 * or rename profile fields without this feature overwriting them.
 */
public class ProfileManager {
    private static final String TAG = "ProfileManager";
    private static final String USERS = "users";
    private static final String RIDES = "rides";
    private static final String RIDER_FLAGS = "riderFlags";
    static final String PUBLIC_RIDES = "publicRides";

    private final FirebaseFirestore db;

    public ProfileManager() {
        db = FirebaseFirestore.getInstance();
    }

    public void loadUser(String userId, Consumer<User> callback) {
        if (userId == null || userId.isEmpty()) {
            callback.accept(null);
            return;
        }
        db.collection(USERS).document(userId).get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) {
                        callback.accept(null);
                        return;
                    }
                    callback.accept(toUser(snapshot));
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "loadUser failed", e);
                    callback.accept(null);
                });
    }

    /** How many of this person's unpaid expenses are flagged. Passes null when the read fails. */
    public void loadFlagCount(String userId, Consumer<Integer> callback) {
        db.collection(RIDER_FLAGS)
                .whereEqualTo("flaggedUser", db.collection(USERS).document(userId))
                .get()
                .addOnSuccessListener(flags -> callback.accept(flags.size()))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "loadFlagCount failed", e);
                    callback.accept(null);
                });
    }

    /** Rides this person organized or joined. Missing fields on a document are skipped. */
    public void loadParticipatedRides(String userId, Consumer<List<Ride>> callback) {
        Map<String, Ride> rides = new LinkedHashMap<>();
        int[] remaining = {2};
        boolean[] failed = {false, false};

        Consumer<Integer> done = index -> {
            remaining[0]--;
            if (remaining[0] > 0) {
                return;
            }
            if (failed[0] && failed[1]) {
                callback.accept(null);
                return;
            }
            List<Ride> list = new ArrayList<>(rides.values());
            list.sort(Comparator.comparing(Ride::getDepartureTime,
                    Comparator.nullsLast(Collections.reverseOrder())));
            callback.accept(list);
        };

        db.collection(RIDES).whereArrayContains("riders", userId).get()
                .addOnSuccessListener(snapshot -> {
                    for (DocumentSnapshot document : snapshot.getDocuments()) {
                        addRide(rides, document);
                    }
                    done.accept(0);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "rider query failed", e);
                    failed[0] = true;
                    done.accept(0);
                });

        db.collection(RIDES).whereEqualTo("organizer.id", userId).get()
                .addOnSuccessListener(snapshot -> {
                    for (DocumentSnapshot document : snapshot.getDocuments()) {
                        addRide(rides, document);
                    }
                    done.accept(1);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "organizer query failed", e);
                    failed[1] = true;
                    done.accept(1);
                });
    }

    public void loadPublicRideIds(String userId, Consumer<List<String>> callback) {
        db.collection(USERS).document(userId).collection(PUBLIC_RIDES).get()
                .addOnSuccessListener(snapshot -> {
                    List<String> ids = new ArrayList<>();
                    for (DocumentSnapshot document : snapshot.getDocuments()) {
                        ids.add(document.getId());
                    }
                    callback.accept(ids);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "loadPublicRideIds failed", e);
                    callback.accept(null);
                });
    }

    /**
     * People on this ride who marked it public. Each name on the ride page opens that profile.
     * A failed read for one person is skipped so the others still show.
     */
    public void loadUsersWhoShared(Ride ride, Consumer<List<User>> callback) {
        List<String> ids = participantIds(ride);
        if (ride == null || ride.getRideID() == null || ride.getRideID().isEmpty() || ids.isEmpty()) {
            callback.accept(new ArrayList<>());
            return;
        }
        List<Task<DocumentSnapshot>> reads = new ArrayList<>();
        for (String id : ids) {
            reads.add(db.collection(USERS).document(id).collection(PUBLIC_RIDES).document(ride.getRideID()).get());
        }
        Tasks.whenAllComplete(reads).addOnSuccessListener(tasks -> {
            List<String> sharedIds = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                Task<?> task = tasks.get(i);
                if (!task.isSuccessful() || !(task.getResult() instanceof DocumentSnapshot)) {
                    continue;
                }
                if (((DocumentSnapshot) task.getResult()).exists()) {
                    sharedIds.add(ids.get(i));
                }
            }
            if (sharedIds.isEmpty()) {
                callback.accept(new ArrayList<>());
                return;
            }
            List<Task<DocumentSnapshot>> userReads = new ArrayList<>();
            for (String id : sharedIds) {
                userReads.add(db.collection(USERS).document(id).get());
            }
            Tasks.whenAllComplete(userReads).addOnSuccessListener(userTasks -> {
                List<User> people = new ArrayList<>();
                for (Task<?> task : userTasks) {
                    if (!task.isSuccessful() || !(task.getResult() instanceof DocumentSnapshot)) {
                        continue;
                    }
                    DocumentSnapshot snapshot = (DocumentSnapshot) task.getResult();
                    if (snapshot.exists()) {
                        people.add(toUser(snapshot));
                    }
                }
                callback.accept(people);
            });
        });
    }

    /** Organizer plus riders, with duplicates and blank ids removed. */
    static List<String> participantIds(Ride ride) {
        List<String> ids = new ArrayList<>();
        if (ride == null) {
            return ids;
        }
        if (ride.getOrganizer() != null) {
            addId(ids, ride.getOrganizer().getId());
        }
        for (String riderId : ride.getRiders()) {
            addId(ids, riderId);
        }
        return ids;
    }

    private static void addId(List<String> ids, String id) {
        if (id != null && !id.isEmpty() && !ids.contains(id)) {
            ids.add(id);
        }
    }

    /** Rides this person marked public, and only if they were actually on the ride. */
    public void loadPublicRides(String userId, Consumer<List<Ride>> callback) {
        loadPublicRideIds(userId, ids -> {
            if (ids == null) {
                callback.accept(null);
                return;
            }
            if (ids.isEmpty()) {
                callback.accept(new ArrayList<>());
                return;
            }
            List<Task<DocumentSnapshot>> reads = new ArrayList<>();
            for (String rideId : ids) {
                reads.add(db.collection(RIDES).document(rideId).get());
            }
            Tasks.whenAllSuccess(reads).addOnSuccessListener(results -> {
                Map<String, Ride> rides = new LinkedHashMap<>();
                for (Object result : results) {
                    addRide(rides, (DocumentSnapshot) result);
                }
                List<Ride> visible = new ArrayList<>();
                for (Ride ride : rides.values()) {
                    if (includesUser(ride, userId)) {
                        visible.add(ride);
                    }
                }
                visible.sort(Comparator.comparing(Ride::getDepartureTime,
                        Comparator.nullsLast(Collections.reverseOrder())));
                callback.accept(visible);
            }).addOnFailureListener(e -> {
                Log.e(TAG, "loadPublicRides failed", e);
                callback.accept(null);
            });
        });
    }

    public void setRidePublic(String userId, String rideId, boolean makePublic, Consumer<Boolean> callback) {
        User current = AuthManager.getInstance().getCurrentUser();
        if (current == null || userId == null || !userId.equals(current.getId()) || rideId == null || rideId.isEmpty()) {
            callback.accept(false);
            return;
        }
        DocumentReference ref = db.collection(USERS).document(userId).collection(PUBLIC_RIDES).document(rideId);
        if (!makePublic) {
            ref.delete()
                    .addOnSuccessListener(unused -> callback.accept(true))
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "setRidePublic delete failed", e);
                        callback.accept(false);
                    });
            return;
        }
        Map<String, Object> share = new HashMap<>();
        share.put("rideId", rideId);
        share.put("public", true);
        ref.set(share)
                .addOnSuccessListener(unused -> callback.accept(true))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "setRidePublic failed", e);
                    callback.accept(false);
                });
    }

    /** Upcoming rides stay ON_TIME here. Delayed rides are still upcoming. */
    static Status sectionFor(Ride ride) {
        if (ride == null || ride.getRideStatus() == null) {
            return Status.ON_TIME;
        }
        try {
            Status status = Status.valueOf(ride.getRideStatus());
            if (status == Status.COMPLETED || status == Status.CANCELLED) {
                return status;
            }
        } catch (IllegalArgumentException ignored) {
            return Status.ON_TIME;
        }
        return Status.ON_TIME;
    }

    static boolean includesUser(Ride ride, String userId) {
        if (ride == null || userId == null || userId.isEmpty()) {
            return false;
        }
        if (ride.getOrganizer() != null && userId.equals(ride.getOrganizer().getId())) {
            return true;
        }
        return ride.getRiders().contains(userId);
    }

    private static void addRide(Map<String, Ride> rides, DocumentSnapshot document) {
        if (document == null || !document.exists()) {
            return;
        }
        try {
            Ride ride = RideManager.toRide(document);
            if (ride.getRideID() != null && !ride.getRideID().isEmpty()) {
                rides.put(ride.getRideID(), ride);
            }
        } catch (RuntimeException | FirebaseFirestoreException e) {
            Log.e(TAG, "Skipping unreadable ride " + document.getId(), e);
        }
    }

    /** Reads the fields that exist today and ignores any new ones teammates add. */
    static User toUser(DocumentSnapshot snapshot) {
        Long graduationYear = snapshot.getLong("graduationYear");
        Long uscId = snapshot.getLong("uscId");
        return new User(
                snapshot.getId(),
                text(snapshot.getString("email")),
                "",
                text(snapshot.getString("fullName")),
                text(snapshot.getString("nickname")),
                graduationYear != null ? graduationYear.intValue() : 0,
                uscId != null ? uscId.intValue() : 0);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}

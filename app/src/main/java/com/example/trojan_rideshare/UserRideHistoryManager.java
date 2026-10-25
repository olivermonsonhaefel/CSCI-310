package com.example.trojan_rideshare;

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class UserRideHistoryManager {
    private static final String TAG = "UserRideHistoryManager";
    private static final String RIDES = "rides";
    private static final String USERS = "users";

    private final FirebaseFirestore db;
    private final AuthManager authManager;

    public UserRideHistoryManager() {
        db = FirebaseFirestore.getInstance();
        authManager = AuthManager.getInstance();
    }

    public void getHistory(Consumer<List<Ride>> callback) {
        User user = authManager.getCurrentUser();
        if (user == null) {
            Log.e(TAG, "getHistory failed: no user is logged in");
            callback.accept(null);
            return;
        }
        db.collection(RIDES)
                .whereArrayContains("riders", user.getId())
                .get()
                .addOnSuccessListener(snapshot -> {
                    List<Ride> rides = new ArrayList<>();
                    for (DocumentSnapshot document : snapshot.getDocuments()) {
                        try {
                            rides.add(RideManager.toRide(document));
                        } catch (FirebaseFirestoreException e) {
                            Log.e(TAG, "Skipping unreadable ride " + document.getId(), e);
                        }
                    }
                    rides.sort(Comparator.comparing(Ride::getDepartureTime,
                            Comparator.nullsLast(Comparator.<Date>naturalOrder())).reversed());
                    callback.accept(rides);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getHistory failed", e);
                    callback.accept(null);
                });
    }

    public void getParticipants(Consumer<List<User>> callback) {
        User user = authManager.getCurrentUser();
        getHistory(rides -> {
            if (rides == null || user == null) {
                callback.accept(null);
                return;
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Ride ride : rides) {
                ids.addAll(ride.getRiders());
            }
            ids.remove(user.getId());

            List<Task<DocumentSnapshot>> reads = new ArrayList<>();
            for (String id : ids) {
                reads.add(db.collection(USERS).document(id).get());
            }
            Tasks.whenAllSuccess(reads)
                    .addOnSuccessListener(results -> {
                        List<User> participants = new ArrayList<>();
                        for (Object result : results) {
                            DocumentSnapshot snapshot = (DocumentSnapshot) result;
                            if (snapshot.exists()) {
                                participants.add(toUser(snapshot));
                            }
                        }
                        callback.accept(participants);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "getParticipants failed", e);
                        callback.accept(null);
                    });
        });
    }

    private static User toUser(DocumentSnapshot snapshot) {
        Long graduationYear = snapshot.getLong("graduationYear");
        Long uscId = snapshot.getLong("uscId");
        return new User(
                snapshot.getId(),
                snapshot.getString("email"),
                "",
                snapshot.getString("fullName"),
                snapshot.getString("nickname"),
                graduationYear != null ? graduationYear.intValue() : 0,
                uscId != null ? uscId.intValue() : 0);
    }
}

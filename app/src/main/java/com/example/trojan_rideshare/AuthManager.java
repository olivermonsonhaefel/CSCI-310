package com.example.trojan_rideshare;

import android.util.Log;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

@SuppressWarnings({"unused", "RedundantSuppression"})
public class AuthManager {
    private static final String TAG = "AuthManager";
    private static AuthManager instance;
    private final FirebaseAuth mAuth;
    private final FirebaseFirestore db;
    private User currentUser;

    private AuthManager() {
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
    }

    public static synchronized AuthManager getInstance() {
        if (instance == null) {
            instance = new AuthManager();
        }
        return instance;
    }

    public void registerUser(String fname, String lname, String username, String password, Consumer<Boolean> callback) {
        String fullName = fname + " " + lname;
        int defaultGradYear = 2026;
        int defaultUscId = 1234567890;

        mAuth.createUserWithEmailAndPassword(username, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && mAuth.getCurrentUser() != null) {
                        FirebaseUser firebaseUser = mAuth.getCurrentUser();
                        String uid = firebaseUser.getUid();

                        User newUser = new User(uid, username, password, fullName, fname, defaultGradYear, defaultUscId);

                        Map<String, Object> userMap = new HashMap<>();
                        userMap.put("id", uid);
                        userMap.put("email", username);
                        userMap.put("fullName", fullName);
                        userMap.put("nickname", fname);
                        userMap.put("graduationYear", defaultGradYear);
                        userMap.put("uscId", defaultUscId);

                        db.collection("users").document(uid)
                                .set(userMap)
                                .addOnSuccessListener(aVoid -> {
                                    currentUser = newUser;
                                    callback.accept(true);
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "Error saving user to Firestore", e);
                                    callback.accept(false);
                                });
                    } else {
                        Log.e(TAG, "Firebase Auth registration failed", task.getException());
                        callback.accept(false);
                    }
                });
    }

    public void authenticateUserByPassword(String username, String password, Consumer<Boolean> callback) {
        mAuth.signInWithEmailAndPassword(username, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && mAuth.getCurrentUser() != null) {
                        FirebaseUser firebaseUser = mAuth.getCurrentUser();
                        String uid = firebaseUser.getUid();

                        db.collection("users").document(uid).get()
                                .addOnSuccessListener(documentSnapshot -> {
                                    if (documentSnapshot.exists()) {
                                        currentUser = new User(
                                                uid,
                                                documentSnapshot.getString("email"),
                                                password,
                                                documentSnapshot.getString("fullName"),
                                                documentSnapshot.getString("nickname"),
                                                Objects.requireNonNull(documentSnapshot.getLong("graduationYear")).intValue(),
                                                Objects.requireNonNull(documentSnapshot.getLong("uscId")).intValue()
                                        );
                                    } else {
                                        currentUser = new User(uid, username, password, username, username, 2026, 123456789);
                                    }
                                    callback.accept(true);
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "Error fetching user profile from Firestore", e);
                                    callback.accept(false);
                                });
                    } else {
                        Log.e(TAG, "Firebase Auth sign-in failed", task.getException());
                        callback.accept(false);
                    }
                });
    }

    public boolean authenticateUser(String username, int userId) {
        FirebaseUser firebaseUser = mAuth.getCurrentUser();
        return firebaseUser != null && firebaseUser.getEmail() != null && firebaseUser.getEmail().equals(username);
    }

    public void deleteAccount(String username, Consumer<Boolean> callback) {
        FirebaseUser firebaseUser = mAuth.getCurrentUser();
        if (firebaseUser != null) {
            String uid = firebaseUser.getUid();
            db.collection("users").document(uid).delete()
                    .addOnSuccessListener(aVoid -> firebaseUser.delete()
                            .addOnSuccessListener(aVoid2 -> {
                                currentUser = null;
                                callback.accept(true);
                            })
                            .addOnFailureListener(e -> callback.accept(false)))
                    .addOnFailureListener(e -> callback.accept(false));
        } else {
            callback.accept(false);
        }
    }

    public void logoutUser(String fname, String lname, String username) {
        currentUser = null;
        mAuth.signOut();
    }

    public User getCurrentUser() {
        if (currentUser == null && mAuth.getCurrentUser() != null) {
            FirebaseUser fu = mAuth.getCurrentUser();
            currentUser = new User(fu.getUid(), fu.getEmail(), "", fu.getEmail(), "", 2026, 123456789);
        }
        return currentUser;
    }
}

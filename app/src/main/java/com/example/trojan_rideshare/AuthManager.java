package com.example.trojan_rideshare;

import android.util.Log;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;
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

    public void registerUser(String fullName, String email, long uscId, int graduationYear, String password, String nickname, Consumer<Boolean> callback) {
        String finalNickname = (nickname == null || nickname.trim().isEmpty()) ? fullName : nickname.trim();

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && mAuth.getCurrentUser() != null) {
                        FirebaseUser firebaseUser = mAuth.getCurrentUser();
                        String uid = firebaseUser.getUid();

                        User newUser = new User(uid, email, password, fullName, finalNickname, graduationYear, uscId);

                        Map<String, Object> userMap = new HashMap<>();
                        userMap.put("id", uid);
                        userMap.put("email", email);
                        userMap.put("fullName", fullName);
                        userMap.put("nickname", finalNickname);
                        userMap.put("graduationYear", graduationYear);
                        userMap.put("uscId", uscId);

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
                                        Long uscIdVal = documentSnapshot.getLong("uscId");
                                        Long gradYearVal = documentSnapshot.getLong("graduationYear");
                                        currentUser = new User(
                                                uid,
                                                documentSnapshot.getString("email"),
                                                password,
                                                documentSnapshot.getString("fullName"),
                                                documentSnapshot.getString("nickname"),
                                                gradYearVal != null ? gradYearVal.intValue() : 2026,
                                                uscIdVal != null ? uscIdVal : 0L
                                        );
                                    } else {
                                        currentUser = new User(uid, username, password, username, username, 2026, 1234567890L);
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

    public boolean authenticateUser(String username, long userId) {
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
            currentUser = new User(fu.getUid(), fu.getEmail(), "", fu.getEmail(), "", 2026, 1234567890L);
        }
        return currentUser;
    }
}

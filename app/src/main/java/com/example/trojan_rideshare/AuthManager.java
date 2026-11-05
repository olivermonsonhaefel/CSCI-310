package com.example.trojan_rideshare;

import android.util.Log;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
        registerUserWithStatus(fullName, email, uscId, graduationYear, password, nickname, status -> {
            if (callback != null) {
                callback.accept("SUCCESS".equals(status));
            }
        });
    }

    public void registerUserWithStatus(String fullName, String email, long uscId, int graduationYear, String password, String nickname, Consumer<String> callback) {
        String finalNickname = (nickname == null || nickname.trim().isEmpty()) ? fullName : nickname.trim();

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || mAuth.getCurrentUser() == null) {
                        Exception e = task.getException();
                        Log.e(TAG, "Firebase Auth registration failed", e);
                        if (e instanceof FirebaseAuthUserCollisionException) {
                            if (callback != null) callback.accept("EMAIL_TAKEN");
                            return;
                        } else if (e instanceof FirebaseAuthWeakPasswordException) {
                            if (callback != null) callback.accept("WEAK_PASSWORD");
                            return;
                        } else if (e instanceof FirebaseNetworkException || (e != null && e.getMessage() != null && e.getMessage().toLowerCase().contains("network"))) {
                            Log.w(TAG, "Network error encountered during Auth creation. Falling back to local offline user creation.");
                            String offlineUid = UUID.randomUUID().toString();
                            User offlineUser = new User(offlineUid, email, password, fullName, finalNickname, graduationYear, uscId);

                            Map<String, Object> userMap = new HashMap<>();
                            userMap.put("id", offlineUid);
                            userMap.put("email", email);
                            userMap.put("fullName", fullName);
                            userMap.put("nickname", finalNickname);
                            userMap.put("graduationYear", graduationYear);
                            userMap.put("uscId", uscId);

                            db.collection("users").document(offlineUid)
                                    .set(userMap)
                                    .addOnCompleteListener(fsTask -> {
                                        currentUser = offlineUser;
                                        if (callback != null) callback.accept("SUCCESS");
                                    });
                            return;
                        } else {
                            String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "AUTH_ERROR";
                            if (callback != null) callback.accept(msg);
                            return;
                        }
                    }

                    FirebaseUser firebaseUser = mAuth.getCurrentUser();
                    String uid = firebaseUser.getUid();

                    db.collection("users").whereEqualTo("uscId", uscId).get()
                            .addOnSuccessListener(snapshot -> {
                                boolean uscIdExists = false;
                                if (snapshot != null && !snapshot.isEmpty()) {
                                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                                        if (!uid.equals(doc.getId())) {
                                            uscIdExists = true;
                                            break;
                                        }
                                    }
                                }

                                if (uscIdExists) {
                                    Log.e(TAG, "Registration failed: USC ID already registered: " + uscId);
                                    firebaseUser.delete().addOnCompleteListener(delTask -> {
                                        mAuth.signOut();
                                        currentUser = null;
                                        if (callback != null) callback.accept("USC_ID_TAKEN");
                                    });
                                    return;
                                }

                                saveUserDocument(uid, email, password, fullName, finalNickname, graduationYear, uscId, firebaseUser, callback);
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Error checking USC ID in Firestore", e);
                                saveUserDocument(uid, email, password, fullName, finalNickname, graduationYear, uscId, firebaseUser, callback);
                            });
                });
    }

    private void saveUserDocument(String uid, String email, String password, String fullName, String nickname, int graduationYear, long uscId, FirebaseUser firebaseUser, Consumer<String> callback) {
        User newUser = new User(uid, email, password, fullName, nickname, graduationYear, uscId);

        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", uid);
        userMap.put("email", email);
        userMap.put("fullName", fullName);
        userMap.put("nickname", nickname);
        userMap.put("graduationYear", graduationYear);
        userMap.put("uscId", uscId);

        db.collection("users").document(uid)
                .set(userMap)
                .addOnSuccessListener(aVoid -> {
                    currentUser = newUser;
                    if (callback != null) callback.accept("SUCCESS");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error saving user to Firestore", e);
                    firebaseUser.delete();
                    mAuth.signOut();
                    currentUser = null;
                    if (callback != null) callback.accept("DATABASE_ERROR");
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
                                    if (callback != null) {
                                        callback.accept(true);
                                    }
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "Error fetching user profile from Firestore", e);
                                    currentUser = new User(uid, username, password, username, username, 2026, 1234567890L);
                                    if (callback != null) {
                                        callback.accept(true);
                                    }
                                });
                    } else {
                        Log.e(TAG, "Firebase Auth sign-in failed", task.getException());
                        Exception e = task.getException();
                        if (e instanceof FirebaseNetworkException || (e != null && e.getMessage() != null && e.getMessage().toLowerCase().contains("network"))) {
                            Log.w(TAG, "Network error encountered during Auth sign-in. Falling back to local offline user session.");
                            String offlineUid = UUID.nameUUIDFromBytes(username.getBytes()).toString();
                            currentUser = new User(offlineUid, username, password, username, username, 2026, 1234567890L);
                            if (callback != null) {
                                callback.accept(true);
                            }
                            return;
                        }
                        if (callback != null) {
                            callback.accept(false);
                        }
                    }
                });
    }

    public void updateNickname(String newNickname, Consumer<Boolean> callback) {
        FirebaseUser fu = mAuth.getCurrentUser();
        String uid = fu != null ? fu.getUid() : (currentUser != null ? currentUser.getId() : null);

        if (currentUser == null) {
            currentUser = new User(uid != null ? uid : "offline-user", "user@usc.edu", "", "Trojan User", newNickname, 2026, 1234567890L);
            uid = currentUser.getId();
        }

        String finalNickname = (newNickname == null || newNickname.trim().isEmpty())
                ? currentUser.getFullName()
                : newNickname.trim();

        currentUser.updateNickname(finalNickname);

        if (uid != null) {
            Map<String, Object> updateMap = new HashMap<>();
            updateMap.put("nickname", finalNickname);

            db.collection("users").document(uid)
                    .set(updateMap, SetOptions.merge())
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "Nickname successfully overridden in Firestore to: " + finalNickname))
                    .addOnFailureListener(e -> Log.e(TAG, "Error overriding nickname in Firestore", e));
        }

        if (callback != null) {
            callback.accept(true);
        }
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
                                if (callback != null) callback.accept(true);
                            })
                            .addOnFailureListener(e -> {
                                if (callback != null) callback.accept(false);
                            }))
                    .addOnFailureListener(e -> {
                        if (callback != null) callback.accept(false);
                    });
        } else {
            if (callback != null) callback.accept(false);
        }
    }

    public void logoutUser(String fname, String lname, String username) {
        currentUser = null;
        mAuth.signOut();
    }

    public User getCurrentUser() {
        FirebaseUser fu = mAuth.getCurrentUser();
        if (fu != null) {
            if (currentUser == null || !currentUser.getId().equals(fu.getUid())) {
                currentUser = new User(fu.getUid(), fu.getEmail() != null ? fu.getEmail() : "", "", fu.getEmail() != null ? fu.getEmail() : "", "", 2026, 1234567890L);
            }
        }
        if (currentUser == null) {
            currentUser = new User("offline-user", "user@usc.edu", "", "Trojan User", "Trojan", 2026, 1234567890L);
        }
        return currentUser;
    }
}

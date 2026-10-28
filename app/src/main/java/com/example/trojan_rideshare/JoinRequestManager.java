package com.example.trojan_rideshare;

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sending and managing join requests (spec Feature 3, use case 2.3). Method names match the class diagram;
 * inputs and Consumer callbacks are added because Firestore calls finish asynchronously, like RideManager.
 * Requests are stored in the "joinRequests" collection.
 */
@SuppressWarnings({"unused", "RedundantSuppression"})
public class JoinRequestManager {
    private static final String TAG = "JoinRequestManager";
    private static final String RIDES = "rides";
    private static final String JOIN_REQUESTS = "joinRequests";
    static final int MAX_MESSAGE_LENGTH = 250;

    // Messages from the requirements document (UCS-2.3.2, UCS-2.3.7)
    static final String MSG_REQUEST_SENT = "Request Sent.";
    static final String MSG_OWN_RIDE = "You can not join your own Ride. Search for another ride.";
    static final String MSG_RIDE_FULL = "Ride is full. Search for another ride.";
    static final String MSG_RIDE_UNAVAILABLE = "Ride is no longer available. Search for another ride.";
    static final String MSG_RIDE_DEPARTED = "Ride has departed. Search for another ride";
    static final String MSG_ALREADY_JOINED = "You already joined this ride.";
    static final String MSG_PENDING_EXISTS = "You have a pending request in progress. Wait for a response.";
    static final String MSG_PREVIOUSLY_REJECTED = "You cannot request to join; a previous request was rejected.";
    static final String MSG_SEND_FAILED = "Could not send request. Try again.";

    private final FirebaseFirestore db;

    public JoinRequestManager() {
        db = FirebaseFirestore.getInstance();
    }

    /**
     * Sends a Pending request from rider to join r (UCS-2.3.2 to UCS-2.3.7). The ride is read again right
     * before saving (UCS-2.3.5). The diagram returns Bool; the callback gets a message instead because the
     * requirements give a different message for each error. It gets MSG_REQUEST_SENT when the request was sent.
     */
    public void requestJoin(Ride r, User rider, String message, Consumer<String> callback) {
        if (rider == null) {
            Log.e(TAG, "requestJoin failed: no user is logged in");
            callback.accept(MSG_SEND_FAILED);
            return;
        }
        String note = message != null ? message.trim() : "";
        // The document ID combines the ride and the rider, so each rider has one request per ride
        DocumentReference requestRef = db.collection(JOIN_REQUESTS).document(r.getRideID() + "_" + rider.getId());
        DocumentReference rideRef = db.collection(RIDES).document(r.getRideID());
        db.runTransaction(transaction -> {
                    Ride current = RideManager.toRide(transaction.get(rideRef));
                    DocumentSnapshot existing = transaction.get(requestRef);
                    String existingStatus = existing.exists() ? existing.getString("status") : null;
                    Date now = new Date(); // UCS-2.3.3: the phone's date and time

                    String error = checkCanRequest(current, rider.getId(), existingStatus, note, now);
                    if (error != null) {
                        return error;
                    }
                    transaction.set(requestRef, toMap(requestRef.getId(), current.getRideID(), rider, now, note));
                    return MSG_REQUEST_SENT;
                }).addOnSuccessListener(callback::accept)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "requestJoin failed", e);
                    callback.accept(MSG_SEND_FAILED);
                });
    }

    /** Only the requester may cancel, and only while the request is Pending. The request is deleted. */
    public void cancelJoinRequest(JoinRequest request, User user, Consumer<Boolean> callback) {
        if (user == null) {
            fail(callback, "No user is logged in");
            return;
        }
        DocumentReference ref = db.collection(JOIN_REQUESTS).document(request.getId());
        db.runTransaction(transaction -> {
                    DocumentSnapshot snapshot = requireRequest(transaction.get(ref));
                    if (!user.getId().equals(snapshot.getString("requesterId"))) {
                        throw abort("Only the requester can cancel this request");
                    }
                    requirePending(snapshot);
                    transaction.delete(ref);
                    return null;
                }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "cancelJoinRequest failed", e);
                    callback.accept(false);
                });
    }

    /** Only the organizer may accept. Adds the requester to the ride, which turns Full when it reaches maxRiders. */
    public void acceptJoinRequest(JoinRequest request, User user, Consumer<Boolean> callback) {
        if (user == null) {
            fail(callback, "No user is logged in");
            return;
        }
        DocumentReference requestRef = db.collection(JOIN_REQUESTS).document(request.getId());
        DocumentReference rideRef = db.collection(RIDES).document(request.getRide().getRideID());
        db.runTransaction(transaction -> {
                    DocumentSnapshot snapshot = requireRequest(transaction.get(requestRef));
                    Ride ride = RideManager.toRide(transaction.get(rideRef));
                    String requesterId = snapshot.getString("requesterId");
                    String error = checkCanAccept(ride, user.getId(), snapshot.getString("status"), requesterId);
                    if (error != null) {
                        throw abort(error);
                    }
                    List<String> riders = new ArrayList<>(ride.getRiders());
                    riders.add(requesterId);
                    Ride updated = new Ride(ride.getRideID(), ride.getOrganizer(), ride.getTitle(), ride.getOrigin(),
                            ride.getDestination(), ride.getDepartureTime(), ride.getMaxRiders(), ride.getTotalExpectedCost(),
                            ride.getNote(), riders, ride.getRideStatus());
                    transaction.set(rideRef, RideManager.toMap(updated));
                    transaction.update(requestRef, "status", RequestStatus.ACCEPTED.name());
                    return null;
                }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "acceptJoinRequest failed", e);
                    callback.accept(false);
                });
    }

    /** Only the organizer may reject. A rejected rider can't request this ride again. */
    public void rejectJoinRequest(JoinRequest request, User user, Consumer<Boolean> callback) {
        if (user == null) {
            fail(callback, "No user is logged in");
            return;
        }
        DocumentReference requestRef = db.collection(JOIN_REQUESTS).document(request.getId());
        DocumentReference rideRef = db.collection(RIDES).document(request.getRide().getRideID());
        db.runTransaction(transaction -> {
                    DocumentSnapshot snapshot = requireRequest(transaction.get(requestRef));
                    Ride ride = RideManager.toRide(transaction.get(rideRef));
                    if (!user.getId().equals(ride.getOrganizer().getId())) {
                        throw abort("Only the organizer can reject requests");
                    }
                    requirePending(snapshot);
                    transaction.update(requestRef, "status", RequestStatus.REJECTED.name());
                    return null;
                }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "rejectJoinRequest failed", e);
                    callback.accept(false);
                });
    }

    /**
     * Every request for one ride, newest first. Only the organizer may see them.
     * The organizer's "Pending Join Requests" list (UCS-2.3.9) calls this for each ride they organize
     * and keeps the Pending ones. Passes null on failure.
     */
    public void getRideRequests(Ride ride, User user, Consumer<List<JoinRequest>> callback) {
        if (user == null || !user.getId().equals(ride.getOrganizer().getId())) {
            Log.e(TAG, "getRideRequests failed: only the organizer can see a ride's requests");
            callback.accept(null);
            return;
        }
        db.collection(JOIN_REQUESTS)
                .whereEqualTo("rideID", ride.getRideID())
                .get()
                .addOnSuccessListener(snapshot -> {
                    List<JoinRequest> requests = new ArrayList<>();
                    for (DocumentSnapshot document : snapshot.getDocuments()) {
                        requests.add(toJoinRequest(document, ride));
                    }
                    sortNewestFirst(requests);
                    callback.accept(requests);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getRideRequests failed", e);
                    callback.accept(null);
                });
    }

    /**
     * Not in the class diagram. The requester's "Join Ride Requests" list (UCS-2.3.8) needs every request
     * a user sent, which getRideRequests can't give because it works one ride at a time. Newest first.
     * Passes null on failure.
     */
    public void getMyRequests(User user, Consumer<List<JoinRequest>> callback) {
        if (user == null) {
            Log.e(TAG, "getMyRequests failed: no user is logged in");
            callback.accept(null);
            return;
        }
        db.collection(JOIN_REQUESTS)
                .whereEqualTo("requesterId", user.getId())
                .get()
                .addOnSuccessListener(snapshot -> {
                    List<DocumentSnapshot> documents = snapshot.getDocuments();
                    List<Task<DocumentSnapshot>> rideReads = new ArrayList<>();
                    for (DocumentSnapshot document : documents) {
                        rideReads.add(db.collection(RIDES).document(document.getString("rideID")).get());
                    }
                    Tasks.whenAllSuccess(rideReads)
                            .addOnSuccessListener(results -> {
                                List<JoinRequest> requests = new ArrayList<>();
                                for (int i = 0; i < documents.size(); i++) {
                                    try {
                                        Ride ride = RideManager.toRide((DocumentSnapshot) results.get(i));
                                        requests.add(toJoinRequest(documents.get(i), ride));
                                    } catch (FirebaseFirestoreException e) {
                                        Log.e(TAG, "Skipping request with unreadable ride " + documents.get(i).getId(), e);
                                    }
                                }
                                sortNewestFirst(requests);
                                callback.accept(requests);
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "getMyRequests failed", e);
                                callback.accept(null);
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getMyRequests failed", e);
                    callback.accept(null);
                });
    }

    // ---- Checks (not in the class diagram; separate from Firestore so they can be unit tested) ----

    /** Returns the UCS-2.3.2 or UCS-2.3.4 message for the first rule that fails, or null if the rider may request. */
    static String checkCanRequest(Ride ride, String riderId, String existingStatus, String message, Date now) {
        if (riderId.equals(ride.getOrganizer().getId())) {
            return MSG_OWN_RIDE;
        }
        if (ride.getRiders().contains(riderId)) {
            return MSG_ALREADY_JOINED;
        }
        String status = ride.getRideStatus();
        if (Status.CANCELLED.name().equals(status) || Status.COMPLETED.name().equals(status)) {
            return MSG_RIDE_UNAVAILABLE;
        }
        if (ride.getDepartureTime() == null || !ride.getDepartureTime().after(now)) {
            return MSG_RIDE_DEPARTED;
        }
        if (CapacityStatus.FULL.name().equals(ride.getCapacityStatus())) {
            return MSG_RIDE_FULL;
        }
        if (RequestStatus.PENDING.name().equals(existingStatus)) {
            return MSG_PENDING_EXISTS;
        }
        if (RequestStatus.REJECTED.name().equals(existingStatus)) {
            return MSG_PREVIOUSLY_REJECTED;
        }
        // An Accepted request from a rider who later left the ride doesn't block a new request
        return checkMessage(message);
    }

    /** UCS-2.3.4: the note is optional but can't be over 250 characters. Returns null if it's fine. */
    static String checkMessage(String message) {
        int length = message != null ? message.length() : 0;
        if (length > MAX_MESSAGE_LENGTH) {
            return "Reduce character count (" + length + "/" + MAX_MESSAGE_LENGTH + ").";
        }
        return null;
    }

    /** Returns why the organizer can't accept this request, or null if they can. */
    static String checkCanAccept(Ride ride, String userId, String requestStatus, String requesterId) {
        if (!userId.equals(ride.getOrganizer().getId())) {
            return "Only the organizer can accept requests";
        }
        if (!RequestStatus.PENDING.name().equals(requestStatus)) {
            return "Request was already answered";
        }
        String status = ride.getRideStatus();
        if (Status.CANCELLED.name().equals(status) || Status.COMPLETED.name().equals(status)) {
            return MSG_RIDE_UNAVAILABLE;
        }
        if (ride.getRiders().contains(requesterId)) {
            return MSG_ALREADY_JOINED;
        }
        if (CapacityStatus.FULL.name().equals(ride.getCapacityStatus())) {
            return MSG_RIDE_FULL;
        }
        return null;
    }

    static void sortNewestFirst(List<JoinRequest> requests) {
        requests.sort((a, b) -> {
            Date first = a.getDateRequested();
            Date second = b.getDateRequested();
            if (first == null || second == null) {
                return first == null ? (second == null ? 0 : 1) : -1;
            }
            return second.compareTo(first);
        });
    }

    private static DocumentSnapshot requireRequest(DocumentSnapshot snapshot) throws FirebaseFirestoreException {
        if (!snapshot.exists()) {
            throw abort("Request not found");
        }
        return snapshot;
    }

    private static void requirePending(DocumentSnapshot snapshot) throws FirebaseFirestoreException {
        if (!RequestStatus.PENDING.name().equals(snapshot.getString("status"))) {
            throw abort("Request was already answered");
        }
    }

    private static FirebaseFirestoreException abort(String message) {
        return new FirebaseFirestoreException(message, FirebaseFirestoreException.Code.ABORTED);
    }

    private static void fail(Consumer<Boolean> callback, String reason) {
        Log.e(TAG, reason);
        callback.accept(false);
    }

    // ---- Firestore mapping (not in the class diagram) ----

    // Like a ride's organizer, only part of the requester is stored (no password hash)
    private static Map<String, Object> toMap(String id, String rideId, User requester, Date dateRequested, String message) {
        Map<String, Object> requesterMap = new HashMap<>();
        requesterMap.put("id", requester.getId());
        requesterMap.put("email", requester.getEmail());
        requesterMap.put("fullName", requester.getFullName());
        requesterMap.put("nickname", requester.getNickname());

        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("id", id);
        requestMap.put("rideID", rideId);
        requestMap.put("requesterId", requester.getId()); // stored separately so requests can be searched by requester
        requestMap.put("requester", requesterMap);
        requestMap.put("dateRequested", dateRequested);
        requestMap.put("message", message);
        requestMap.put("status", RequestStatus.PENDING.name());
        return requestMap;
    }

    @SuppressWarnings("unchecked")
    private static JoinRequest toJoinRequest(DocumentSnapshot document, Ride ride) {
        User requester;
        Object raw = document.get("requester");
        if (raw instanceof Map) {
            Map<String, Object> requesterMap = (Map<String, Object>) raw;
            requester = new User(text(requesterMap.get("id")), text(requesterMap.get("email")), "",
                    text(requesterMap.get("fullName")), text(requesterMap.get("nickname")), 0, 0);
        } else {
            requester = new User(text(document.getString("requesterId")), "", "", "Unknown", "", 0, 0);
        }
        RequestStatus status;
        try {
            status = RequestStatus.valueOf(document.getString("status"));
        } catch (IllegalArgumentException | NullPointerException e) {
            status = RequestStatus.PENDING;
        }
        return new JoinRequest(document.getId(), document.getDate("dateRequested"), document.getString("message"),
                status, ride, requester);
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
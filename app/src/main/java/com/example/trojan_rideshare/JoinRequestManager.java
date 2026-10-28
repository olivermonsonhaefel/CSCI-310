package com.example.trojan_rideshare;

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sending and managing join requests (spec Feature 3, use case 2.3).
 * Method names match the class diagram. Firestore calls finish asynchronously, so each method
 * takes a callback instead of returning a value, like RideManager.
 *
 * The diagram returns Bool from the write methods. Here the callback gets a String instead:
 * null when it worked, otherwise the message to show the user (the exact text from UCS-2.3.2),
 * because a plain true/false can't tell the screen which error to display.
 *
 * Requests are stored in the "joinRequests" collection. The document ID is rideID + "_" + requesterId,
 * so each user has at most one request per ride and a transaction can read it directly.
 */
@SuppressWarnings({"unused", "RedundantSuppression"})
public class JoinRequestManager {
    private static final String TAG = "JoinRequestManager";
    private static final String RIDES = "rides";
    private static final String JOIN_REQUESTS = "joinRequests";
    private static final int IN_QUERY_LIMIT = 10; // Firestore whereIn takes a limited list, so IDs are sent in chunks

    public static final int MAX_MESSAGE_LENGTH = 250;

    // Messages from the requirements document (UCS-2.3.2, UCS-2.3.4, UCS-2.3.7)
    public static final String MSG_REQUEST_SENT = "Request Sent.";
    // UCS-2.3.2
    //(Error Case)
    public static final String MSG_OWN_RIDE = "You can not join your own Ride. Search for another ride.";
    // UCS-2.3.2
    //(Error Case)
    public static final String MSG_RIDE_FULL = "Ride is full. Search for another ride.";
    // UCS-2.3.2
    //(Error Case)
    public static final String MSG_RIDE_UNAVAILABLE = "Ride is no longer available. Search for another ride.";
    //UCS-2.3.2
    //(Error Case)
    public static final String MSG_RIDE_DEPARTED = "Ride has departed. Search for another ride";
    //UCS-2.3.2
    //(Error Case)
    public static final String MSG_ALREADY_JOINED = "You already joined this ride.";
    //UCS-2.3.2
    //(Error Case)
    public static final String MSG_PENDING_EXISTS = "You have a pending request in progress. Wait for a response.";
    //UCS-2.3.2
    //(Error Case)
    public static final String MSG_PREVIOUSLY_REJECTED = "You cannot request to join; a previous request was rejected.";

    // Messages for the other methods (not in the requirements document)
    static final String MSG_NOT_LOGGED_IN = "You need to be logged in.";
    static final String MSG_SEND_FAILED = "Could not send request. Try again.";
    static final String MSG_UPDATE_FAILED = "Could not update the request. Try again.";
    static final String MSG_NOT_ORGANIZER = "Only the ride organizer can respond to requests.";
    static final String MSG_NOT_REQUESTER = "You can only cancel your own requests.";
    static final String MSG_NOT_PENDING = "This request was already answered.";
    static final String MSG_REQUEST_GONE = "This request no longer exists.";

    private final FirebaseFirestore db;

    public JoinRequestManager() {
        this(FirebaseFirestore.getInstance());
    }

    JoinRequestManager(FirebaseFirestore db) {
        this.db = db;
    }

    // ---- Sending a request (UCS-2.3.2 to UCS-2.3.7) ----

    /**
     * Sends a Pending request from rider to join ride r. Checks the ride again inside a transaction
     * right before saving (UCS-2.3.5). Passes null when the request was sent; the screen then shows
     * MSG_REQUEST_SENT. Otherwise passes the message to show.
     */
    public void requestJoin(Ride r, User rider, String message, Consumer<String> callback) {
        if (rider == null) {
            callback.accept(MSG_NOT_LOGGED_IN);
            return;
        }
        String note = message != null ? message.trim() : "";
        String noteError = checkMessage(note);
        if (noteError != null) {
            callback.accept(noteError);
            return;
        }

        DocumentReference rideRef = db.collection(RIDES).document(r.getRideID());
        DocumentReference requestRef = requestRef(r.getRideID(), rider.getId());
        db.runTransaction(transaction -> {
                    DocumentSnapshot rideSnapshot = transaction.get(rideRef);
                    if (!rideSnapshot.exists()) {
                        return MSG_RIDE_UNAVAILABLE;
                    }
                    Ride current = RideManager.toRide(rideSnapshot);
                    DocumentSnapshot existing = transaction.get(requestRef);
                    String existingStatus = existing.exists() ? existing.getString("status") : null;

                    Date now = new Date(); // UCS-2.3.3: the phone's time
                    String error = checkCanRequest(current, rider.getId(), existingStatus, note, now);
                    if (error != null) {
                        return error;
                    }
                    transaction.set(requestRef, toMap(requestRef.getId(), current.getRideID(), rider, now, note));
                    return null;
                }).addOnSuccessListener(callback::accept)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "requestJoin failed", e);
                    callback.accept(MSG_SEND_FAILED);
                });
    }

    /**
     * Returns the message to show when this user can't request to join, or null when they can.
     * No Firestore calls, so it can be unit tested. existingStatus is the status of the user's earlier
     * request for this ride, or null if there is none.
     * An Accepted request from a user who later left the ride doesn't block a new request.
     */
    static String checkCanRequest(Ride ride, String requesterId, String existingStatus, String message, Date now) {
        if (requesterId.equals(ride.getOrganizer().getId())) {
            return MSG_OWN_RIDE;
        }
        if (ride.getRiders().contains(requesterId)) {
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
        return checkMessage(message);
    }

    /** UCS-2.3.4: the note is optional but can't be over 250 characters. Null means it's fine. */
    public static String checkMessage(String message) {
        int length = message != null ? message.length() : 0;
        if (length > MAX_MESSAGE_LENGTH) {
            return "Reduce character count (" + length + "/" + MAX_MESSAGE_LENGTH + ").";
        }
        return null;
    }

    // ---- Managing requests ----

    /** The requester cancels their own Pending request. The request is deleted, so they can ask again later. */
    public void cancelJoinRequest(JoinRequest request, User user, Consumer<String> callback) {
        if (user == null) {
            callback.accept(MSG_NOT_LOGGED_IN);
            return;
        }
        DocumentReference ref = db.collection(JOIN_REQUESTS).document(request.getId());
        db.runTransaction(transaction -> {
                    DocumentSnapshot snapshot = transaction.get(ref);
                    if (!snapshot.exists()) {
                        return MSG_REQUEST_GONE;
                    }
                    if (!user.getId().equals(snapshot.getString("requesterId"))) {
                        return MSG_NOT_REQUESTER;
                    }
                    if (!RequestStatus.PENDING.name().equals(snapshot.getString("status"))) {
                        return MSG_NOT_PENDING;
                    }
                    transaction.delete(ref);
                    return (String) null;
                }).addOnSuccessListener(callback::accept)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "cancelJoinRequest failed", e);
                    callback.accept(MSG_UPDATE_FAILED);
                });
    }

    /**
     * Only the ride's current organizer may accept. Adds the requester to the ride's riders in the
     * same transaction, so the ride turns Full by itself when it reaches maxRiders.
     */
    public void acceptJoinRequest(JoinRequest request, User user, Consumer<String> callback) {
        if (user == null) {
            callback.accept(MSG_NOT_LOGGED_IN);
            return;
        }
        DocumentReference requestRef = db.collection(JOIN_REQUESTS).document(request.getId());
        DocumentReference rideRef = db.collection(RIDES).document(request.getRide().getRideID());
        db.runTransaction(transaction -> {
                    // Firestore needs every read before the first write
                    DocumentSnapshot requestSnapshot = transaction.get(requestRef);
                    DocumentSnapshot rideSnapshot = transaction.get(rideRef);
                    if (!requestSnapshot.exists()) {
                        return MSG_REQUEST_GONE;
                    }
                    if (!rideSnapshot.exists()) {
                        return MSG_RIDE_UNAVAILABLE;
                    }
                    Ride ride = RideManager.toRide(rideSnapshot);
                    String requesterId = requestSnapshot.getString("requesterId");
                    String error = checkCanAccept(ride, user.getId(), requestSnapshot.getString("status"), requesterId);
                    if (error != null) {
                        return error;
                    }

                    List<String> riders = new ArrayList<>(ride.getRiders());
                    riders.add(requesterId);
                    Ride updated = new Ride(ride.getRideID(), ride.getOrganizer(), ride.getTitle(), ride.getOrigin(),
                            ride.getDestination(), ride.getDepartureTime(), ride.getMaxRiders(), ride.getTotalExpectedCost(),
                            ride.getNote(), riders, ride.getRideStatus());
                    // update() changes only these fields and leaves the rest of the ride document alone
                    transaction.update(rideRef,
                            "riders", new ArrayList<>(updated.getRiders()),
                            "numOfRiders", updated.getNumOfRiders(),
                            "capacityStatus", updated.getCapacityStatus());
                    transaction.update(requestRef, "status", RequestStatus.ACCEPTED.name());
                    return (String) null;
                }).addOnSuccessListener(callback::accept)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "acceptJoinRequest failed", e);
                    callback.accept(MSG_UPDATE_FAILED);
                });
    }

    /** Returns the message to show when the organizer can't accept this request, or null when they can. */
    static String checkCanAccept(Ride ride, String userId, String requestStatus, String requesterId) {
        if (!userId.equals(ride.getOrganizer().getId())) {
            return MSG_NOT_ORGANIZER;
        }
        if (!RequestStatus.PENDING.name().equals(requestStatus)) {
            return MSG_NOT_PENDING;
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

    /** Only the ride's current organizer may reject. A rejected user can't request this ride again. */
    public void rejectJoinRequest(JoinRequest request, User user, Consumer<String> callback) {
        if (user == null) {
            callback.accept(MSG_NOT_LOGGED_IN);
            return;
        }
        DocumentReference requestRef = db.collection(JOIN_REQUESTS).document(request.getId());
        DocumentReference rideRef = db.collection(RIDES).document(request.getRide().getRideID());
        db.runTransaction(transaction -> {
                    DocumentSnapshot requestSnapshot = transaction.get(requestRef);
                    DocumentSnapshot rideSnapshot = transaction.get(rideRef);
                    if (!requestSnapshot.exists()) {
                        return MSG_REQUEST_GONE;
                    }
                    if (!rideSnapshot.exists()) {
                        return MSG_RIDE_UNAVAILABLE;
                    }
                    Ride ride = RideManager.toRide(rideSnapshot);
                    if (!user.getId().equals(ride.getOrganizer().getId())) {
                        return MSG_NOT_ORGANIZER;
                    }
                    if (!RequestStatus.PENDING.name().equals(requestSnapshot.getString("status"))) {
                        return MSG_NOT_PENDING;
                    }
                    transaction.update(requestRef, "status", RequestStatus.REJECTED.name());
                    return (String) null;
                }).addOnSuccessListener(callback::accept)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "rejectJoinRequest failed", e);
                    callback.accept(MSG_UPDATE_FAILED);
                });
    }

    // ---- Loading request lists (UCS-2.3.8, UCS-2.3.9) ----

    /** Every request for one ride, for its organizer. Newest first. Passes null on failure. */
    public void getRideRequests(Ride ride, User user, Consumer<List<JoinRequest>> callback) {
        if (user == null || !user.getId().equals(ride.getOrganizer().getId())) {
            Log.e(TAG, "getRideRequests: only the organizer can see a ride's requests");
            callback.accept(null);
            return;
        }
        db.collection(JOIN_REQUESTS)
                .whereEqualTo("rideID", ride.getRideID())
                .get()
                .addOnSuccessListener(query -> {
                    List<JoinRequest> requests = new ArrayList<>();
                    for (DocumentSnapshot doc : query.getDocuments()) {
                        requests.add(toJoinRequest(doc, ride));
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
     * The requester's "Join Ride Requests" list (UCS-2.3.8): every request this user sent, any status.
     * Not in the class diagram. Newest first. Passes null on failure.
     */
    public void getMyRequests(User user, Consumer<List<JoinRequest>> callback) {
        if (user == null) {
            callback.accept(null);
            return;
        }
        db.collection(JOIN_REQUESTS)
                .whereEqualTo("requesterId", user.getId())
                .get()
                .addOnSuccessListener(query -> {
                    List<DocumentSnapshot> docs = query.getDocuments();
                    List<String> rideIds = new ArrayList<>();
                    for (DocumentSnapshot doc : docs) {
                        String rideId = doc.getString("rideID");
                        if (rideId != null && !rideIds.contains(rideId)) {
                            rideIds.add(rideId);
                        }
                    }
                    loadRides(rideIds, rides -> {
                        if (rides == null) {
                            callback.accept(null);
                            return;
                        }
                        List<JoinRequest> requests = new ArrayList<>();
                        for (DocumentSnapshot doc : docs) {
                            Ride ride = rides.get(doc.getString("rideID"));
                            if (ride != null) { // skip requests whose ride was deleted or can't be read
                                requests.add(toJoinRequest(doc, ride));
                            }
                        }
                        sortNewestFirst(requests);
                        callback.accept(requests);
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getMyRequests failed", e);
                    callback.accept(null);
                });
    }

    /**
     * The organizer's "Pending Join Requests" list (UCS-2.3.9): Pending requests on every active ride
     * this user organizes. Not in the class diagram. Looks up rides by their current organizer, so
     * requests move to the new organizer when the old one leaves. Newest first. Passes null on failure.
     */
    public void getPendingRequestsForOrganizer(User user, Consumer<List<JoinRequest>> callback) {
        if (user == null) {
            callback.accept(null);
            return;
        }
        db.collection(RIDES)
                .whereEqualTo("organizer.id", user.getId())
                .get()
                .addOnSuccessListener(rideQuery -> {
                    Map<String, Ride> rides = new HashMap<>();
                    for (DocumentSnapshot doc : rideQuery.getDocuments()) {
                        Ride ride = readRide(doc);
                        if (ride != null && isActive(ride)) {
                            rides.put(ride.getRideID(), ride);
                        }
                    }
                    if (rides.isEmpty()) {
                        callback.accept(new ArrayList<>());
                        return;
                    }
                    List<Task<QuerySnapshot>> queries = new ArrayList<>();
                    for (List<String> chunk : chunks(new ArrayList<>(rides.keySet()))) {
                        queries.add(db.collection(JOIN_REQUESTS).whereIn("rideID", chunk).get());
                    }
                    Tasks.whenAllSuccess(queries)
                            .addOnSuccessListener(results -> {
                                List<JoinRequest> requests = new ArrayList<>();
                                for (Object result : results) {
                                    for (DocumentSnapshot doc : ((QuerySnapshot) result).getDocuments()) {
                                        if (RequestStatus.PENDING.name().equals(doc.getString("status"))) {
                                            requests.add(toJoinRequest(doc, rides.get(doc.getString("rideID"))));
                                        }
                                    }
                                }
                                sortNewestFirst(requests);
                                callback.accept(requests);
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "getPendingRequestsForOrganizer failed", e);
                                callback.accept(null);
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getPendingRequestsForOrganizer failed", e);
                    callback.accept(null);
                });
    }

    // ---- Helpers ----

    private static boolean isActive(Ride ride) {
        String status = ride.getRideStatus();
        return !Status.CANCELLED.name().equals(status) && !Status.COMPLETED.name().equals(status);
    }

    private DocumentReference requestRef(String rideId, String requesterId) {
        return db.collection(JOIN_REQUESTS).document(rideId + "_" + requesterId);
    }

    // Loads rides by document ID. Ride documents use the rideID as their ID (RideManager.createRide).
    private void loadRides(List<String> rideIds, Consumer<Map<String, Ride>> callback) {
        Map<String, Ride> rides = new HashMap<>();
        if (rideIds.isEmpty()) {
            callback.accept(rides);
            return;
        }
        List<Task<QuerySnapshot>> queries = new ArrayList<>();
        for (List<String> chunk : chunks(rideIds)) {
            queries.add(db.collection(RIDES).whereIn(FieldPath.documentId(), chunk).get());
        }
        Tasks.whenAllSuccess(queries)
                .addOnSuccessListener(results -> {
                    for (Object result : results) {
                        for (DocumentSnapshot doc : ((QuerySnapshot) result).getDocuments()) {
                            Ride ride = readRide(doc);
                            if (ride != null) {
                                rides.put(doc.getId(), ride);
                            }
                        }
                    }
                    callback.accept(rides);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Loading rides for requests failed", e);
                    callback.accept(null);
                });
    }

    // Skips rides that can't be read, like the search screen does
    private static Ride readRide(DocumentSnapshot doc) {
        try {
            return RideManager.toRide(doc);
        } catch (FirebaseFirestoreException | RuntimeException e) {
            Log.w(TAG, "Skipping unreadable ride " + doc.getId(), e);
            return null;
        }
    }

    private static List<List<String>> chunks(List<String> ids) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += IN_QUERY_LIMIT) {
            chunks.add(new ArrayList<>(ids.subList(i, Math.min(i + IN_QUERY_LIMIT, ids.size()))));
        }
        return chunks;
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
        requestMap.put("requesterId", requester.getId()); // top-level so it can be queried
        requestMap.put("requester", requesterMap);
        requestMap.put("dateRequested", dateRequested);
        requestMap.put("message", message);
        requestMap.put("status", RequestStatus.PENDING.name());
        return requestMap;
    }

    @SuppressWarnings("unchecked")
    private static JoinRequest toJoinRequest(DocumentSnapshot doc, Ride ride) {
        User requester;
        Object raw = doc.get("requester");
        if (raw instanceof Map) {
            Map<String, Object> requesterMap = (Map<String, Object>) raw;
            requester = new User(text(requesterMap.get("id")), text(requesterMap.get("email")), "",
                    text(requesterMap.get("fullName")), text(requesterMap.get("nickname")), 0, 0);
        } else {
            requester = new User(text(doc.getString("requesterId")), "", "", "Unknown", "", 0, 0);
        }
        String status = doc.getString("status");
        return new JoinRequest(
                doc.getId(),
                doc.getDate("dateRequested"),
                doc.getString("message"),
                status != null ? status : RequestStatus.PENDING.name(),
                ride,
                requester);
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
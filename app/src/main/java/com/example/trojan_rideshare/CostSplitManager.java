package com.example.trojan_rideshare;

import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * UML cost splitting and UCS-2.4 expense operations. Like RideManager, asynchronous
 * operations add Consumer callbacks: false/null means failure, never an empty success.
 * No payment provider exists in the project; payment methods record supplied outcomes.
 */
@SuppressWarnings({"unused", "RedundantSuppression"})
public class CostSplitManager {
    private final FirebaseFirestore db;
    private final AuthManager authManager;

    public CostSplitManager() {
        db = FirebaseFirestore.getInstance();
        authManager = AuthManager.getInstance();
    }

    /** UML overload without custom amounts; CUSTOM requires the other overload. */
    public void divideCost(Ride ride, double actualTotalCost, CostSplitMode mode,
                           Consumer<Boolean> callback) {
        divideCost(ride, actualTotalCost, mode, null, callback);
    }

    /**
     * UML divideCost. Custom amounts are keyed by rider id and include the organizer's
     * own share. Only reimbursements to the organizer become expenses. Equal splits
     * distribute leftover cents in Ride.getRiders() order, including the organizer.
     * One atomic transaction creates the split; a second split is rejected to avoid
     * duplicating debts or overwriting recorded payments (use editExpense instead).
     */
    public void divideCost(Ride ride, double actualTotalCost, CostSplitMode mode,
                           Map<String, Double> customAmounts, Consumer<Boolean> callback) {
        User actor = authManager.getCurrentUser();
        if (actor == null || ride == null) {
            fail(callback, "A logged-in user and ride are required");
            return;
        }
        final long total;
        try {
            total = cents(actualTotalCost);
            if (mode == null || (mode == CostSplitMode.CUSTOM && customAmounts == null)
                    || (mode == CostSplitMode.EQUAL && customAmounts != null)) {
                throw new IllegalArgumentException("Supply custom amounts only for CUSTOM mode");
            }
        } catch (IllegalArgumentException e) {
            fail(callback, e.getMessage());
            return;
        }
        // Snapshot caller-owned input before Firestore schedules/retries the transaction.
        Map<String, Double> custom = customAmounts == null ? null : new HashMap<>(customAmounts);
        Date createdAt = new Date();
        db.runTransaction(transaction -> {
            Ride current = RideManager.toRide(transaction.get(db.collection("rides").document(ride.getRideID())));
            requireCompleted(current);
            requireOrganizer(current, actor);
            List<String> riders = current.getRiders();
            if (riders.isEmpty() || !riders.contains(current.getOrganizer().getId())
                    || new java.util.HashSet<>(riders).size() != riders.size()) {
                throw abort("Ride must contain distinct riders including the organizer");
            }
            Map<String, Long> shares = new LinkedHashMap<>();
            long sum = 0;
            if (mode == CostSplitMode.CUSTOM && !custom.keySet().equals(new java.util.HashSet<>(riders))) {
                throw abort("Custom amounts must include exactly the current riders");
            }
            for (int i = 0; i < riders.size(); i++) {
                String uid = riders.get(i);
                long share;
                if (mode == CostSplitMode.EQUAL) {
                    share = total / riders.size() + (i < total % riders.size() ? 1 : 0);
                } else {
                    Double value = custom.get(uid);
                    if (value == null) {
                        throw abort("Each rider needs a custom amount");
                    }
                    share = cents(value);
                }
                sum = Math.addExact(sum, share);
                shares.put(uid, share);
            }
            if (sum != total) {
                throw abort("Custom amounts must sum to the actual total cost");
            }
            List<Expense> expenses = new ArrayList<>();
            for (String uid : riders) {
                if (uid.equals(current.getOrganizer().getId())) {
                    continue;
                }
                DocumentReference ref = rideExpense(current, uid);
                if (transaction.get(ref).exists()) {
                    throw abort("This ride already has a cost split");
                }
                User payer = toUser(transaction.get(db.collection("users").document(uid)));
                expenses.add(new Expense(ref.getId(), dollars(shares.get(uid)), false,
                        createdAt, payer, current.getOrganizer()));
            }
            // All reads must precede writes in a Firestore transaction.
            for (Expense expense : expenses) {
                transaction.set(db.collection("expenses").document(expense.getId()), toMap(expense));
            }
            return null;
        }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> fail(callback, "divideCost failed", e));
    }

    /** UML markPaid: acknowledge this rider's reimbursement after payment succeeds. */
    public void markPaid(Ride ride, User rider, Consumer<Boolean> callback) {
        updateRidePayment(ride, rider, null, true, callback);
    }

    /**
     * UML flagRider: only the organizer can flag an unpaid rider. The UML RiderFlag's
     * ride, flaggedUser and flaggedBy relationships are stored without adding a type.
     * A flag does not undo an already settled payment.
     */
    public void flagRider(Ride ride, User organizer, User rider, Consumer<Boolean> callback) {
        if (organizer == null) {
            fail(callback, "Organizer is required");
            return;
        }
        updateRidePayment(ride, rider, organizer, false, callback);
    }

    private void updateRidePayment(Ride ride, User rider, User organizer, boolean paid,
                                   Consumer<Boolean> callback) {
        User actor = authManager.getCurrentUser();
        if (actor == null || ride == null || rider == null) {
            fail(callback, "A logged-in user, ride and rider are required");
            return;
        }
        db.runTransaction(transaction -> {
            Ride current = RideManager.toRide(transaction.get(db.collection("rides").document(ride.getRideID())));
            requireCompleted(current);
            if (!current.getRiders().contains(rider.getId())
                    || current.getOrganizer().getId().equals(rider.getId())) {
                throw abort("Rider must be a non-organizer participant");
            }
            if (paid) {
                if (!actor.getId().equals(rider.getId())
                        && !actor.getId().equals(current.getOrganizer().getId())) {
                    throw abort("Only the payer or organizer can acknowledge payment");
                }
            } else {
                requireOrganizer(current, actor);
                requireOrganizer(current, organizer);
            }
            DocumentReference ref = rideExpense(current, rider.getId());
            Expense expense = toExpense(transaction.get(ref));
            if (!expense.getPayer().getId().equals(rider.getId())
                    || !expense.getPayee().getId().equals(current.getOrganizer().getId())) {
                throw abort("Expense participants do not match the ride");
            }
            DocumentReference flag = db.collection("riderFlags").document(ref.getId());
            if (paid) {
                transaction.update(ref, "paid", true);
                transaction.delete(flag);
            } else {
                if (expense.isPaid()) {
                    throw abort("A settled expense cannot be flagged as unpaid");
                }
                Map<String, Object> values = new HashMap<>();
                values.put("ride", db.collection("rides").document(current.getRideID()));
                values.put("flaggedUser", db.collection("users").document(rider.getId()));
                values.put("flaggedBy", db.collection("users").document(organizer.getId()));
                transaction.set(flag, values);
            }
            return null;
        }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> fail(callback, "Payment recording failed", e));
    }

    /** UCS-2.4.1–2: record an externally supplied outcome; failure leaves the debt unpaid. */
    public void recordPayment(Expense expense, boolean successful, Consumer<Boolean> callback) {
        if (expense == null) {
            fail(callback, "Expense is required");
            return;
        }
        User actor = authManager.getCurrentUser();
        if (actor == null) {
            fail(callback, "No user is logged in");
            return;
        }
        DocumentReference ref = db.collection("expenses").document(expense.getId());
        db.runTransaction(transaction -> {
            Expense current = toExpense(transaction.get(ref));
            requireParticipant(current, actor);
            if (!successful && current.isPaid()) {
                throw abort("A failed attempt cannot undo a settled payment");
            }
            transaction.update(ref, "paid", successful);
            if (successful) {
                transaction.delete(db.collection("riderFlags").document(ref.getId()));
            }
            return null;
        }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> fail(callback, "recordPayment failed", e));
    }

    /** UCS-2.4.5: all expenses involving the logged-in user, newest first. */
    public void getExpenses(User user, Consumer<List<Expense>> callback) {
        if (!isCurrentUser(user)) {
            Log.e("CostSplitManager", "Only the logged-in user's expenses may be retrieved");
            callback.accept(null);
            return;
        }
        // Two single-field queries avoid requiring a new composite index or schema field.
        Tasks.whenAllSuccess(
                db.collection("expenses").whereEqualTo("payer.id", user.getId()).get(),
                db.collection("expenses").whereEqualTo("payee.id", user.getId()).get())
                .addOnSuccessListener(results -> {
                    List<Expense> expenses = new ArrayList<>();
                    try {
                        for (Object result : results) {
                            for (DocumentSnapshot snapshot : ((QuerySnapshot) result).getDocuments()) {
                                expenses.add(toExpense(snapshot));
                            }
                        }
                        expenses.sort(Comparator.comparing(Expense::getCreatedAt).reversed()
                                .thenComparing(Expense::getId));
                    } catch (Exception e) {
                        Log.e("CostSplitManager", "Invalid expense data", e);
                        callback.accept(null);
                        return;
                    }
                    callback.accept(expenses);
                }).addOnFailureListener(e -> {
                    Log.e("CostSplitManager", "getExpenses failed", e);
                    callback.accept(null);
                });
    }

    /**
     * Read the selected ride's reimbursements for organizer actions such as flagRider.
     * Uses the same stable ids as divideCost/markPaid, without a new Expense field.
     * Missing expenses are omitted; an empty list means there are no reimbursements.
     */
    public void getRideExpenses(Ride ride, Consumer<List<Expense>> callback) {
        User actor = authManager.getCurrentUser();
        if (actor == null || ride == null) {
            Log.e("CostSplitManager", "A logged-in organizer and ride are required");
            callback.accept(null);
            return;
        }
        db.runTransaction(transaction -> {
            Ride current = RideManager.toRide(transaction.get(db.collection("rides").document(ride.getRideID())));
            requireOrganizer(current, actor);
            requireCompleted(current);
            List<Expense> results = new ArrayList<>();
            for (String uid : current.getRiders()) {
                if (uid.equals(current.getOrganizer().getId())) continue;
                DocumentSnapshot snapshot = transaction.get(rideExpense(current, uid));
                if (!snapshot.exists()) continue;
                Expense expense = toExpense(snapshot);
                if (!uid.equals(expense.getPayer().getId())
                        || !actor.getId().equals(expense.getPayee().getId())) {
                    throw abort("Expense participants do not match the ride");
                }
                results.add(expense);
            }
            return results;
        }).addOnSuccessListener(callback::accept)
                .addOnFailureListener(error -> {
                    Log.e("CostSplitManager", "getRideExpenses failed", error);
                    callback.accept(null);
                });
    }

    /** UCS-2.4.6. */
    public void getSettledExpenses(User user, Consumer<List<Expense>> callback) {
        getExpenseHistory(user, null, callback);
    }

    /** UCS-2.4.7: settled history, optionally filtered to the other user in either direction. */
    public void getExpenseHistory(User user, User otherUser, Consumer<List<Expense>> callback) {
        getExpenses(user, expenses -> {
            if (expenses == null) {
                callback.accept(null);
                return;
            }
            expenses.removeIf(expense -> !expense.isPaid() || (otherUser != null
                    && !expense.getPayer().getId().equals(otherUser.getId())
                    && !expense.getPayee().getId().equals(otherUser.getId())));
            callback.accept(expenses);
        });
    }

    /** UCS-2.4.11: create an unpaid expense without overwriting an existing expense. */
    public void createExpense(Expense expense, Consumer<Boolean> callback) {
        User actor = authManager.getCurrentUser();
        if (actor == null || expense == null || expense.isPaid()) {
            fail(callback, "A logged-in user and unpaid expense are required");
            return;
        }
        DocumentReference ref = db.collection("expenses").document(expense.getId());
        db.runTransaction(transaction -> {
            requireParticipant(expense, actor);
            if (transaction.get(ref).exists()) {
                throw abort("Expense already exists");
            }
            // Both User relationships must refer to existing profiles.
            toUser(transaction.get(db.collection("users").document(expense.getPayer().getId())));
            toUser(transaction.get(db.collection("users").document(expense.getPayee().getId())));
            transaction.set(ref, toMap(expense));
            return null;
        }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> fail(callback, "createExpense failed", e));
    }

    /** UCS-2.4.12: edit amount; preserve identity, participants, date and payment outcome. */
    public void editExpense(Expense expense, double amount, Consumer<Boolean> callback) {
        try {
            cents(amount);
        } catch (IllegalArgumentException e) {
            fail(callback, e.getMessage());
            return;
        }
        mutateExpense(expense, amount, false, callback);
    }

    /** UCS-2.4.13. Subsequent expense/balance reads reflect the deletion. */
    public void deleteExpense(Expense expense, Consumer<Boolean> callback) {
        mutateExpense(expense, 0, true, callback);
    }

    private void mutateExpense(Expense expense, double amount, boolean delete, Consumer<Boolean> callback) {
        User actor = authManager.getCurrentUser();
        if (actor == null || expense == null) {
            fail(callback, "A logged-in user and expense are required");
            return;
        }
        DocumentReference ref = db.collection("expenses").document(expense.getId());
        db.runTransaction(transaction -> {
            Expense current = toExpense(transaction.get(ref));
            requireParticipant(current, actor);
            if (delete) {
                transaction.delete(ref);
                transaction.delete(db.collection("riderFlags").document(ref.getId()));
            } else {
                transaction.update(ref, "amount", amount);
            }
            return null;
        }).addOnSuccessListener(result -> callback.accept(true))
                .addOnFailureListener(e -> fail(callback, "Expense change failed", e));
    }

    /** UCS-2.4.4, 9: outgoing unpaid total only. */
    public void getTotalOwed(User user, Consumer<Double> callback) {
        getTotal(user, true, callback);
    }

    /** UCS-2.4.4, 8: incoming unpaid total only. No net total is calculated (2.4.10). */
    public void getTotalToReceive(User user, Consumer<Double> callback) {
        getTotal(user, false, callback);
    }

    private void getTotal(User user, boolean outgoing, Consumer<Double> callback) {
        getExpenses(user, expenses -> {
            if (expenses == null) {
                callback.accept(null);
                return;
            }
            try {
                long total = 0;
                for (Expense expense : expenses) {
                    User endpoint = outgoing ? expense.getPayer() : expense.getPayee();
                    if (!expense.isPaid() && endpoint.getId().equals(user.getId())) {
                        total = Math.addExact(total, cents(expense.getAmount()));
                    }
                }
                callback.accept(dollars(total));
            } catch (ArithmeticException e) {
                Log.e("CostSplitManager", "Expense total exceeds supported amount", e);
                callback.accept(null);
            }
        });
    }

    /**
     * UCS-2.4.3, 14: payer id -> payee id -> unpaid dollars, involving this user.
     * Opposite directions remain separate; individual balances are never netted.
     */
    public void getPairwiseBalances(User user, Consumer<Map<String, Map<String, Double>>> callback) {
        getExpenses(user, expenses -> {
            if (expenses == null) {
                callback.accept(null);
                return;
            }
            Map<String, Map<String, Long>> amounts = new LinkedHashMap<>();
            try {
                for (Expense expense : expenses) {
                    if (!expense.isPaid()) {
                        Map<String, Long> outgoing = amounts.computeIfAbsent(
                                expense.getPayer().getId(), key -> new LinkedHashMap<>());
                        String payee = expense.getPayee().getId();
                        outgoing.put(payee, Math.addExact(outgoing.getOrDefault(payee, 0L),
                                cents(expense.getAmount())));
                    }
                }
            } catch (ArithmeticException e) {
                Log.e("CostSplitManager", "Pairwise balance exceeds supported amount", e);
                callback.accept(null);
                return;
            }
            Map<String, Map<String, Double>> balances = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Long>> payer : amounts.entrySet()) {
                Map<String, Double> outgoing = new LinkedHashMap<>();
                for (Map.Entry<String, Long> payee : payer.getValue().entrySet()) {
                    outgoing.put(payee.getKey(), dollars(payee.getValue()));
                }
                balances.put(payer.getKey(), outgoing);
            }
            callback.accept(balances);
        });
    }

    /**
     * UCS-2.4.15: propose Z -> Y transfers for chains Z -> user -> Y. This is a
     * read-only result: original expenses, settled history and individual pairwise
     * balances remain available. It neither settles expenses nor computes a net total.
     */
    public void simplifyDebts(User user, Consumer<List<Expense>> callback) {
        getExpenses(user, expenses -> {
            if (expenses == null) {
                callback.accept(null);
                return;
            }
            List<Expense> incoming = new ArrayList<>();
            List<Expense> outgoing = new ArrayList<>();
            for (Expense expense : expenses) {
                if (!expense.isPaid() && expense.getAmount() > 0) {
                    (expense.getPayee().getId().equals(user.getId()) ? incoming : outgoing).add(expense);
                }
            }
            List<Expense> simplified = new ArrayList<>();
            long[] remaining = new long[outgoing.size()];
            for (int i = 0; i < outgoing.size(); i++) {
                remaining[i] = cents(outgoing.get(i).getAmount());
            }
            for (Expense credit : incoming) {
                long available = cents(credit.getAmount());
                for (int i = 0; i < outgoing.size() && available > 0; i++) {
                    Expense debt = outgoing.get(i);
                    // Preserve reciprocal debts; do not turn them into a self-payment/net balance.
                    if (credit.getPayer().getId().equals(debt.getPayee().getId())) {
                        continue;
                    }
                    long transfer = Math.min(available, remaining[i]);
                    if (transfer > 0) {
                        simplified.add(new Expense(dollars(transfer), credit.getPayer(), debt.getPayee()));
                        available -= transfer;
                        remaining[i] -= transfer;
                    }
                }
                if (available > 0) {
                    simplified.add(new Expense(credit.getId(), dollars(available), false,
                            credit.getCreatedAt(), credit.getPayer(), credit.getPayee()));
                }
            }
            for (int i = 0; i < outgoing.size(); i++) {
                Expense debt = outgoing.get(i);
                if (remaining[i] > 0) {
                    simplified.add(new Expense(debt.getId(), dollars(remaining[i]), false,
                            debt.getCreatedAt(), debt.getPayer(), debt.getPayee()));
                }
            }
            callback.accept(simplified);
        });
    }

    // A stable UUID uses the existing ride identity without adding a rideID to Expense.
    // This lets UML markPaid/flagRider find the ride's reimbursement in "expenses".
    private DocumentReference rideExpense(Ride ride, String riderId) {
        String id = UUID.nameUUIDFromBytes((ride.getRideID() + ":" + riderId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return db.collection("expenses").document(id);
    }

    private boolean isCurrentUser(User user) {
        User actor = authManager.getCurrentUser();
        return actor != null && user != null && actor.getId().equals(user.getId());
    }

    private static void requireCompleted(Ride ride) throws FirebaseFirestoreException {
        if (!Status.COMPLETED.name().equals(ride.getRideStatus())) {
            throw abort("Cost sharing requires a completed ride");
        }
    }

    private static void requireOrganizer(Ride ride, User user) throws FirebaseFirestoreException {
        if (user == null || !ride.getOrganizer().getId().equals(user.getId())) {
            throw abort("Only the organizer may perform this operation");
        }
    }

    private static void requireParticipant(Expense expense, User user) throws FirebaseFirestoreException {
        if (!expense.getPayer().getId().equals(user.getId())
                && !expense.getPayee().getId().equals(user.getId())) {
            throw abort("Only the payer or payee may change this expense");
        }
    }

    private static long cents(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Amount must be a nonnegative US dollar amount");
        }
        try {
            return BigDecimal.valueOf(amount).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount must fit in whole cents", e);
        }
    }

    private static double dollars(long cents) {
        return BigDecimal.valueOf(cents, 2).doubleValue();
    }

    private static Map<String, Object> toMap(Expense expense) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", expense.getId());
        values.put("amount", expense.getAmount());
        values.put("paid", expense.isPaid());
        values.put("createdAt", expense.getCreatedAt());
        values.put("payer", toMap(expense.getPayer()));
        values.put("payee", toMap(expense.getPayee()));
        return values;
    }

    // Same profile subset used by RideManager: never serialize User's password hash.
    private static Map<String, Object> toMap(User user) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", user.getId());
        values.put("email", user.getEmail());
        values.put("fullName", user.getFullName());
        values.put("nickname", user.getNickname());
        return values;
    }

    private static Expense toExpense(DocumentSnapshot snapshot) throws FirebaseFirestoreException {
        if (!snapshot.exists()) {
            throw abort("Expense not found");
        }
        Object amount = snapshot.get("amount");
        Boolean paid = snapshot.getBoolean("paid");
        if (!(amount instanceof Number) || paid == null
                || !snapshot.getId().equals(snapshot.getString("id"))) {
            throw abort("Invalid expense fields");
        }
        return new Expense(snapshot.getId(), ((Number) amount).doubleValue(), paid,
                snapshot.getDate("createdAt"), toUser(snapshot.get("payer")), toUser(snapshot.get("payee")));
    }

    private static User toUser(DocumentSnapshot snapshot) throws FirebaseFirestoreException {
        if (!snapshot.exists()) {
            throw abort("User not found");
        }
        Map<String, Object> values = snapshot.getData();
        if (values == null) {
            throw abort("User profile is missing");
        }
        values = new HashMap<>(values);
        values.put("id", snapshot.getId());
        return toUser(values);
    }

    private static User toUser(Object value) throws FirebaseFirestoreException {
        if (!(value instanceof Map)) {
            throw abort("User relationship is missing");
        }
        Map<?, ?> values = (Map<?, ?>) value;
        Object id = values.get("id");
        if (!(id instanceof String) || ((String) id).isEmpty()) {
            throw abort("User id is missing");
        }
        return new User((String) id, (String) values.get("email"), "",
                (String) values.get("fullName"), (String) values.get("nickname"), 0, 0);
    }

    private static FirebaseFirestoreException abort(String message) {
        return new FirebaseFirestoreException(message, FirebaseFirestoreException.Code.ABORTED);
    }

    private static void fail(Consumer<Boolean> callback, String reason) {
        Log.e("CostSplitManager", reason);
        callback.accept(false);
    }

    private static void fail(Consumer<Boolean> callback, String reason, Exception error) {
        Log.e("CostSplitManager", reason, error);
        callback.accept(false);
    }
}

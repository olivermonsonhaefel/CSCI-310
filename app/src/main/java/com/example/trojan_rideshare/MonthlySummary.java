package com.example.trojan_rideshare;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Monthly ride summary (spec Feature 5). A ride belongs to the month of its scheduled departure,
 * not the month it was marked Completed or its cost was split. Cancelled rides are excluded.
 */
public final class MonthlySummary {
    private final int completedRides;
    private final double assignedCost; // paid and unpaid
    private final int organizedRides;
    private final int joinedRides;

    private MonthlySummary(int completedRides, double assignedCost, int organizedRides, int joinedRides) {
        this.completedRides = completedRides;
        this.assignedCost = assignedCost;
        this.organizedRides = organizedRides;
        this.joinedRides = joinedRides;
    }

    /** @param month a Calendar month, starting at 0 for January */
    static MonthlySummary of(List<Ride> rides, Map<String, Expense> expensesById,
                             Map<String, Double> organizerShares, String uid, int year, int month) {
        int completed = 0;
        int organized = 0;
        int joined = 0;
        long costCents = 0;
        Calendar scheduled = Calendar.getInstance();
        for (Ride ride : rides) {
            Date departure = ride.getDepartureTime();
            if (departure == null || Status.CANCELLED.name().equals(ride.getRideStatus())) {
                continue;
            }
            scheduled.setTime(departure);
            if (scheduled.get(Calendar.YEAR) != year || scheduled.get(Calendar.MONTH) != month) {
                continue;
            }
            boolean isOrganizer = uid.equals(ride.getOrganizer().getId());
            if (isOrganizer) {
                organized++;
            } else {
                joined++;
            }
            if (Status.COMPLETED.name().equals(ride.getRideStatus())) {
                completed++;
                costCents += Math.round(assignedCost(ride, expensesById, organizerShares, uid, isOrganizer) * 100);
            }
        }
        return new MonthlySummary(completed, costCents / 100.0, organized, joined);
    }

    // Riders' shares are expenses owed to the organizer; the organizer's own share is stored on the ride
    private static double assignedCost(Ride ride, Map<String, Expense> expensesById,
                                       Map<String, Double> organizerShares, String uid, boolean isOrganizer) {
        if (isOrganizer) {
            return organizerShares.getOrDefault(ride.getRideID(), 0.0);
        }
        Expense expense = expensesById.get(CostSplitManager.rideExpenseId(ride.getRideID(), uid));
        return expense != null ? expense.getAmount() : 0;
    }

    public boolean hasActivity() { return organizedRides + joinedRides > 0; }
    public int getCompletedRides() { return completedRides; }
    public double getAssignedCost() { return assignedCost; }
    public int getOrganizedRides() { return organizedRides; }
    public int getJoinedRides() { return joinedRides; }
}

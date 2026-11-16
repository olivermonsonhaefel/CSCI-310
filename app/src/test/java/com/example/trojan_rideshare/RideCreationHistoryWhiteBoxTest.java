package com.example.trojan_rideshare;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * White-box tests for ride creation/management (Ride, RideManager.parseCost) and ride history
 * (MonthlySummary). Inputs are chosen to drive every branch of the methods under test.
 * See docs/TESTING.md.
 */
public class RideCreationHistoryWhiteBoxTest {

    private static final Location USC = new Location("USC", 34.0224, -118.2851);
    private static final Location LAX = new Location("LAX", 33.9416, -118.4085);
    private static final User ME = new User("me", "me@usc.edu", "", "Me Trojan", "me", 2027, 1);
    private static final User OTHER = new User("other", "other@usc.edu", "", "Other Trojan", "other", 2027, 2);
    private static final double DELTA = 1e-9;

    // W1: both outcomes of the format check, including the ".5" alternative of the regex
    @Test
    public void parseCostAcceptsOnlyDollarFormats() {
        assertEquals(12.5, RideManager.parseCost("12.50"), DELTA);
        assertEquals(0.5, RideManager.parseCost(".5"), DELTA);
        assertEquals(-1, RideManager.parseCost("1.234"), DELTA);
        assertEquals(-1, RideManager.parseCost("-5"), DELTA);
        assertEquals(-1, RideManager.parseCost("abc"), DELTA);
        assertEquals(-1, RideManager.parseCost(""), DELTA);
        assertEquals(-1, RideManager.parseCost(null), DELTA);
    }

    // W2: every path of Ride.computeCapacityStatus, including the >= boundary
    @Test
    public void capacityStatusIsFullAtMaxAndNAWhenFinished() {
        List<String> two = Arrays.asList("me", "a");
        List<String> three = Arrays.asList("me", "a", "b");
        assertEquals(CapacityStatus.OPEN.name(), ride("r", ME, Status.ON_TIME, date(2026, Calendar.NOVEMBER, 20), two, 3, 40, null).getCapacityStatus());
        assertEquals(CapacityStatus.FULL.name(), ride("r", ME, Status.DELAYED, date(2026, Calendar.NOVEMBER, 20), three, 3, 40, null).getCapacityStatus());
        assertEquals(CapacityStatus.N_A.name(), ride("r", ME, Status.COMPLETED, date(2026, Calendar.NOVEMBER, 20), three, 3, 40, null).getCapacityStatus());
        assertEquals(CapacityStatus.N_A.name(), ride("r", ME, Status.CANCELLED, date(2026, Calendar.NOVEMBER, 20), three, 3, 40, null).getCapacityStatus());
    }

    // W3: nickname/full-name branch and optional-note branch of Ride.getDisplayInfo
    @Test
    public void displayInfoUsesNicknameFallbackAndOptionalNote() {
        User withNick = new User("o", "o@usc.edu", "", "Tommy Trojan", "Tommy", 2027, 3);
        User emptyNick = new User("o", "o@usc.edu", "", "Tommy Trojan", "", 2027, 3);
        User nullNick = new User("o", "o@usc.edu", "", "Tommy Trojan", null, 2027, 3);
        Date when = date(2026, Calendar.NOVEMBER, 20);
        List<String> riders = Collections.singletonList("o");

        String nickInfo = ride("r", withNick, Status.ON_TIME, when, riders, 3, 40, "Bring bags").getDisplayInfo();
        assertTrue(nickInfo.contains("\nOrganizer: Tommy\n"));
        assertTrue(nickInfo.contains("\nNote: Bring bags"));
        assertTrue(nickInfo.contains("$40.00 total, $13.33 per person"));

        String emptyInfo = ride("r", emptyNick, Status.ON_TIME, when, riders, 3, 40, "").getDisplayInfo();
        assertTrue(emptyInfo.contains("\nOrganizer: Tommy Trojan\n"));
        assertFalse(emptyInfo.contains("Note:"));

        String nullInfo = ride("r", nullNick, Status.ON_TIME, when, riders, 3, 40, null).getDisplayInfo();
        assertTrue(nullInfo.contains("\nOrganizer: Tommy Trojan\n"));
        assertFalse(nullInfo.contains("Note:"));
    }

    // W4: every skip in MonthlySummary.of, the organizer/joined split, and both outcomes of hasActivity
    @Test
    public void monthlySummaryCountsOnlyActiveRidesInSelectedMonth() {
        List<String> meAndOther = Arrays.asList("other", "me");
        List<Ride> rides = Arrays.asList(
                ride("organized", ME, Status.ON_TIME, date(2026, Calendar.NOVEMBER, 5), meAndOther, 3, 30, null),
                ride("joined", OTHER, Status.DELAYED, date(2026, Calendar.NOVEMBER, 6), meAndOther, 3, 30, null),
                ride("cancelled", ME, Status.CANCELLED, date(2026, Calendar.NOVEMBER, 7), meAndOther, 3, 30, null),
                ride("noDate", ME, Status.ON_TIME, null, meAndOther, 3, 30, null),
                ride("otherMonth", ME, Status.ON_TIME, date(2026, Calendar.OCTOBER, 5), meAndOther, 3, 30, null),
                ride("otherYear", OTHER, Status.ON_TIME, date(2025, Calendar.NOVEMBER, 5), meAndOther, 3, 30, null));

        MonthlySummary summary = MonthlySummary.of(rides, new HashMap<>(), new HashMap<>(), "me", 2026, Calendar.NOVEMBER);
        assertEquals(1, summary.getOrganizedRides());
        assertEquals(1, summary.getJoinedRides());
        assertEquals(0, summary.getCompletedRides());
        assertEquals(0, summary.getAssignedCost(), DELTA);
        assertTrue(summary.hasActivity());

        MonthlySummary empty = MonthlySummary.of(rides, new HashMap<>(), new HashMap<>(), "me", 2026, Calendar.DECEMBER);
        assertFalse(empty.hasActivity());
    }

    // W5: cost branches of MonthlySummary.of/assignedCost and rounding to cents
    @Test
    public void monthlySummaryAssignsCostOnlyForCompletedRides() {
        List<String> meAndOther = Arrays.asList("other", "me");
        Date nov = date(2026, Calendar.NOVEMBER, 10);
        List<Ride> rides = Arrays.asList(
                ride("organizedDone", ME, Status.COMPLETED, nov, meAndOther, 3, 30, null),
                ride("joinedDone", OTHER, Status.COMPLETED, nov, meAndOther, 3, 30, null),
                ride("joinedNoSplit", OTHER, Status.COMPLETED, nov, meAndOther, 3, 30, null),
                ride("joinedUpcoming", OTHER, Status.ON_TIME, nov, meAndOther, 3, 30, null));

        Map<String, Double> organizerShares = new HashMap<>();
        organizerShares.put("organizedDone", 7.125); // 712.5 cents rounds up to 713
        Map<String, Expense> expenses = new HashMap<>();
        putExpense(expenses, "joinedDone", 5.50);
        putExpense(expenses, "joinedUpcoming", 50.00); // not completed, so it must not count

        MonthlySummary summary = MonthlySummary.of(rides, expenses, organizerShares, "me", 2026, Calendar.NOVEMBER);
        assertEquals(3, summary.getCompletedRides());
        assertEquals(1, summary.getOrganizedRides());
        assertEquals(3, summary.getJoinedRides());
        assertEquals(12.63, summary.getAssignedCost(), DELTA);
    }

    private static void putExpense(Map<String, Expense> expenses, String rideId, double amount) {
        String id = CostSplitManager.rideExpenseId(rideId, "me");
        expenses.put(id, new Expense(id, amount, false, new Date(), ME, OTHER, rideId));
    }

    private static Ride ride(String id, User organizer, Status status, Date departure, List<String> riders,
                             int maxRiders, double cost, String note) {
        return new Ride(id, organizer, "To LAX", USC, LAX, departure, maxRiders, cost, note, riders, status.name());
    }

    private static Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month, day, 12, 0);
        return calendar.getTime();
    }
}

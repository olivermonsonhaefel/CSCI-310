package com.example.trojan_rideshare;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class RideSearchManagerTest {

    private static final Location USC = new Location("USC", 34.0224, -118.2851);
    private static final Location LAX = new Location("LAX", 33.9416, -118.4085);
    private static final Location NEAR_USC = new Location("Near USC", 34.0274, -118.2851);
    private static final Location NEAR_LAX = new Location("Near LAX", 33.9466, -118.4085);

    private RideSearchManager manager;

    @Before
    public void setUp() {
        manager = new RideSearchManager(null, null);
        manager.setCurrentUserId("me");
        manager.setSearchContext(USC, LAX, at(2026, Calendar.NOVEMBER, 2, 11, 0));
    }

    @Test
    public void filterByAvailableKeepsOpenRidesOnly() {
        Ride open = ride("open", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 30), USC, LAX, 1, 3, 30);
        Ride full = ride("full", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 30), USC, LAX, 2, 2, 30);
        Ride completed = ride("done", "other", Status.COMPLETED.name(), at(2026, Calendar.NOVEMBER, 2, 11, 30), USC, LAX, 1, 3, 30);

        List<Ride> kept = manager.filterByAvailable(Arrays.asList(open, full, completed));

        assertEquals(1, kept.size());
        assertEquals("open", kept.get(0).getRideID());
    }

    @Test
    public void filterByStatusKeepsTheRequestedStatus() {
        Ride onTime = ride("on", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 30), USC, LAX, 1, 3, 20);
        Ride delayed = ride("late", "other", Status.DELAYED.name(), at(2026, Calendar.NOVEMBER, 2, 12, 0), USC, LAX, 1, 3, 20);

        List<Ride> kept = manager.filterByStatus(Arrays.asList(onTime, delayed), Status.DELAYED);

        assertEquals(1, kept.size());
        assertEquals("late", kept.get(0).getRideID());
    }

    @Test
    public void filterByParticipantsDropsTheOrganizersOwnRide() {
        Ride mine = ride("mine", "me", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 30), USC, LAX, 1, 3, 20);
        Ride theirs = ride("theirs", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 30), USC, LAX, 1, 3, 20);

        List<Ride> kept = manager.filterByParticipants(Arrays.asList(mine, theirs));

        assertEquals(1, kept.size());
        assertEquals("theirs", kept.get(0).getRideID());
    }

    @Test
    public void filterByOriginAndDestinationUseTheRadius() {
        Ride close = ride("close", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 30), NEAR_USC, NEAR_LAX, 1, 3, 20);
        Ride farOrigin = ride("far", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 30), LAX, NEAR_LAX, 1, 3, 20);

        List<Ride> byOrigin = manager.filterByOrigin(Arrays.asList(close, farOrigin), USC, 1);
        List<Ride> byDestination = manager.filterByDestination(byOrigin, LAX, 1);

        assertEquals(1, byOrigin.size());
        assertEquals("close", byDestination.get(0).getRideID());
    }

    @Test
    public void filterByDateAndTimeWindow() {
        Ride inside = ride("inside", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 12, 0), USC, LAX, 1, 3, 20);
        Ride otherDay = ride("other-day", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 3, 12, 0), USC, LAX, 1, 3, 20);
        Ride early = ride("early", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 9, 0), USC, LAX, 1, 3, 20);
        List<Ride> rides = Arrays.asList(inside, otherDay, early);

        List<Ride> onDay = manager.filterByDate(rides, at(2026, Calendar.NOVEMBER, 2, 0, 0));
        List<Ride> inWindow = manager.filterByTimeRange(onDay, at(2026, Calendar.NOVEMBER, 2, 11, 0), at(2026, Calendar.NOVEMBER, 2, 13, 0));

        assertEquals(2, onDay.size());
        assertEquals(1, inWindow.size());
        assertEquals("inside", inWindow.get(0).getRideID());
    }

    @Test
    public void sortByEarliestBreaksTiesWithProximity() {
        Date sameTime = at(2026, Calendar.NOVEMBER, 2, 12, 0);
        Ride closer = ride("closer", "other", Status.ON_TIME.name(), sameTime, NEAR_USC, NEAR_LAX, 1, 3, 50);
        Ride farther = ride("farther", "other", Status.ON_TIME.name(), sameTime, new Location("Far", 34.10, -118.50), LAX, 1, 3, 10);
        Ride earlier = ride("earlier", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 15), LAX, NEAR_LAX, 1, 3, 10);

        List<String> order = ids(manager.sortByEarliest(Arrays.asList(farther, closer, earlier)));

        assertEquals(Arrays.asList("earlier", "closer", "farther"), order);
    }

    @Test
    public void sortByLatestPutsTheLatestDepartureFirst() {
        Ride early = ride("early", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 15), USC, LAX, 1, 3, 20);
        Ride late = ride("late", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 12, 45), USC, LAX, 1, 3, 20);

        List<String> order = ids(manager.sortByLatest(Arrays.asList(early, late)));

        assertEquals(Arrays.asList("late", "early"), order);
    }

    @Test
    public void sortByProximityBreaksTiesWithEarlierDeparture() {
        Ride later = ride("later", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 12, 30), NEAR_USC, NEAR_LAX, 1, 3, 20);
        Ride sooner = ride("sooner", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 15), NEAR_USC, NEAR_LAX, 1, 3, 40);
        Ride farther = ride("farther", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 0), new Location("Far", 34.10, -118.50), LAX, 1, 3, 5);

        List<String> order = ids(manager.sortByProximity(Arrays.asList(farther, later, sooner)));

        assertEquals(Arrays.asList("sooner", "later", "farther"), order);
    }

    @Test
    public void sortByProximityAndTimeCanRankASoonerFartherRideAhead() {
        Ride nearButLate = ride("near-late", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 16, 0), NEAR_USC, NEAR_LAX, 1, 3, 20);
        Ride fartherButSoon = ride("far-soon", "other", Status.ON_TIME.name(), at(2026, Calendar.NOVEMBER, 2, 11, 0),
                new Location("A bit farther", 34.05, -118.2851), NEAR_LAX, 1, 3, 20);

        List<String> byProximity = ids(manager.sortByProximity(Arrays.asList(nearButLate, fartherButSoon)));
        List<String> combined = ids(manager.sortByProximityAndTime(Arrays.asList(nearButLate, fartherButSoon)));

        assertEquals("near-late", byProximity.get(0));
        assertEquals("far-soon", combined.get(0));
    }

    @Test
    public void sortByCostThenProximity() {
        Date sameTime = at(2026, Calendar.NOVEMBER, 2, 12, 0);
        Ride cheapFar = ride("cheap-far", "other", Status.ON_TIME.name(), sameTime, new Location("Far", 34.10, -118.50), LAX, 1, 3, 10);
        Ride priceyNear = ride("pricey-near", "other", Status.ON_TIME.name(), sameTime, NEAR_USC, NEAR_LAX, 1, 3, 40);
        Ride cheapNear = ride("cheap-near", "other", Status.ON_TIME.name(), sameTime, NEAR_USC, NEAR_LAX, 1, 3, 10);

        List<String> order = ids(manager.sortByCost(Arrays.asList(priceyNear, cheapFar, cheapNear)));

        assertEquals(Arrays.asList("cheap-near", "cheap-far", "pricey-near"), order);
    }

    private static List<String> ids(List<Ride> rides) {
        List<String> ids = new ArrayList<>();
        for (Ride ride : rides) {
            ids.add(ride.getRideID());
        }
        return ids;
    }

    private static Ride ride(String id, String organizerId, String status, Date departure,
                             Location origin, Location destination, int riders, int maxRiders, int cost) {
        User organizer = new User(organizerId, organizerId + "@usc.edu", "", "Organizer " + organizerId,
                organizerId, 2026, 1234567890);
        List<String> riderIds = new ArrayList<>();
        riderIds.add(organizerId);
        for (int i = 1; i < riders; i++) {
            riderIds.add("rider-" + i);
        }
        return new Ride(id, organizer, "Ride " + id, origin, destination, departure, maxRiders, cost, "", riderIds, status);
    }

    private static Date at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }
}

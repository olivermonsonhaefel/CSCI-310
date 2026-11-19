package com.example.trojan_rideshare;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * White-box tests for search filtering and sorting.
 * Each test drives a branch the search code takes on null, boundary, or tie input.
 */
public class SearchProfileWhiteBoxTest {

    private static final Location USC = new Location("USC", 34.0224, -118.2851);
    private static final Location LAX = new Location("LAX", 33.9416, -118.4085);
    private static final Location NEAR_USC = new Location("Near USC", 34.0274, -118.2851);

    private RideSearchManager manager;

    @Before
    public void setUp() {
        manager = new RideSearchManager(null, null);
        manager.setCurrentUserId("me");
        manager.setSearchContext(USC, LAX, at(2026, Calendar.NOVEMBER, 2, 11, 0));
    }

    @Test
    public void timeWindowKeepsBothEndsAndSkipsAMissingDeparture() {
        Date start = at(2026, Calendar.NOVEMBER, 2, 11, 0);
        Date end = at(2026, Calendar.NOVEMBER, 2, 13, 0);
        Ride atStart = ride("start", "other", start, USC, LAX, 20);
        Ride atEnd = ride("end", "other", end, USC, LAX, 20);
        Ride after = ride("after", "other", at(2026, Calendar.NOVEMBER, 2, 13, 1), USC, LAX, 20);
        Ride noTime = ride("none", "other", null, USC, LAX, 20);

        List<String> kept = ids(manager.filterByTimeRange(Arrays.asList(atStart, atEnd, after, noTime), start, end));

        assertEquals(Arrays.asList("start", "end"), kept);
        assertTrue(manager.filterByTimeRange(null, start, end).isEmpty());
        assertTrue(manager.filterByTimeRange(Collections.singletonList(atStart), null, end).isEmpty());
    }

    @Test
    public void radiusBoundaryKeepsZeroDistanceAndRejectsANegativeRadius() {
        Ride here = ride("here", "other", at(2026, Calendar.NOVEMBER, 2, 12, 0), USC, LAX, 20);
        Ride elsewhere = ride("away", "other", at(2026, Calendar.NOVEMBER, 2, 12, 0), NEAR_USC, LAX, 20);
        Ride noOrigin = ride("blank", "other", at(2026, Calendar.NOVEMBER, 2, 12, 0), null, LAX, 20);

        assertEquals(Arrays.asList("here"), ids(manager.filterByOrigin(Arrays.asList(here, elsewhere, noOrigin), USC, 0)));
        assertTrue(manager.filterByOrigin(Collections.singletonList(here), USC, -0.1).isEmpty());
        assertTrue(manager.filterByDestination(Collections.singletonList(here), null, 1).isEmpty());
    }

    @Test
    public void participantFiltersCoverAMissingUserAndARiderId() {
        Ride mine = ride("mine", "me", at(2026, Calendar.NOVEMBER, 2, 12, 0), USC, LAX, 20, Arrays.asList("me", "alex"));
        Ride theirs = ride("theirs", "other", at(2026, Calendar.NOVEMBER, 2, 12, 0), USC, LAX, 20, Collections.singletonList("other"));

        manager.setCurrentUserId(null);
        assertEquals(Arrays.asList("mine", "theirs"), ids(manager.filterByParticipants(Arrays.asList(mine, theirs))));

        manager.setCurrentUserId("me");
        assertEquals(Collections.singletonList("mine"), ids(manager.filterByParticipants(Arrays.asList(mine, theirs), "alex")));
        assertTrue(manager.filterByParticipants(Arrays.asList(mine, theirs), null).isEmpty());
        assertTrue(manager.filterByParticipants(null, "alex").isEmpty());
    }

    @Test
    public void latestAndCostSortsBreakTies() {
        Date noon = at(2026, Calendar.NOVEMBER, 2, 12, 0);
        Ride near = ride("near", "other", noon, NEAR_USC, LAX, 30);
        Ride far = ride("far", "other", noon, new Location("Far", 34.10, -118.50), LAX, 10);
        assertEquals(Arrays.asList("near", "far"), ids(manager.sortByLatest(Arrays.asList(far, near))));

        Ride earlier = ride("earlier", "other", at(2026, Calendar.NOVEMBER, 2, 11, 15), USC, LAX, 20);
        Ride later = ride("later", "other", at(2026, Calendar.NOVEMBER, 2, 12, 45), USC, LAX, 20);
        assertEquals(Arrays.asList("earlier", "later"), ids(manager.sortByCost(Arrays.asList(later, earlier))));
    }

    @Test
    public void combinedSortAddsHoursAndPutsAMissingDepartureLast() {
        Ride sooner = ride("sooner", "other", at(2026, Calendar.NOVEMBER, 2, 11, 0), USC, LAX, 20);
        Ride later = ride("later", "other", at(2026, Calendar.NOVEMBER, 2, 13, 0), USC, LAX, 20);
        Ride noTime = ride("none", "other", null, USC, LAX, 20);

        assertEquals(Arrays.asList("sooner", "none", "later"),
                ids(manager.sortByProximityAndTime(Arrays.asList(later, noTime, sooner))));
        assertTrue(manager.sortByProximityAndTime(null).isEmpty());
    }

    private static List<String> ids(List<Ride> rides) {
        List<String> ids = new ArrayList<>();
        for (Ride ride : rides) {
            ids.add(ride.getRideID());
        }
        return ids;
    }

    private static Ride ride(String id, String organizerId, Date departure, Location origin, Location destination, int cost) {
        return ride(id, organizerId, departure, origin, destination, cost, Collections.singletonList(organizerId), Status.ON_TIME.name());
    }

    private static Ride ride(String id, String organizerId, Date departure, Location origin, Location destination,
                             int cost, List<String> riders) {
        return ride(id, organizerId, departure, origin, destination, cost, riders, Status.ON_TIME.name());
    }

    private static Ride ride(String id, String organizerId, Date departure, Location origin, Location destination,
                             int cost, List<String> riders, String status) {
        User organizer = new User(organizerId, organizerId + "@usc.edu", "", "Organizer " + organizerId,
                organizerId, 2026, 1234567890);
        return new Ride(id, organizer, "Ride " + id, origin, destination, departure, 4, cost, "", riders, status);
    }

    private static Date at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }
}

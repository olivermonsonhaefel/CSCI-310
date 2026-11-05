package com.example.trojan_rideshare;

import org.junit.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProfileManagerTest {

    @Test
    public void includesOrganizerAndRider() {
        User organizer = user("org");
        Ride ride = ride(organizer, List.of("org", "rider"));
        assertTrue(ProfileManager.includesUser(ride, "org"));
        assertTrue(ProfileManager.includesUser(ride, "rider"));
        assertFalse(ProfileManager.includesUser(ride, "someone-else"));
    }

    @Test
    public void splitsUpcomingCompletedAndCancelled() {
        assertEquals(Status.ON_TIME, ProfileManager.sectionFor(rideWithStatus(Status.ON_TIME)));
        assertEquals(Status.ON_TIME, ProfileManager.sectionFor(rideWithStatus(Status.DELAYED)));
        assertEquals(Status.COMPLETED, ProfileManager.sectionFor(rideWithStatus(Status.COMPLETED)));
        assertEquals(Status.CANCELLED, ProfileManager.sectionFor(rideWithStatus(Status.CANCELLED)));
    }

    @Test
    public void participantIdsIncludeOrganizerAndRidersOnce() {
        Ride ride = ride(user("org"), List.of("org", "rider", "rider", ""));
        assertEquals(List.of("org", "rider"), ProfileManager.participantIds(ride));
        assertTrue(ProfileManager.participantIds(null).isEmpty());
    }

    @Test
    public void ignoresMissingUser() {
        Ride ride = ride(user("org"), Collections.singletonList("org"));
        assertFalse(ProfileManager.includesUser(ride, null));
        assertFalse(ProfileManager.includesUser(ride, ""));
        assertFalse(ProfileManager.includesUser(null, "org"));
    }

    private static User user(String id) {
        return new User(id, id + "@usc.edu", "", "Name", "Nick", 2026, 1);
    }

    private static Ride ride(User organizer, List<String> riders) {
        return rideWithStatus(Status.ON_TIME, organizer, riders);
    }

    private static Ride rideWithStatus(Status status) {
        return rideWithStatus(status, user("org"), List.of("org"));
    }

    private static Ride rideWithStatus(Status status, User organizer, List<String> riders) {
        Location place = new Location("USC", 34.02, -118.28);
        return new Ride("ride", organizer, "Campus", place, place, new Date(), 4, 20, null,
                riders, status.name());
    }
}

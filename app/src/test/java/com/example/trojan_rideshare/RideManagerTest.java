package com.example.trojan_rideshare;

import org.junit.Test;

import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Status rules for managing a ride. No Firestore needed. */
public class RideManagerTest {

    private static final Location USC = new Location("USC", 34.0224, -118.2851);
    private static final Location LAX = new Location("LAX", 33.9416, -118.4085);
    private static final Date BEFORE = new Date(1000L);
    private static final Date DEPARTURE = new Date(2000L);
    private static final Date AFTER = new Date(3000L);

    @Test
    public void cannotCompleteBeforeDeparture() {
        assertNotNull(RideManager.checkStatusChange(ride(Status.ON_TIME), Status.COMPLETED, BEFORE));
    }

    @Test
    public void soloRideCanCompleteAfterDeparture() {
        assertNull(RideManager.checkStatusChange(ride(Status.ON_TIME), Status.COMPLETED, AFTER));
        assertNull(RideManager.checkStatusChange(ride(Status.DELAYED), Status.COMPLETED, AFTER));
    }

    @Test
    public void activeRideCanChangeStatusAroundDeparture() {
        assertNull(RideManager.checkStatusChange(ride(Status.ON_TIME), Status.DELAYED, BEFORE));
        assertNull(RideManager.checkStatusChange(ride(Status.DELAYED), Status.ON_TIME, AFTER));
        assertNull(RideManager.checkStatusChange(ride(Status.ON_TIME), Status.CANCELLED, AFTER));
    }

    @Test
    public void finishedRidesCannotChange() {
        for (Status finished : new Status[]{Status.COMPLETED, Status.CANCELLED}) {
            for (Status next : Status.values()) {
                assertNotNull(RideManager.checkStatusChange(ride(finished), next, AFTER));
            }
        }
    }

    @Test
    public void finishedRidesHaveNoCapacity() {
        assertEquals(CapacityStatus.N_A.name(), ride(Status.COMPLETED).getCapacityStatus());
        assertEquals(CapacityStatus.N_A.name(), ride(Status.CANCELLED).getCapacityStatus());
        assertEquals(CapacityStatus.OPEN.name(), ride(Status.ON_TIME).getCapacityStatus());
    }

    private static Ride ride(Status status) {
        User organizer = new User("org", "org@usc.edu", "", "Organizer", "org", 2027, 0);
        return new Ride("ride1", organizer, "To LAX", USC, LAX, DEPARTURE, 3, 40, "",
                Collections.singletonList("org"), status.name());
    }
}

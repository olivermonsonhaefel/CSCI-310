package com.example.trojan_rideshare;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Checks for sending and answering join requests (UCS-2.3.2, UCS-2.3.4). No Firestore needed. */
public class JoinRequestManagerTest {

    private static final Location USC = new Location("USC", 34.0224, -118.2851);
    private static final Location LAX = new Location("LAX", 33.9416, -118.4085);
    private static final Date NOW = at(2026, Calendar.NOVEMBER, 1, 9, 0);
    private static final Date LATER = at(2026, Calendar.NOVEMBER, 2, 11, 0);
    private static final Date EARLIER = at(2026, Calendar.OCTOBER, 31, 11, 0);

    // ---- requestJoin checks ----

    @Test
    public void openRideWithNoEarlierRequestIsAllowed() {
        Ride ride = ride(Status.ON_TIME, LATER, 3, "org");
        assertNull(JoinRequestManager.checkCanRequest(ride, "me", null, "", NOW));
    }

    @Test
    public void delayedRideCanStillBeRequested() {
        Ride ride = ride(Status.DELAYED, LATER, 3, "org");
        assertNull(JoinRequestManager.checkCanRequest(ride, "me", null, "See you there", NOW));
    }

    @Test
    public void organizerCannotJoinOwnRide() {
        Ride ride = ride(Status.ON_TIME, LATER, 3, "org");
        assertEquals(JoinRequestManager.MSG_OWN_RIDE,
                JoinRequestManager.checkCanRequest(ride, "org", null, "", NOW));
    }

    @Test
    public void fullRideIsRejected() {
        Ride ride = ride(Status.ON_TIME, LATER, 2, "org", "other");
        assertEquals(JoinRequestManager.MSG_RIDE_FULL,
                JoinRequestManager.checkCanRequest(ride, "me", null, "", NOW));
    }

    @Test
    public void cancelledRideIsUnavailable() {
        Ride ride = ride(Status.CANCELLED, LATER, 3, "org");
        assertEquals(JoinRequestManager.MSG_RIDE_UNAVAILABLE,
                JoinRequestManager.checkCanRequest(ride, "me", null, "", NOW));
    }

    @Test
    public void completedRideIsUnavailable() {
        Ride ride = ride(Status.COMPLETED, LATER, 3, "org");
        assertEquals(JoinRequestManager.MSG_RIDE_UNAVAILABLE,
                JoinRequestManager.checkCanRequest(ride, "me", null, "", NOW));
    }

    @Test
    public void departedRideIsRejected() {
        Ride ride = ride(Status.ON_TIME, EARLIER, 3, "org");
        assertEquals(JoinRequestManager.MSG_RIDE_DEPARTED,
                JoinRequestManager.checkCanRequest(ride, "me", null, "", NOW));
    }

    @Test
    public void riderAlreadyOnTheRideIsRejected() {
        Ride ride = ride(Status.ON_TIME, LATER, 3, "org", "me");
        assertEquals(JoinRequestManager.MSG_ALREADY_JOINED,
                JoinRequestManager.checkCanRequest(ride, "me", RequestStatus.ACCEPTED.name(), "", NOW));
    }

    @Test
    public void pendingRequestBlocksASecondOne() {
        Ride ride = ride(Status.ON_TIME, LATER, 3, "org");
        assertEquals(JoinRequestManager.MSG_PENDING_EXISTS,
                JoinRequestManager.checkCanRequest(ride, "me", RequestStatus.PENDING.name(), "", NOW));
    }

    @Test
    public void rejectedRequestBlocksAskingAgain() {
        Ride ride = ride(Status.ON_TIME, LATER, 3, "org");
        assertEquals(JoinRequestManager.MSG_PREVIOUSLY_REJECTED,
                JoinRequestManager.checkCanRequest(ride, "me", RequestStatus.REJECTED.name(), "", NOW));
    }

    @Test
    public void riderWhoLeftAfterBeingAcceptedCanAskAgain() {
        Ride ride = ride(Status.ON_TIME, LATER, 3, "org");
        assertNull(JoinRequestManager.checkCanRequest(ride, "me", RequestStatus.ACCEPTED.name(), "", NOW));
    }

    // ---- note length (UCS-2.3.4) ----

    @Test
    public void noteOfExactly250CharactersIsAllowed() {
        assertNull(JoinRequestManager.checkMessage(repeat(250)));
    }

    @Test
    public void noteOver250CharactersShowsTheCount() {
        assertEquals("Reduce character count (251/250).", JoinRequestManager.checkMessage(repeat(251)));
    }

    @Test
    public void emptyOrMissingNoteIsAllowed() {
        assertNull(JoinRequestManager.checkMessage(""));
        assertNull(JoinRequestManager.checkMessage(null));
    }

    // ---- accepting ----

    @Test
    public void organizerCanAcceptPendingRequest() {
        Ride ride = ride(Status.ON_TIME, LATER, 3, "org");
        assertNull(JoinRequestManager.checkCanAccept(ride, "org", RequestStatus.PENDING.name(), "me"));
    }

    @Test
    public void onlyTheOrganizerCanAccept() {
        Ride ride = ride(Status.ON_TIME, LATER, 3, "org", "other");
        assertEquals(JoinRequestManager.MSG_NOT_ORGANIZER,
                JoinRequestManager.checkCanAccept(ride, "other", RequestStatus.PENDING.name(), "me"));
    }

    @Test
    public void answeredRequestCannotBeAcceptedAgain() {
        Ride ride = ride(Status.ON_TIME, LATER, 3, "org");
        assertEquals(JoinRequestManager.MSG_NOT_PENDING,
                JoinRequestManager.checkCanAccept(ride, "org", RequestStatus.REJECTED.name(), "me"));
    }

    @Test
    public void cannotAcceptIntoFullRide() {
        Ride ride = ride(Status.ON_TIME, LATER, 2, "org", "other");
        assertEquals(JoinRequestManager.MSG_RIDE_FULL,
                JoinRequestManager.checkCanAccept(ride, "org", RequestStatus.PENDING.name(), "me"));
    }

    @Test
    public void cannotAcceptIntoCancelledRide() {
        Ride ride = ride(Status.CANCELLED, LATER, 3, "org");
        assertEquals(JoinRequestManager.MSG_RIDE_UNAVAILABLE,
                JoinRequestManager.checkCanAccept(ride, "org", RequestStatus.PENDING.name(), "me"));
    }

    @Test
    public void acceptingTheLastSeatMakesTheRideFull() {
        Ride ride = ride(Status.ON_TIME, LATER, 2, "org");
        List<String> riders = new ArrayList<>(ride.getRiders());
        riders.add("me");
        Ride updated = new Ride(ride.getRideID(), ride.getOrganizer(), ride.getTitle(), USC, LAX,
                LATER, 2, 40, "", riders, Status.ON_TIME.name());
        assertEquals(CapacityStatus.FULL.name(), updated.getCapacityStatus());
    }

    // ---- lists ----

    @Test
    public void requestsAreSortedNewestFirst() {
        JoinRequest older = request("older", EARLIER);
        JoinRequest newer = request("newer", LATER);
        JoinRequest middle = request("middle", NOW);
        List<JoinRequest> requests = new ArrayList<>(Arrays.asList(older, newer, middle));

        JoinRequestManager.sortNewestFirst(requests);

        assertEquals("newer", requests.get(0).getId());
        assertEquals("middle", requests.get(1).getId());
        assertEquals("older", requests.get(2).getId());
    }

    @Test
    public void requesterNameFallsBackToFullName() {
        User noNickname = new User("u1", "a@usc.edu", "", "Tommy Trojan", "", 2027, 0);
        JoinRequest request = new JoinRequest("r", NOW, "", RequestStatus.PENDING.name(),
                ride(Status.ON_TIME, LATER, 3, "org"), noNickname);
        assertEquals("Tommy Trojan", request.getRequesterName());
    }

    // ---- helpers ----

    private static Ride ride(Status status, Date departure, int maxRiders, String organizerId, String... otherRiders) {
        User organizer = new User(organizerId, organizerId + "@usc.edu", "", "Organizer " + organizerId,
                organizerId, 2027, 0);
        List<String> riders = new ArrayList<>(Collections.singletonList(organizerId));
        riders.addAll(Arrays.asList(otherRiders));
        return new Ride("ride1", organizer, "To LAX", USC, LAX, departure, maxRiders, 40, "", riders, status.name());
    }

    private static JoinRequest request(String id, Date when) {
        User requester = new User("me", "me@usc.edu", "", "Me", "me", 2027, 0);
        return new JoinRequest(id, when, "", RequestStatus.PENDING.name(),
                ride(Status.ON_TIME, LATER, 3, "org"), requester);
    }

    private static String repeat(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append('a');
        }
        return text.toString();
    }

    private static Date at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month, day, hour, minute);
        return calendar.getTime();
    }
}
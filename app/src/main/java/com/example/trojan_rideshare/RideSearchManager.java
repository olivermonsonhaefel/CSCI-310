package com.example.trojan_rideshare;

import android.util.Log;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Search, filter, and sort (spec section 2.2, design class RideSearchManager).
 * Filter and sort methods match the class diagram and run on a list already loaded from Firestore.
 * Firestore cannot query by straight-line radius, so {@link #getRidesByRadius} loads future rides
 * and the filter methods apply radius, time, status, and availability.
 */
public class RideSearchManager {
    private static final String TAG = "RideSearchManager";
    private static final String RIDES = "rides";
    private static final long MILLIS_PER_HOUR = 60L * 60L * 1000L;

    private final FirebaseFirestore db;
    private final AuthManager authManager;

    // Sort methods in the diagram only receive the ride list, so the search targets live here.
    private Location desiredOrigin;
    private Location desiredDestination;
    private Date windowStart;
    private String currentUserId;

    public RideSearchManager() {
        this(FirebaseFirestore.getInstance(), AuthManager.getInstance());
    }

    RideSearchManager(FirebaseFirestore db, AuthManager authManager) {
        this.db = db;
        this.authManager = authManager;
    }

    /**
     * Remembers the places and time window the user searched for.
     * Proximity sorts and the combined sort read this state.
     */
    public void setSearchContext(Location origin, Location destination, Date windowStart) {
        this.desiredOrigin = origin;
        this.desiredDestination = destination;
        this.windowStart = windowStart;
    }

    /** Used when a test or caller already knows the logged-in user id. */
    void setCurrentUserId(String currentUserId) {
        this.currentUserId = currentUserId;
    }

    /**
     * Loads rides that have not departed yet.
     * The diagram signature uses int coordinates. Location stores doubles, so these are doubles.
     * Radius is not applied here. {@link #filterByOrigin} and {@link #filterByDestination} do that.
     * Passes null when the read fails.
     */
    public void getRidesByRadius(double orgLat, double orgLon, double destLat, double destLon,
                                 Consumer<List<Ride>> callback) {
        if (desiredOrigin == null) {
            desiredOrigin = new Location("origin", orgLat, orgLon);
        }
        if (desiredDestination == null) {
            desiredDestination = new Location("destination", destLat, destLon);
        }
        db.collection(RIDES)
                .whereGreaterThan("departureTime", new Date())
                .get()
                .addOnSuccessListener(snapshot -> {
                    List<Ride> rides = new ArrayList<>();
                    for (DocumentSnapshot document : snapshot.getDocuments()) {
                        try {
                            Ride ride = RideManager.toRide(document);
                            if (ride.getDepartureTime() != null && ride.getDepartureTime().after(new Date())) {
                                rides.add(ride);
                            }
                        } catch (RuntimeException | FirebaseFirestoreException e) {
                            Log.e(TAG, "Skipping unreadable ride " + document.getId(), e);
                        }
                    }
                    callback.accept(rides);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getRidesByRadius failed", e);
                    callback.accept(null);
                });
    }

    /** Reloads one ride so the detail screen can show a status that changed after search (UCS-2.2.13). */
    public void loadRide(String rideId, Consumer<Ride> callback) {
        db.collection(RIDES).document(rideId).get()
                .addOnSuccessListener(snapshot -> {
                    try {
                        callback.accept(RideManager.toRide(snapshot));
                    } catch (RuntimeException | FirebaseFirestoreException e) {
                        Log.e(TAG, "loadRide failed", e);
                        callback.accept(null);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "loadRide failed", e);
                    callback.accept(null);
                });
    }

    /** UCS-2.2.6: the ride is still open. */
    public List<Ride> filterByAvailable(List<Ride> rides) {
        List<Ride> kept = new ArrayList<>();
        if (rides == null) {
            return kept;
        }
        for (Ride ride : rides) {
            if (CapacityStatus.OPEN.name().equals(ride.getCapacityStatus())) {
                kept.add(ride);
            }
        }
        return kept;
    }

    /** Diagram method. Search keeps a ride when its status is the one asked for. */
    public List<Ride> filterByStatus(List<Ride> rides, Status status) {
        List<Ride> kept = new ArrayList<>();
        if (rides == null || status == null) {
            return kept;
        }
        for (Ride ride : rides) {
            if (status.name().equals(ride.getRideStatus())) {
                kept.add(ride);
            }
        }
        return kept;
    }

    /**
     * UCS-2.2.6: the signed-in user is not the organizer.
     * The diagram method takes only the ride list, so the user comes from AuthManager.
     */
    public List<Ride> filterByParticipants(List<Ride> rides) {
        String userId = currentUserId;
        if (userId == null && authManager != null && authManager.getCurrentUser() != null) {
            userId = authManager.getCurrentUser().getId();
        }
        List<Ride> kept = new ArrayList<>();
        if (rides == null) {
            return kept;
        }
        for (Ride ride : rides) {
            if (userId == null || ride.getOrganizer() == null || !userId.equals(ride.getOrganizer().getId())) {
                kept.add(ride);
            }
        }
        return kept;
    }

    public List<Ride> filterByParticipants(List<Ride> rides, String participantId) {
        List<Ride> kept = new ArrayList<>();
        if (rides == null || participantId == null) {
            return kept;
        }
        for (Ride ride : rides) {
            if (ride.getRiders().contains(participantId)) {
                kept.add(ride);
            }
        }
        return kept;
    }

    /** Keeps rides whose departure falls on the given calendar day. */
    public List<Ride> filterByDate(List<Ride> rides, Date day) {
        List<Ride> kept = new ArrayList<>();
        if (rides == null || day == null) {
            return kept;
        }
        Calendar wanted = Calendar.getInstance();
        wanted.setTime(day);
        for (Ride ride : rides) {
            Date departure = effectiveDeparture(ride);
            if (departure == null) {
                continue;
            }
            Calendar rideDay = Calendar.getInstance();
            rideDay.setTime(departure);
            if (rideDay.get(Calendar.YEAR) == wanted.get(Calendar.YEAR)
                    && rideDay.get(Calendar.DAY_OF_YEAR) == wanted.get(Calendar.DAY_OF_YEAR)) {
                kept.add(ride);
            }
        }
        return kept;
    }

    /**
     * Keeps rides whose effective departure is inside the window, including both ends.
     * The diagram labels the arguments "UTF start" and "UTF end"; those are the window instants.
     */
    public List<Ride> filterByTimeRange(List<Ride> rides, Date start, Date end) {
        List<Ride> kept = new ArrayList<>();
        if (rides == null || start == null || end == null) {
            return kept;
        }
        long startMillis = start.getTime();
        long endMillis = end.getTime();
        for (Ride ride : rides) {
            Date departure = effectiveDeparture(ride);
            if (departure == null) {
                continue;
            }
            long time = departure.getTime();
            if (time >= startMillis && time <= endMillis) {
                kept.add(ride);
            }
        }
        return kept;
    }

    /**
     * UCS-2.2.6: ride origin is within radius miles of the desired origin.
     * The diagram takes a POI string and an int radius. Distance needs coordinates, and a radius
     * can be fractional, so this takes the Location Mapbox already resolved and a double radius.
     */
    public List<Ride> filterByOrigin(List<Ride> rides, Location desired, double radiusMiles) {
        return filterByPlace(rides, desired, radiusMiles, true);
    }

    /**
     * UCS-2.2.6 also requires the destination to fall within the same radius.
     * The class diagram has no destination filter, so this method was added.
     */
    public List<Ride> filterByDestination(List<Ride> rides, Location desired, double radiusMiles) {
        return filterByPlace(rides, desired, radiusMiles, false);
    }

    /** UCS-2.2.9 earliest to latest. UCS-2.2.11 breaks ties with proximity. */
    public List<Ride> sortByEarliest(List<Ride> rides) {
        return sorted(rides, Comparator.comparing(this::effectiveDeparture, Comparator.nullsLast(Date::compareTo))
                .thenComparingDouble(this::proximity));
    }

    /** UCS-2.2.9 latest to earliest. Ties break with proximity, closer first. */
    public List<Ride> sortByLatest(List<Ride> rides) {
        return sorted(rides, Comparator.comparing(this::effectiveDeparture, Comparator.nullsLast(Date::compareTo))
                .reversed()
                .thenComparingDouble(this::proximity));
    }

    /** UCS-2.2.9 and UCS-2.2.10. Ties break with earlier departure. */
    public List<Ride> sortByProximity(List<Ride> rides) {
        return sorted(rides, Comparator.comparingDouble(this::proximity)
                .thenComparing(this::effectiveDeparture, Comparator.nullsLast(Date::compareTo)));
    }

    /**
     * UCS-2.2.9 "proximity and time combined".
     * Score = proximity in miles + hours after the search window start. Lower score ranks first.
     * A farther ride that leaves much sooner can rank ahead of a nearer ride that leaves later.
     * Ties break with earlier departure.
     */
    public List<Ride> sortByProximityAndTime(List<Ride> rides) {
        return sorted(rides, Comparator.comparingDouble(this::combinedScore)
                .thenComparing(this::effectiveDeparture, Comparator.nullsLast(Date::compareTo)));
    }

    /** Class diagram and search sequence. Not listed in UCS-2.2.9. Lower expected cost first. */
    public List<Ride> sortByCost(List<Ride> rides) {
        return sorted(rides, Comparator.comparingInt(Ride::getTotalExpectedCost)
                .thenComparingDouble(this::proximity)
                .thenComparing(this::effectiveDeparture, Comparator.nullsLast(Date::compareTo)));
    }

    /**
     * UCS-2.2.7 says a delayed ride uses its delayed departure time.
     * Ride does not store a separate delayed time, so the posted departure time is used.
     */
    private Date effectiveDeparture(Ride ride) {
        return ride.getDepartureTime();
    }

    /** UCS-2.2.10: origin gap plus destination gap, in miles. */
    private double proximity(Ride ride) {
        double total = 0;
        if (desiredOrigin != null && ride.getOrigin() != null) {
            total += desiredOrigin.distanceFromLocation(ride.getOrigin());
        }
        if (desiredDestination != null && ride.getDestination() != null) {
            total += desiredDestination.distanceFromLocation(ride.getDestination());
        }
        return total;
    }

    private double combinedScore(Ride ride) {
        double hours = 0;
        Date departure = effectiveDeparture(ride);
        if (windowStart != null && departure != null) {
            hours = (departure.getTime() - windowStart.getTime()) / (double) MILLIS_PER_HOUR;
        }
        return proximity(ride) + hours;
    }

    private List<Ride> filterByPlace(List<Ride> rides, Location desired, double radiusMiles, boolean origin) {
        List<Ride> kept = new ArrayList<>();
        if (rides == null || desired == null || radiusMiles < 0) {
            return kept;
        }
        for (Ride ride : rides) {
            Location place = origin ? ride.getOrigin() : ride.getDestination();
            if (place != null && desired.distanceFromLocation(place) <= radiusMiles) {
                kept.add(ride);
            }
        }
        return kept;
    }

    private List<Ride> sorted(List<Ride> rides, Comparator<Ride> comparator) {
        List<Ride> copy = new ArrayList<>();
        if (rides != null) {
            copy.addAll(rides);
        }
        copy.sort(comparator);
        return copy;
    }
}

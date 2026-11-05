package com.example.trojan_rideshare;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressWarnings({"unused", "RedundantSuppression"})
public class Ride {
    private final String rideID;
    private final User organizer;
    private final Date departureTime;
    private final String rideStatus; // a Status name, e.g. "ON_TIME"
    private final String title;
    private final int numOfRiders;
    private final int maxRiders; // includes the organizer
    private final String note;
    private final String capacityStatus; // a CapacityStatus name, e.g. "OPEN"
    private final double totalExpectedCost; // US dollars, up to 2 decimals
    private final List<String> riders; // user ids in the order they joined, organizer included
    private final Location origin;
    private final Location destination;

    public Ride(String rideID, User organizer, String title, Location origin, Location destination,
                Date departureTime, int maxRiders, double totalExpectedCost, String note,
                List<String> riders, String rideStatus) {
        this.rideID = rideID;
        this.organizer = organizer;
        this.title = title;
        this.origin = origin;
        this.destination = destination;
        this.departureTime = departureTime;
        this.maxRiders = maxRiders;
        this.totalExpectedCost = totalExpectedCost;
        this.note = note;
        this.riders = new ArrayList<>(riders);
        this.rideStatus = rideStatus;
        this.numOfRiders = this.riders.size();
        this.capacityStatus = computeCapacityStatus();
    }

    // N/A once Completed or Cancelled, Full when the riders reach the maximum, otherwise Open
    private String computeCapacityStatus() {
        if (Status.COMPLETED.name().equals(rideStatus) || Status.CANCELLED.name().equals(rideStatus)) {
            return CapacityStatus.N_A.name();
        }
        return numOfRiders >= maxRiders ? CapacityStatus.FULL.name() : CapacityStatus.OPEN.name();
    }

    public int getNumOfRiders() { return numOfRiders; }
    public Date getDepartureTime() { return departureTime; }
    public String getRideStatus() { return rideStatus; }
    public List<String> getRiders() { return Collections.unmodifiableList(riders); }
    public String getCapacityStatus() { return capacityStatus; }
    public User getOrganizer() { return organizer; }

    public String getDisplayInfo() {
        String organizerName = organizer.getNickname() != null && !organizer.getNickname().isEmpty()
                ? organizer.getNickname() : organizer.getFullName();
        String departure = new SimpleDateFormat("EEE, MMM d, yyyy · h:mm a", Locale.US).format(departureTime);
        String costPerPerson = String.format(Locale.US, "%.2f", totalExpectedCost / maxRiders);
        StringBuilder info = new StringBuilder()
                .append(title)
                .append("\nOrganizer: ").append(organizerName)
                .append("\nFrom: ").append(origin.getPOIName())
                .append("\nTo: ").append(destination.getPOIName())
                .append("\nDeparts: ").append(departure)
                .append("\nStatus: ").append(Status.valueOf(rideStatus))
                .append("\nCapacity: ").append(CapacityStatus.valueOf(capacityStatus))
                .append(" (").append(numOfRiders).append("/").append(maxRiders).append(" riders)")
                .append("\nExpected cost: $").append(String.format(Locale.US, "%.2f", totalExpectedCost))
                .append(" total, $").append(costPerPerson).append(" per person");
        if (note != null && !note.isEmpty()) {
            info.append("\nNote: ").append(note);
        }
        return info.toString();
    }

    // Getters below are not in the class diagram; screens and teammates need them to read the fields
    public String getRideID() { return rideID; }
    public String getTitle() { return title; }
    public int getMaxRiders() { return maxRiders; }
    public String getNote() { return note; }
    public double getTotalExpectedCost() { return totalExpectedCost; }
    public Location getOrigin() { return origin; }
    public Location getDestination() { return destination; }
}

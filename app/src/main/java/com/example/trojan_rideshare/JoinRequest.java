package com.example.trojan_rideshare;

import java.util.Date;

/** A request to join a ride. Fields and methods match the JoinRequest class in the class diagram. */
@SuppressWarnings({"unused", "RedundantSuppression"})
public class JoinRequest {
    private final String id;
    private final Date dateRequested;
    private final String message;
    private final RequestStatus status;
    private final Ride ride;
    private final User requester;

    public JoinRequest(String id, Date dateRequested, String message, RequestStatus status, Ride ride, User requester) {
        this.id = id;
        this.dateRequested = dateRequested;
        this.message = message;
        this.status = status;
        this.ride = ride;
        this.requester = requester;
    }

    public String getStatus() { return status.name(); }
    public String getMessage() { return message; }

    // Getters below are not in the class diagram; screens and JoinRequestManager need them to read the fields
    public String getId() { return id; }
    public Date getDateRequested() { return dateRequested; }
    public Ride getRide() { return ride; }
    public User getRequester() { return requester; }
}
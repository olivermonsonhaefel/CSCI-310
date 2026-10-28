package com.example.trojan_rideshare;

import java.util.Date;
import java.util.List;
@SuppressWarnings({"unused", "RedundantSuppression"})
public class JoinRequest {
    private final String id;
    private final Date dateRequested;
    private String message;
    private String status;
    private final Ride ride;
    private final User requester;

    public JoinRequest(String id, Date dateRequested,String message, String status, Ride ride, User requester) {
        this.id = id;
        this.dateRequested = dateRequested;
        this.message = message;
        this.status = status;
        this.ride = ride;
        this.requester = requester;
    }

    //Getter methods
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public String getId() { return id; }
    public Date getDateRequested() { return dateRequested; }
    public Ride getRide() { return ride; }
    public User getRequester() { return requester; }

    public boolean isPending() {
        return RequestStatus.PENDING.name().equals(status);
    }
    /** Name shown in request lists: the nickname, or the full name when there is no nickname. */
    public String getRequesterName() {
        if (requester == null) {
            return "";
        }
        String nickname = requester.getNickname();
        return nickname != null && !nickname.isEmpty() ? nickname : requester.getFullName();
    }

}

package com.example.trojan_rideshare;

import androidx.annotation.NonNull;

// Matches the RequestStatus enum in the class diagram.
// A cancelled request is deleted instead of getting its own status
// (spec Feature 3: "cancel a pending request").
public enum RequestStatus {
    PENDING("Pending"),
    ACCEPTED("Accepted"),
    REJECTED("Rejected");

    private final String label;

    RequestStatus(String label) {
        this.label = label;
    }
    @Override
    public String toString() {
        return label;
    }
}

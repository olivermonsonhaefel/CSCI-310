package com.example.trojan_rideshare;

// ON_TIME is not in the class diagram; added because the customer spec makes it the default status.
public enum Status {
    ON_TIME("On-Time"),
    DELAYED("Delayed"),
    CANCELLED("Cancelled"),
    COMPLETED("Completed");

    private final String label;

    Status(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

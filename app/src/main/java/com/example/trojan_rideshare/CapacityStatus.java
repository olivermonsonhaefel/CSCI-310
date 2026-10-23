package com.example.trojan_rideshare;

public enum CapacityStatus {
    OPEN("Open"),
    FULL("Full"),
    N_A("N/A"); // "N/A" in the class diagram; Java names can't contain "/"

    private final String label;

    CapacityStatus(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

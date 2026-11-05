package com.example.trojan_rideshare;

@SuppressWarnings({"unused", "RedundantSuppression"})
public class Location {
    private static final double EARTH_RADIUS_MILES = 3958.8;

    private final String POIName;
    private final double lat;
    private final double lon;

    public Location(String POIName, double lat, double lon) {
        this.POIName = POIName;
        this.lat = lat;
        this.lon = lon;
    }

    public String getPOIName() { return POIName; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }

    /** Straight-line (haversine) distance in miles. */
    public double distanceFromLocation(Location l) {
        double dLat = Math.toRadians(l.lat - lat);
        double dLon = Math.toRadians(l.lon - lon);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(l.lat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_MILES * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

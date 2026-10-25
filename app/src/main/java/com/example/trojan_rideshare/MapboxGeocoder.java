package com.example.trojan_rideshare;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.function.Consumer;

/**
 * Shared Mapbox lookup used by ride creation (requirement 1.2) and ride search (requirement 1.3).
 * The design doc treats Mapbox as one application-layer component for both features.
 */
public class MapboxGeocoder {
    private static final String TAG = "MapboxGeocoder";
    public static final String LOCATION_UNAVAILABLE = "Location service provider not available. Try again later.";
    // Results are biased toward USC, matching ride creation.
    private static final double USC_LAT = 34.0224;
    private static final double USC_LON = -118.2851;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Turns a POI keyword such as "LAX" into a Location.
     * onFound receives null when no place matches.
     * onError receives a message when Mapbox cannot be reached.
     * Both callbacks run on the main thread.
     */
    public void lookUp(String keyword, Consumer<Location> onFound, Consumer<String> onError) {
        new Thread(() -> {
            try {
                String query = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20");
                URL url = new URL("https://api.mapbox.com/search/searchbox/v1/forward?q=" + query
                        + "&limit=1&country=us&proximity=" + USC_LON + "," + USC_LAT
                        + "&access_token=" + BuildConfig.MAPBOX_TOKEN);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Mapbox returned HTTP " + connection.getResponseCode());
                    mainHandler.post(() -> onError.accept(LOCATION_UNAVAILABLE));
                    return;
                }

                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }

                JSONArray features = new JSONObject(body.toString()).getJSONArray("features");
                if (features.length() == 0) {
                    mainHandler.post(() -> onFound.accept(null));
                    return;
                }
                JSONArray coordinates = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
                Location location = new Location(keyword, coordinates.getDouble(1), coordinates.getDouble(0));
                mainHandler.post(() -> onFound.accept(location));
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Mapbox lookup failed", e);
                mainHandler.post(() -> onError.accept(LOCATION_UNAVAILABLE));
            }
        }).start();
    }
}

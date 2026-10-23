package com.example.trojan_rideshare;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

public class CreateRideActivity extends AppCompatActivity {

    private static final String TAG = "CreateRideActivity";
    private static final String LOCATION_UNAVAILABLE = "Location service provider not available. Try again later.";
    // Mapbox results are biased toward USC
    private static final double USC_LAT = 34.0224;
    private static final double USC_LON = -118.2851;

    private TextInputLayout tilRideTitle, tilOrigin, tilDestination, tilRideDate, tilDepartureTime,
            tilMaxRiders, tilExpectedCost;
    private TextInputEditText etRideTitle, etOrigin, etDestination, etRideDate, etDepartureTime,
            etMaxRiders, etExpectedCost, etNote;
    private MaterialButton btnPostRide;
    private ProgressBar progressBar;
    private RideManager rideManager;

    private final Calendar departure = Calendar.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_ride);

        rideManager = new RideManager();

        tilRideTitle = findViewById(R.id.tilRideTitle);
        tilOrigin = findViewById(R.id.tilOrigin);
        tilDestination = findViewById(R.id.tilDestination);
        tilRideDate = findViewById(R.id.tilRideDate);
        tilDepartureTime = findViewById(R.id.tilDepartureTime);
        tilMaxRiders = findViewById(R.id.tilMaxRiders);
        tilExpectedCost = findViewById(R.id.tilExpectedCost);

        etRideTitle = findViewById(R.id.etRideTitle);
        etOrigin = findViewById(R.id.etOrigin);
        etDestination = findViewById(R.id.etDestination);
        etRideDate = findViewById(R.id.etRideDate);
        etDepartureTime = findViewById(R.id.etDepartureTime);
        etMaxRiders = findViewById(R.id.etMaxRiders);
        etExpectedCost = findViewById(R.id.etExpectedCost);
        etNote = findViewById(R.id.etNote);
        btnPostRide = findViewById(R.id.btnPostRide);
        progressBar = findViewById(R.id.progressBar);
        TextView tvCancel = findViewById(R.id.tvCancel);

        // Default departure: tomorrow at 12:00 PM
        departure.add(Calendar.DAY_OF_YEAR, 1);
        departure.set(Calendar.HOUR_OF_DAY, 12);
        departure.set(Calendar.MINUTE, 0);
        departure.set(Calendar.SECOND, 0);
        departure.set(Calendar.MILLISECOND, 0);
        showDeparture();

        etRideDate.setOnClickListener(v -> pickDate());
        etDepartureTime.setOnClickListener(v -> pickTime());
        btnPostRide.setOnClickListener(v -> validateAndPost());
        tvCancel.setOnClickListener(v -> finish());
    }

    private void pickDate() {
        new DatePickerDialog(this, (view, year, month, day) -> {
            departure.set(year, month, day);
            showDeparture();
        }, departure.get(Calendar.YEAR), departure.get(Calendar.MONTH), departure.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime() {
        new TimePickerDialog(this, (view, hour, minute) -> {
            departure.set(Calendar.HOUR_OF_DAY, hour);
            departure.set(Calendar.MINUTE, minute);
            showDeparture();
        }, departure.get(Calendar.HOUR_OF_DAY), departure.get(Calendar.MINUTE), false).show();
    }

    private void showDeparture() {
        etRideDate.setText(new SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(departure.getTime()));
        etDepartureTime.setText(new SimpleDateFormat("h:mm a", Locale.US).format(departure.getTime()));
        tilRideDate.setError(null);
        tilDepartureTime.setError(null);
    }

    private void validateAndPost() {
        // Clear previous errors
        tilRideTitle.setError(null);
        tilOrigin.setError(null);
        tilDestination.setError(null);
        tilRideDate.setError(null);
        tilDepartureTime.setError(null);
        tilMaxRiders.setError(null);
        tilExpectedCost.setError(null);

        String title = etRideTitle.getText() != null ? etRideTitle.getText().toString().trim() : "";
        String origin = etOrigin.getText() != null ? etOrigin.getText().toString().trim() : "";
        String destination = etDestination.getText() != null ? etDestination.getText().toString().trim() : "";
        String maxRidersText = etMaxRiders.getText() != null ? etMaxRiders.getText().toString().trim() : "";
        String costText = etExpectedCost.getText() != null ? etExpectedCost.getText().toString().trim() : "";
        String note = etNote.getText() != null ? etNote.getText().toString().trim() : "";

        boolean isValid = true;

        if (title.isEmpty()) {
            tilRideTitle.setError("Please enter a title");
            isValid = false;
        }

        if (origin.isEmpty()) {
            tilOrigin.setError("Please enter a valid origin.");
            isValid = false;
        }

        if (destination.isEmpty()) {
            tilDestination.setError("Please enter a valid destination.");
            isValid = false;
        }

        if (!departure.getTime().after(new Date())) {
            tilDepartureTime.setError("Departure must be in the future");
            isValid = false;
        }

        int maxRiders = parseInt(maxRidersText);
        if (maxRiders < 2 || maxRiders > 6) {
            tilMaxRiders.setError("Riders must be between 2 and 6");
            isValid = false;
        }

        int expectedCost = parseInt(costText);
        if (expectedCost < 0) {
            tilExpectedCost.setError("Please enter an expected cost");
            isValid = false;
        }

        if (!isValid) {
            return;
        }

        setLoading(true);

        lookUpLocation(origin, originLocation -> {
            if (originLocation == null) {
                tilOrigin.setError("Please enter a valid origin.");
                setLoading(false);
                return;
            }
            lookUpLocation(destination, destinationLocation -> {
                if (destinationLocation == null) {
                    tilDestination.setError("Please enter a valid destination.");
                    setLoading(false);
                    return;
                }
                rideManager.createRide(title, originLocation, destinationLocation, departure.getTime(),
                        maxRiders, expectedCost, note.isEmpty() ? null : note, success -> {
                            setLoading(false);
                            if (success) {
                                Toast.makeText(this, "Ride posted", Toast.LENGTH_LONG).show();
                                finish();
                            } else {
                                Toast.makeText(this, "Could not post ride. Try again.", Toast.LENGTH_LONG).show();
                            }
                        });
            }, this::showLocationUnavailable);
        }, this::showLocationUnavailable);
    }

    /**
     * Turns a POI keyword like "LAX" into a Location using the Mapbox Geocoding API.
     * onFound receives null when no place matches; onError receives a message when Mapbox can't be reached.
     */
    private void lookUpLocation(String keyword, Consumer<Location> onFound, Consumer<String> onError) {
        new Thread(() -> {
            try {
                String query = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20");
                // Search Box API: unlike Mapbox's older geocoding endpoint, it finds POIs like "LAX"
                URL url = new URL("https://api.mapbox.com/search/searchbox/v1/forward?q=" + query
                        + "&limit=1&country=us&proximity=" + USC_LON + "," + USC_LAT
                        + "&access_token=" + BuildConfig.MAPBOX_TOKEN);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Mapbox returned HTTP " + connection.getResponseCode());
                    runOnUiThread(() -> onError.accept(LOCATION_UNAVAILABLE));
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
                    runOnUiThread(() -> onFound.accept(null));
                    return;
                }
                // Mapbox gives coordinates as [lon, lat]
                JSONArray coordinates = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
                Location location = new Location(keyword, coordinates.getDouble(1), coordinates.getDouble(0));
                runOnUiThread(() -> onFound.accept(location));
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Mapbox lookup failed", e);
                runOnUiThread(() -> onError.accept(LOCATION_UNAVAILABLE));
            }
        }).start();
    }

    private void showLocationUnavailable(String message) {
        tilOrigin.setError(message);
        tilDestination.setError(message);
        setLoading(false);
    }

    private void setLoading(boolean loading) {
        btnPostRide.setEnabled(!loading);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** Returns -1 when the text isn't a whole number. */
    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

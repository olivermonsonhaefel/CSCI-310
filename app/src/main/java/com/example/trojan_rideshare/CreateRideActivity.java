package com.example.trojan_rideshare;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

public class CreateRideActivity extends AppCompatActivity {

    public static final String EXTRA_RIDE_ID = "rideId";

    private TextInputLayout tilRideTitle, tilOrigin, tilDestination, tilRideDate, tilDepartureTime,
            tilMaxRiders, tilExpectedCost;
    private TextInputEditText etRideTitle, etOrigin, etDestination, etRideDate, etDepartureTime,
            etMaxRiders, etExpectedCost, etNote;
    private MaterialButton btnPostRide;
    private ProgressBar progressBar;
    private RideManager rideManager;
    private Ride editingRide;

    private final Calendar departure = Calendar.getInstance();
    private final MapboxGeocoder geocoder = new MapboxGeocoder();
    private Location pinnedOrigin;
    private Location pinnedDestination;
    private final ActivityResultLauncher<Intent> mapLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    applyMapSelection(result.getData());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenInsets.enable(this);
        setContentView(R.layout.activity_create_ride);
        ScreenInsets.pad(findViewById(R.id.createRideScroll));

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
        findViewById(R.id.btnSetOnMap).setOnClickListener(v ->
                mapLauncher.launch(new Intent(this, MapPinActivity.class)));
        forgetPinIfEdited(etOrigin, true);
        forgetPinIfEdited(etDestination, false);
        tvCancel.setOnClickListener(v -> finish());

        String rideId = getIntent().getStringExtra(EXTRA_RIDE_ID);
        if (rideId != null) {
            ((TextView) findViewById(R.id.tvTitle)).setText(R.string.edit_ride_title);
            ((TextView) findViewById(R.id.tvSubtitle)).setText(R.string.edit_ride_subtitle);
            btnPostRide.setText(R.string.action_save_changes);
            lockPostedFields();
            setLoading(true);
            new RideSearchManager().loadRide(rideId, this::fillFields);
        }
    }

    /** After posting, only the departure time and note can change. */
    private void lockPostedFields() {
        tilRideTitle.setEnabled(false);
        tilOrigin.setEnabled(false);
        tilDestination.setEnabled(false);
        tilRideDate.setEnabled(false);
        tilMaxRiders.setEnabled(false);
        tilExpectedCost.setEnabled(false);
        findViewById(R.id.btnSetOnMap).setVisibility(View.GONE);
    }

    private void fillFields(Ride ride) {
        setLoading(false);
        if (ride == null) {
            Toast.makeText(this, R.string.error_ride_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        editingRide = ride;
        etRideTitle.setText(ride.getTitle());
        etOrigin.setText(ride.getOrigin().getPOIName());
        etDestination.setText(ride.getDestination().getPOIName());
        departure.setTime(ride.getDepartureTime());
        showDeparture();
        etMaxRiders.setText(String.valueOf(ride.getMaxRiders()));
        etExpectedCost.setText(String.format(Locale.US, "%.2f", ride.getTotalExpectedCost()));
        etNote.setText(ride.getNote());
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

    private void saveChanges() {
        String note = etNote.getText() != null ? etNote.getText().toString().trim() : "";
        setLoading(true);
        rideManager.editRide(editingRide, departure.getTime(), note.isEmpty() ? null : note, updated -> {
            setLoading(false);
            if (updated != null) {
                Toast.makeText(this, R.string.msg_ride_updated, Toast.LENGTH_LONG).show();
                finish();
            } else {
                Toast.makeText(this, R.string.error_update_ride, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void validateAndPost() {
        if (editingRide != null) {
            saveChanges();
            return;
        }

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

        double expectedCost = RideManager.parseCost(costText);
        if (expectedCost <= 0) {
            tilExpectedCost.setError("Please enter a cost greater than $0 with up to 2 decimals");
            isValid = false;
        }

        if (!isValid) {
            return;
        }

        setLoading(true);

        lookUpLocation(origin, pinnedOrigin, originLocation -> {
            if (originLocation == null) {
                tilOrigin.setError("Please enter a valid origin.");
                setLoading(false);
                return;
            }
            lookUpLocation(destination, pinnedDestination, destinationLocation -> {
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
            });
        });
    }

    private void applyMapSelection(Intent data) {
        String originName = data.getStringExtra(MapPinActivity.EXTRA_ORIGIN_NAME);
        String destinationName = data.getStringExtra(MapPinActivity.EXTRA_DESTINATION_NAME);
        if (originName == null || destinationName == null) {
            return;
        }
        pinnedOrigin = new Location(originName,
                data.getDoubleExtra(MapPinActivity.EXTRA_ORIGIN_LAT, 0),
                data.getDoubleExtra(MapPinActivity.EXTRA_ORIGIN_LON, 0));
        pinnedDestination = new Location(destinationName,
                data.getDoubleExtra(MapPinActivity.EXTRA_DESTINATION_LAT, 0),
                data.getDoubleExtra(MapPinActivity.EXTRA_DESTINATION_LON, 0));
        etOrigin.setText(originName);
        etDestination.setText(destinationName);
        tilOrigin.setError(null);
        tilDestination.setError(null);
    }

    private void forgetPinIfEdited(TextInputEditText field, boolean originField) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Location pinned = originField ? pinnedOrigin : pinnedDestination;
                if (pinned != null && !s.toString().trim().equals(pinned.getPOIName())) {
                    if (originField) {
                        pinnedOrigin = null;
                    } else {
                        pinnedDestination = null;
                    }
                }
            }
        });
    }

    private void lookUpLocation(String keyword, Location current, Consumer<Location> onFound) {
        if (current != null && keyword.equals(current.getPOIName())) {
            onFound.accept(current);
            return;
        }
        geocoder.lookUp(keyword, onFound, this::showLocationUnavailable);
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

package com.example.trojan_rideshare;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Ride search page (requirement 1.3, UCS-2.2).
 * Defaults from the spec: radius 1 mile, date tomorrow, time window 11:00 a.m. to 1:00 p.m.
 */
public class SearchRideActivity extends AppCompatActivity {

    private static final String ERROR_BOTH_PLACES = "Please enter both an origin and destination";
    private static final String ERROR_ORIGIN = "Please enter a valid origin.";
    private static final String ERROR_DESTINATION = "Please enter a valid destination.";
    private static final String ERROR_FILTER = "Please enter a valid filter option.";
    private static final String ERROR_NONE = "No rides found. Try widening your radius or changing your filters.";

    private TextInputLayout tilOrigin, tilDestination, tilRadius, tilRideDate, tilWindowStart, tilWindowEnd;
    private TextInputEditText etOrigin, etDestination, etRadius, etRideDate, etWindowStart, etWindowEnd;
    private MaterialButton btnSearch;
    private ProgressBar progressBar;
    private TextView tvResultsMessage;
    private Spinner spinnerSort;
    private LinearLayout resultsContainer;

    private final MapboxGeocoder geocoder = new MapboxGeocoder();
    private RideSearchManager searchManager;
    private final Calendar rideDate = Calendar.getInstance();
    private final Calendar windowStart = Calendar.getInstance();
    private final Calendar windowEnd = Calendar.getInstance();

    private Location originLocation;
    private Location destinationLocation;
    private List<Ride> filteredResults = new ArrayList<>();
    private boolean resultsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search_ride);

        if (AuthManager.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        searchManager = new RideSearchManager();

        tilOrigin = findViewById(R.id.tilOrigin);
        tilDestination = findViewById(R.id.tilDestination);
        tilRadius = findViewById(R.id.tilRadius);
        tilRideDate = findViewById(R.id.tilRideDate);
        tilWindowStart = findViewById(R.id.tilWindowStart);
        tilWindowEnd = findViewById(R.id.tilWindowEnd);
        etOrigin = findViewById(R.id.etOrigin);
        etDestination = findViewById(R.id.etDestination);
        etRadius = findViewById(R.id.etRadius);
        etRideDate = findViewById(R.id.etRideDate);
        etWindowStart = findViewById(R.id.etWindowStart);
        etWindowEnd = findViewById(R.id.etWindowEnd);
        btnSearch = findViewById(R.id.btnSearch);
        progressBar = findViewById(R.id.progressBar);
        tvResultsMessage = findViewById(R.id.tvResultsMessage);
        spinnerSort = findViewById(R.id.spinnerSort);
        resultsContainer = findViewById(R.id.resultsContainer);
        TextView tvCancel = findViewById(R.id.tvCancel);

        applyDefaults();

        etRideDate.setOnClickListener(v -> pickDate());
        etWindowStart.setOnClickListener(v -> pickTime(windowStart, etWindowStart, tilWindowStart));
        etWindowEnd.setOnClickListener(v -> pickTime(windowEnd, etWindowEnd, tilWindowEnd));
        btnSearch.setOnClickListener(v -> validateAndSearch());
        tvCancel.setOnClickListener(v -> finish());

        ArrayAdapter<CharSequence> sortAdapter = ArrayAdapter.createFromResource(
                this, R.array.search_sort_options, android.R.layout.simple_spinner_item);
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSort.setAdapter(sortAdapter);
        spinnerSort.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (resultsReady) {
                    showResults(sortCurrent(filteredResults));
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void applyDefaults() {
        rideDate.add(Calendar.DAY_OF_YEAR, 1);
        rideDate.set(Calendar.SECOND, 0);
        rideDate.set(Calendar.MILLISECOND, 0);
        copyDate(rideDate, windowStart);
        copyDate(rideDate, windowEnd);
        windowStart.set(Calendar.HOUR_OF_DAY, 11);
        windowStart.set(Calendar.MINUTE, 0);
        windowEnd.set(Calendar.HOUR_OF_DAY, 13);
        windowEnd.set(Calendar.MINUTE, 0);
        etRadius.setText(getString(R.string.default_radius));
        showDateAndWindow();
    }

    private void pickDate() {
        new DatePickerDialog(this, (view, year, month, day) -> {
            rideDate.set(year, month, day);
            copyDate(rideDate, windowStart);
            copyDate(rideDate, windowEnd);
            showDateAndWindow();
        }, rideDate.get(Calendar.YEAR), rideDate.get(Calendar.MONTH), rideDate.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime(Calendar target, TextInputEditText field, TextInputLayout layout) {
        new TimePickerDialog(this, (view, hour, minute) -> {
            target.set(Calendar.HOUR_OF_DAY, hour);
            target.set(Calendar.MINUTE, minute);
            field.setText(new SimpleDateFormat("h:mm a", Locale.US).format(target.getTime()));
            layout.setError(null);
        }, target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE), false).show();
    }

    private void showDateAndWindow() {
        etRideDate.setText(new SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(rideDate.getTime()));
        etWindowStart.setText(new SimpleDateFormat("h:mm a", Locale.US).format(windowStart.getTime()));
        etWindowEnd.setText(new SimpleDateFormat("h:mm a", Locale.US).format(windowEnd.getTime()));
        tilRideDate.setError(null);
        tilWindowStart.setError(null);
        tilWindowEnd.setError(null);
    }

    private void validateAndSearch() {
        clearErrors();
        String origin = textOf(etOrigin);
        String destination = textOf(etDestination);
        String radiusText = textOf(etRadius);

        boolean valid = true;
        if (origin.isEmpty() || destination.isEmpty()) {
            if (origin.isEmpty()) {
                tilOrigin.setError(ERROR_BOTH_PLACES);
            }
            if (destination.isEmpty()) {
                tilDestination.setError(ERROR_BOTH_PLACES);
            }
            valid = false;
        }

        Double radius = parseRadius(radiusText);
        if (radius == null || radius <= 0) {
            tilRadius.setError(ERROR_FILTER);
            valid = false;
        }

        if (isBeforeToday(rideDate)) {
            tilRideDate.setError(ERROR_FILTER);
            valid = false;
        }

        if (!windowEnd.after(windowStart)) {
            tilWindowEnd.setError(ERROR_FILTER);
            valid = false;
        }

        if (!valid) {
            return;
        }

        setLoading(true);
        double radiusMiles = radius;
        geocoder.lookUp(origin, originResult -> {
            if (originResult == null) {
                tilOrigin.setError(ERROR_ORIGIN);
                setLoading(false);
                return;
            }
            geocoder.lookUp(destination, destinationResult -> {
                if (destinationResult == null) {
                    tilDestination.setError(ERROR_DESTINATION);
                    setLoading(false);
                    return;
                }
                originLocation = originResult;
                destinationLocation = destinationResult;
                runSearch(radiusMiles);
            }, this::showLocationUnavailable);
        }, this::showLocationUnavailable);
    }

    private void runSearch(double radiusMiles) {
        searchManager.setSearchContext(originLocation, destinationLocation, windowStart.getTime());
        searchManager.getRidesByRadius(
                originLocation.getLat(), originLocation.getLon(),
                destinationLocation.getLat(), destinationLocation.getLon(),
                rides -> {
                    setLoading(false);
                    if (rides == null) {
                        Toast.makeText(this, R.string.error_search_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    filteredResults = applyFilters(rides, radiusMiles);
                    resultsReady = true;
                    if (spinnerSort.getSelectedItemPosition() != 0) {
                        spinnerSort.setSelection(0);
                    } else {
                        showResults(sortCurrent(filteredResults));
                    }
                });
    }

    /**
     * Sequence diagram order: availability, time window, status, then origin.
     * Date, destination, and "user is not the organizer" come from UCS-2.2.6 and the class diagram.
     * Status is on-time or delayed, so filterByStatus is called once for each.
     */
    private List<Ride> applyFilters(List<Ride> rides, double radiusMiles) {
        List<Ride> filtered = searchManager.filterByAvailable(rides);
        filtered = searchManager.filterByTimeRange(filtered, windowStart.getTime(), windowEnd.getTime());
        filtered = keepOnTimeOrDelayed(filtered);
        filtered = searchManager.filterByOrigin(filtered, originLocation, radiusMiles);
        filtered = searchManager.filterByDestination(filtered, destinationLocation, radiusMiles);
        filtered = searchManager.filterByDate(filtered, rideDate.getTime());
        filtered = searchManager.filterByParticipants(filtered);
        return filtered;
    }

    private List<Ride> keepOnTimeOrDelayed(List<Ride> rides) {
        List<Ride> kept = new ArrayList<>(searchManager.filterByStatus(rides, Status.ON_TIME));
        kept.addAll(searchManager.filterByStatus(rides, Status.DELAYED));
        return kept;
    }

    private List<Ride> sortCurrent(List<Ride> rides) {
        int position = spinnerSort.getSelectedItemPosition();
        if (position == 1) {
            return searchManager.sortByLatest(rides);
        }
        if (position == 2) {
            return searchManager.sortByProximity(rides);
        }
        if (position == 3) {
            return searchManager.sortByProximityAndTime(rides);
        }
        if (position == 4) {
            return searchManager.sortByCost(rides);
        }
        return searchManager.sortByEarliest(rides);
    }

    private void showResults(List<Ride> rides) {
        resultsContainer.removeAllViews();
        if (rides.isEmpty()) {
            tvResultsMessage.setText(ERROR_NONE);
            return;
        }
        tvResultsMessage.setText(getResources().getQuantityString(R.plurals.search_result_count, rides.size(), rides.size()));
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Ride ride : rides) {
            View row = inflater.inflate(R.layout.item_ride_result, resultsContainer, false);
            ((TextView) row.findViewById(R.id.tvRideTitle)).setText(ride.getTitle());
            ((TextView) row.findViewById(R.id.tvOrganizer)).setText(getString(R.string.label_organizer, organizerName(ride)));
            ((TextView) row.findViewById(R.id.tvRoute)).setText(getString(R.string.label_route,
                    ride.getOrigin().getPOIName(), ride.getDestination().getPOIName()));
            String when = new SimpleDateFormat("EEE, MMM d, yyyy · h:mm a", Locale.US).format(ride.getDepartureTime());
            ((TextView) row.findViewById(R.id.tvDeparture)).setText(when);
            int seatsOpen = Math.max(0, ride.getMaxRiders() - ride.getNumOfRiders());
            ((TextView) row.findViewById(R.id.tvStatus)).setText(getString(R.string.label_status_capacity,
                    Status.valueOf(ride.getRideStatus()).toString(),
                    CapacityStatus.valueOf(ride.getCapacityStatus()).toString(),
                    seatsOpen));
            double divisor = ride.getMaxRiders() == 0 ? 1 : ride.getMaxRiders();
            String perPerson = String.format(Locale.US, "%.2f", ride.getTotalExpectedCost() / divisor);
            ((TextView) row.findViewById(R.id.tvCost)).setText(getString(R.string.label_expected_cost,
                    ride.getTotalExpectedCost(), perPerson));
            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, RideDetailActivity.class);
                intent.putExtra(RideDetailActivity.EXTRA_RIDE_ID, ride.getRideID());
                startActivity(intent);
            });
            resultsContainer.addView(row);
        }
    }

    private void showLocationUnavailable(String message) {
        tilOrigin.setError(message);
        tilDestination.setError(message);
        setLoading(false);
    }

    private void clearErrors() {
        tilOrigin.setError(null);
        tilDestination.setError(null);
        tilRadius.setError(null);
        tilRideDate.setError(null);
        tilWindowStart.setError(null);
        tilWindowEnd.setError(null);
    }

    private void setLoading(boolean loading) {
        btnSearch.setEnabled(!loading);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private static String organizerName(Ride ride) {
        User organizer = ride.getOrganizer();
        if (organizer.getNickname() != null && !organizer.getNickname().isEmpty()) {
            return organizer.getNickname();
        }
        return organizer.getFullName();
    }

    private static String textOf(TextInputEditText field) {
        return field.getText() != null ? field.getText().toString().trim() : "";
    }

    private static Double parseRadius(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBeforeToday(Calendar day) {
        Calendar today = Calendar.getInstance();
        if (day.get(Calendar.YEAR) != today.get(Calendar.YEAR)) {
            return day.get(Calendar.YEAR) < today.get(Calendar.YEAR);
        }
        return day.get(Calendar.DAY_OF_YEAR) < today.get(Calendar.DAY_OF_YEAR);
    }

    private static void copyDate(Calendar from, Calendar to) {
        to.set(Calendar.YEAR, from.get(Calendar.YEAR));
        to.set(Calendar.MONTH, from.get(Calendar.MONTH));
        to.set(Calendar.DAY_OF_MONTH, from.get(Calendar.DAY_OF_MONTH));
    }
}

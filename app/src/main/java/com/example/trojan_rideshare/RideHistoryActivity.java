package com.example.trojan_rideshare;

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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public class RideHistoryActivity extends AppCompatActivity {

    private static final String ERROR_ORIGIN = "Please enter a valid origin.";
    private static final String ERROR_DESTINATION = "Please enter a valid destination.";
    private static final String ERROR_FILTER = "Please enter a valid filter option.";

    private TextInputLayout tilOrigin, tilDestination, tilRadius;
    private TextInputEditText etOrigin, etDestination, etRadius;
    private Spinner spinnerStatus, spinnerParticipant, spinnerSort;
    private MaterialButton btnSearch;
    private ProgressBar progressBar, searchProgressBar;
    private TextView tvUpcomingMessage, tvHistoryMessage;
    private LinearLayout upcomingContainer, historyContainer;

    private final MapboxGeocoder geocoder = new MapboxGeocoder();
    private UserRideHistoryManager historyManager;
    private RideSearchManager searchManager;
    private RideManager rideManager;
    private CostSplitManager costSplitManager;
    private AuthManager authManager;

    private List<Ride> pastRides = new ArrayList<>();
    private final Map<String, Expense> expensesById = new HashMap<>();
    private final List<User> participants = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_history);

        authManager = AuthManager.getInstance();
        if (authManager.getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        historyManager = new UserRideHistoryManager();
        searchManager = new RideSearchManager();
        rideManager = new RideManager();
        costSplitManager = new CostSplitManager();

        tilOrigin = findViewById(R.id.tilOrigin);
        tilDestination = findViewById(R.id.tilDestination);
        tilRadius = findViewById(R.id.tilRadius);
        etOrigin = findViewById(R.id.etOrigin);
        etDestination = findViewById(R.id.etDestination);
        etRadius = findViewById(R.id.etRadius);
        spinnerStatus = findViewById(R.id.spinnerStatus);
        spinnerParticipant = findViewById(R.id.spinnerParticipant);
        spinnerSort = findViewById(R.id.spinnerSort);
        btnSearch = findViewById(R.id.btnSearch);
        progressBar = findViewById(R.id.progressBar);
        searchProgressBar = findViewById(R.id.searchProgressBar);
        tvUpcomingMessage = findViewById(R.id.tvUpcomingMessage);
        tvHistoryMessage = findViewById(R.id.tvHistoryMessage);
        upcomingContainer = findViewById(R.id.upcomingContainer);
        historyContainer = findViewById(R.id.historyContainer);
        TextView tvBack = findViewById(R.id.tvBack);

        etRadius.setText(getString(R.string.default_radius));
        spinnerStatus.setAdapter(adapterFromArray(R.array.history_status_options));
        spinnerSort.setAdapter(adapterFromArray(R.array.history_sort_options));
        showParticipants();

        btnSearch.setOnClickListener(v -> validateAndSearch());
        tvBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (historyManager != null) {
            loadRides();
        }
    }

    private void loadRides() {
        progressBar.setVisibility(View.VISIBLE);
        historyManager.getHistory(rides -> {
            if (rides == null) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(this, R.string.error_load_rides, Toast.LENGTH_LONG).show();
                return;
            }
            costSplitManager.getExpenses(authManager.getCurrentUser(), expenses -> {
                progressBar.setVisibility(View.GONE);
                expensesById.clear();
                if (expenses != null) {
                    for (Expense expense : expenses) {
                        expensesById.put(expense.getId(), expense);
                    }
                }
                List<Ride> upcoming = new ArrayList<>(searchManager.filterByStatus(rides, Status.ON_TIME));
                upcoming.addAll(searchManager.filterByStatus(rides, Status.DELAYED));
                showRides(searchManager.sortByEarliest(upcoming), upcomingContainer, tvUpcomingMessage,
                        getString(R.string.no_upcoming_rides), true);

                pastRides = new ArrayList<>(searchManager.filterByStatus(rides, Status.COMPLETED));
                pastRides.addAll(searchManager.filterByStatus(rides, Status.CANCELLED));
                showRides(searchManager.sortByLatest(pastRides), historyContainer, tvHistoryMessage,
                        getString(R.string.no_past_rides), false);
            });
        });
        historyManager.getParticipants(users -> {
            participants.clear();
            if (users != null) {
                participants.addAll(users);
            }
            showParticipants();
        });
    }

    private void showParticipants() {
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.option_all));
        for (User user : participants) {
            names.add(displayName(user));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerParticipant.setAdapter(adapter);
    }

    private void validateAndSearch() {
        tilOrigin.setError(null);
        tilDestination.setError(null);
        tilRadius.setError(null);

        String origin = textOf(etOrigin);
        String destination = textOf(etDestination);
        double radius = parseRadius(textOf(etRadius));
        if (radius <= 0) {
            tilRadius.setError(ERROR_FILTER);
            return;
        }

        setSearching(true);
        lookUpIfEntered(origin, tilOrigin, ERROR_ORIGIN, originLocation ->
                lookUpIfEntered(destination, tilDestination, ERROR_DESTINATION, destinationLocation -> {
                    setSearching(false);
                    showRides(applyFilters(originLocation, destinationLocation, radius), historyContainer,
                            tvHistoryMessage, getString(R.string.no_matching_rides), false);
                }));
    }

    private void lookUpIfEntered(String keyword, TextInputLayout field, String notFoundError, Consumer<Location> onFound) {
        if (keyword.isEmpty()) {
            onFound.accept(null);
            return;
        }
        geocoder.lookUp(keyword, location -> {
            if (location == null) {
                field.setError(notFoundError);
                setSearching(false);
                return;
            }
            onFound.accept(location);
        }, this::showLocationUnavailable);
    }

    private List<Ride> applyFilters(Location origin, Location destination, double radius) {
        List<Ride> filtered = pastRides;
        if (origin != null) {
            filtered = searchManager.filterByOrigin(filtered, origin, radius);
        }
        if (destination != null) {
            filtered = searchManager.filterByDestination(filtered, destination, radius);
        }
        int status = spinnerStatus.getSelectedItemPosition();
        if (status == 1) {
            filtered = searchManager.filterByStatus(filtered, Status.COMPLETED);
        } else if (status == 2) {
            filtered = searchManager.filterByStatus(filtered, Status.CANCELLED);
        }
        int participant = spinnerParticipant.getSelectedItemPosition();
        if (participant > 0 && participant <= participants.size()) {
            filtered = searchManager.filterByParticipants(filtered, participants.get(participant - 1).getId());
        }
        return spinnerSort.getSelectedItemPosition() == 1
                ? searchManager.sortByEarliest(filtered)
                : searchManager.sortByLatest(filtered);
    }

    private void showRides(List<Ride> rides, LinearLayout container, TextView message, String emptyText, boolean upcoming) {
        container.removeAllViews();
        message.setText(rides.isEmpty() ? emptyText : "");
        message.setVisibility(rides.isEmpty() ? View.VISIBLE : View.GONE);
        String uid = authManager.getCurrentUser().getId();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Ride ride : rides) {
            View row = inflater.inflate(R.layout.item_ride_result, container, false);
            boolean isOrganizer = uid.equals(ride.getOrganizer().getId());
            String status = Status.valueOf(ride.getRideStatus()).toString();
            ((TextView) row.findViewById(R.id.tvRideTitle)).setText(ride.getTitle());
            ((TextView) row.findViewById(R.id.tvOrganizer)).setText(getString(R.string.label_role,
                    getString(isOrganizer ? R.string.role_organizer : R.string.role_rider)));
            ((TextView) row.findViewById(R.id.tvRoute)).setText(getString(R.string.label_route,
                    ride.getOrigin().getPOIName(), ride.getDestination().getPOIName()));
            ((TextView) row.findViewById(R.id.tvDeparture)).setText(
                    new SimpleDateFormat("EEE, MMM d, yyyy · h:mm a", Locale.US).format(ride.getDepartureTime()));
            TextView tvStatus = row.findViewById(R.id.tvStatus);
            TextView tvCost = row.findViewById(R.id.tvCost);
            if (upcoming) {
                tvStatus.setText(getString(R.string.label_status_riders, status,
                        CapacityStatus.valueOf(ride.getCapacityStatus()).toString(),
                        ride.getNumOfRiders(), ride.getMaxRiders()));
                String perPerson = String.format(Locale.US, "%.2f", ride.getTotalExpectedCost() / (double) ride.getMaxRiders());
                tvCost.setText(getString(R.string.label_expected_cost, ride.getTotalExpectedCost(), perPerson));
            } else {
                tvStatus.setText(getString(R.string.label_history_status, status, ride.getNumOfRiders()));
                if (Status.COMPLETED.name().equals(ride.getRideStatus())) {
                    tvCost.setText(costText(ride, uid, isOrganizer));
                } else {
                    tvCost.setVisibility(View.GONE);
                }
            }
            row.setOnClickListener(v -> showActions(ride, isOrganizer));
            container.addView(row);
        }
    }

    private String costText(Ride ride, String uid, boolean isOrganizer) {
        if (!isOrganizer) {
            Expense expense = expensesById.get(CostSplitManager.rideExpenseId(ride.getRideID(), uid));
            if (expense == null) {
                return getString(R.string.label_cost_not_split);
            }
            return getString(R.string.label_your_cost, money(expense.getAmount()),
                    getString(expense.isPaid() ? R.string.status_paid : R.string.status_not_paid));
        }
        double owed = 0;
        int paid = 0;
        int split = 0;
        for (String riderId : ride.getRiders()) {
            Expense expense = expensesById.get(CostSplitManager.rideExpenseId(ride.getRideID(), riderId));
            if (expense == null) {
                continue;
            }
            split++;
            owed += expense.getAmount();
            if (expense.isPaid()) {
                paid++;
            }
        }
        if (split == 0) {
            return getString(R.string.label_cost_not_split);
        }
        return getString(R.string.label_owed_to_you, money(owed), paid, split);
    }

    private static String money(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    private void showActions(Ride ride, boolean isOrganizer) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_ride_actions, null);
        ((TextView) view.findViewById(R.id.tvDetails)).setText(ride.getDisplayInfo());
        MaterialButton btnEdit = view.findViewById(R.id.btnEditRide);
        MaterialButton btnStatus = view.findViewById(R.id.btnChangeStatus);
        MaterialButton btnCancel = view.findViewById(R.id.btnCancelRide);
        MaterialButton btnLeave = view.findViewById(R.id.btnLeaveRide);

        int organizerOnly = isOrganizer ? View.VISIBLE : View.GONE;
        btnEdit.setVisibility(organizerOnly);
        btnStatus.setVisibility(organizerOnly);
        btnCancel.setVisibility(organizerOnly);
        btnLeave.setVisibility(Status.COMPLETED.name().equals(ride.getRideStatus()) ? View.GONE : View.VISIBLE);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();
        btnEdit.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(this, CreateRideActivity.class);
            intent.putExtra(CreateRideActivity.EXTRA_RIDE_ID, ride.getRideID());
            startActivity(intent);
        });
        btnStatus.setOnClickListener(v -> {
            dialog.dismiss();
            pickStatus(ride);
        });
        btnCancel.setOnClickListener(v -> {
            dialog.dismiss();
            confirm(R.string.confirm_cancel_ride, () -> rideManager.cancelRide(authManager.getCurrentUser(), ride,
                    success -> afterAction(success, R.string.msg_ride_cancelled)));
        });
        btnLeave.setOnClickListener(v -> {
            dialog.dismiss();
            confirm(R.string.confirm_leave_ride, () -> rideManager.leaveRide(ride,
                    success -> afterAction(success, R.string.msg_left_ride)));
        });
        dialog.show();
    }

    private void pickStatus(Ride ride) {
        Status[] choices = {Status.ON_TIME, Status.DELAYED, Status.COMPLETED};
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_change_status)
                .setItems(R.array.ride_status_choices, (dialog, which) -> {
                    progressBar.setVisibility(View.VISIBLE);
                    rideManager.updateRideStatus(choices[which], ride,
                            success -> afterAction(success, R.string.msg_status_updated));
                })
                .show();
    }

    private void confirm(int messageId, Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setMessage(messageId)
                .setPositiveButton(R.string.action_yes, (dialog, which) -> {
                    progressBar.setVisibility(View.VISIBLE);
                    onConfirm.run();
                })
                .setNegativeButton(R.string.action_no, null)
                .show();
    }

    private void afterAction(boolean success, int successMessageId) {
        progressBar.setVisibility(View.GONE);
        Toast.makeText(this, success ? successMessageId : R.string.error_update_ride, Toast.LENGTH_LONG).show();
        if (success) {
            loadRides();
        }
    }

    private ArrayAdapter<CharSequence> adapterFromArray(int arrayId) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, arrayId,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void showLocationUnavailable(String message) {
        tilOrigin.setError(message);
        tilDestination.setError(message);
        setSearching(false);
    }

    private void setSearching(boolean searching) {
        btnSearch.setEnabled(!searching);
        searchProgressBar.setVisibility(searching ? View.VISIBLE : View.GONE);
    }

    private static String displayName(User user) {
        if (user.getNickname() != null && !user.getNickname().isEmpty()) {
            return user.getNickname();
        }
        return user.getFullName();
    }

    private static String textOf(TextInputEditText field) {
        return field.getText() != null ? field.getText().toString().trim() : "";
    }

    private static double parseRadius(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

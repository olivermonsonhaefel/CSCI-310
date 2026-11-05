package com.example.trojan_rideshare;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One card for the signed-in user and for any other user.
 * The payment flag area is left for the teammate who owns that feature.
 */
public class ProfileActivity extends AppCompatActivity {

    public static final String EXTRA_USER_ID = "userId";

    private final ProfileManager profiles = new ProfileManager();

    private TextView tvProfileName;
    private TextView tvProfileNickname;
    private TextView tvProfileEmail;
    private TextView tvProfileYear;
    private TextView tvPublicHint;
    private TextView tvRidesMessage;
    private TextView tvUpcomingMessage;
    private TextView tvCompletedMessage;
    private TextView tvCancelledMessage;
    private LinearLayout upcomingContainer;
    private LinearLayout completedContainer;
    private LinearLayout cancelledContainer;
    private ProgressBar progressBar;
    private String userId;
    private boolean ownProfile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenInsets.enable(this);
        setContentView(R.layout.activity_profile);
        ScreenInsets.pad(findViewById(R.id.profileScroll));

        User current = AuthManager.getInstance().getCurrentUser();
        if (current == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        String requested = getIntent().getStringExtra(EXTRA_USER_ID);
        userId = requested == null || requested.isEmpty() ? current.getId() : requested;
        ownProfile = userId.equals(current.getId());

        tvProfileName = findViewById(R.id.tvProfileName);
        tvProfileNickname = findViewById(R.id.tvProfileNickname);
        tvProfileEmail = findViewById(R.id.tvProfileEmail);
        tvProfileYear = findViewById(R.id.tvProfileYear);
        tvPublicHint = findViewById(R.id.tvPublicHint);
        tvRidesMessage = findViewById(R.id.tvRidesMessage);
        tvUpcomingMessage = findViewById(R.id.tvUpcomingMessage);
        tvCompletedMessage = findViewById(R.id.tvCompletedMessage);
        tvCancelledMessage = findViewById(R.id.tvCancelledMessage);
        upcomingContainer = findViewById(R.id.upcomingContainer);
        completedContainer = findViewById(R.id.completedContainer);
        cancelledContainer = findViewById(R.id.cancelledContainer);
        progressBar = findViewById(R.id.progressBar);
        findViewById(R.id.tvBack).setOnClickListener(v -> finish());

        if (ownProfile) {
            ((TextView) findViewById(R.id.tvProfileTitle)).setText(R.string.profile_title_own);
            tvPublicHint.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (userId != null) {
            loadProfile();
        }
    }

    private void loadProfile() {
        setLoading(true);
        profiles.loadUser(userId, user -> {
            if (user == null) {
                setLoading(false);
                showMessage(getString(R.string.profile_load_error));
                return;
            }
            showUser(user);
            if (ownProfile) {
                profiles.loadParticipatedRides(userId, rides -> {
                    if (rides == null) {
                        setLoading(false);
                        showMessage(getString(R.string.profile_load_error));
                        return;
                    }
                    profiles.loadPublicRideIds(userId, ids -> {
                        setLoading(false);
                        if (ids == null) {
                            showMessage(getString(R.string.profile_load_error));
                            return;
                        }
                        showRides(rides, new HashSet<>(ids));
                    });
                });
                return;
            }
            profiles.loadPublicRides(userId, rides -> {
                setLoading(false);
                if (rides == null) {
                    showMessage(getString(R.string.profile_load_error));
                    return;
                }
                showRides(rides, null);
            });
        });
    }

    private void showUser(User user) {
        String name = user.getFullName();
        if (name.isEmpty()) {
            name = user.getNickname();
        }
        if (name.isEmpty()) {
            name = getString(R.string.profile_unknown_name);
        }
        tvProfileName.setText(name);

        String nickname = user.getNickname();
        if (!nickname.isEmpty() && !nickname.equals(name)) {
            tvProfileNickname.setText(nickname);
            tvProfileNickname.setVisibility(View.VISIBLE);
        } else {
            tvProfileNickname.setVisibility(View.GONE);
        }

        if (!user.getEmail().isEmpty()) {
            tvProfileEmail.setText(user.getEmail());
            tvProfileEmail.setVisibility(View.VISIBLE);
        } else {
            tvProfileEmail.setVisibility(View.GONE);
        }

        if (user.getGraduationYear() > 0) {
            tvProfileYear.setText(getString(R.string.label_graduation_year, user.getGraduationYear()));
            tvProfileYear.setVisibility(View.VISIBLE);
        } else {
            tvProfileYear.setVisibility(View.GONE);
        }
    }

    private void showRides(List<Ride> rides, Set<String> publicIds) {
        tvRidesMessage.setVisibility(View.GONE);
        List<Ride> upcoming = new ArrayList<>();
        List<Ride> completed = new ArrayList<>();
        List<Ride> cancelled = new ArrayList<>();
        for (Ride ride : rides) {
            Status section = ProfileManager.sectionFor(ride);
            if (section == Status.COMPLETED) {
                completed.add(ride);
            } else if (section == Status.CANCELLED) {
                cancelled.add(ride);
            } else {
                upcoming.add(ride);
            }
        }
        upcoming.sort(Comparator.comparing(Ride::getDepartureTime, Comparator.nullsLast(Comparator.naturalOrder())));
        completed.sort(Comparator.comparing(Ride::getDepartureTime, Comparator.nullsLast(Comparator.reverseOrder())));
        cancelled.sort(Comparator.comparing(Ride::getDepartureTime, Comparator.nullsLast(Comparator.reverseOrder())));

        fillSection(upcomingContainer, tvUpcomingMessage, upcoming, publicIds, true,
                ownProfile ? R.string.profile_no_upcoming : R.string.profile_no_public_upcoming);
        fillSection(completedContainer, tvCompletedMessage, completed, publicIds, false,
                ownProfile ? R.string.profile_no_completed : R.string.profile_no_public_completed);
        fillSection(cancelledContainer, tvCancelledMessage, cancelled, null, false,
                ownProfile ? R.string.profile_no_cancelled : R.string.profile_no_public_cancelled);
    }

    private void fillSection(LinearLayout container, TextView message, List<Ride> rides, Set<String> publicIds,
                             boolean openDetails, int emptyMessage) {
        container.removeAllViews();
        if (rides.isEmpty()) {
            message.setText(emptyMessage);
            message.setVisibility(View.VISIBLE);
            return;
        }
        message.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Ride ride : rides) {
            View row = inflater.inflate(R.layout.item_profile_ride, container, false);
            ((TextView) row.findViewById(R.id.tvRideTitle)).setText(blank(ride.getTitle()));
            String origin = ride.getOrigin() != null ? ride.getOrigin().getPOIName() : "";
            String destination = ride.getDestination() != null ? ride.getDestination().getPOIName() : "";
            ((TextView) row.findViewById(R.id.tvRoute)).setText(getString(R.string.label_route, origin, destination));
            String when = ride.getDepartureTime() == null
                    ? ""
                    : new SimpleDateFormat("EEE, MMM d, yyyy · h:mm a", Locale.US).format(ride.getDepartureTime());
            ((TextView) row.findViewById(R.id.tvDeparture)).setText(when);
            ((TextView) row.findViewById(R.id.tvStatus)).setText(statusLabel(ride));
            if (openDetails) {
                row.findViewById(R.id.rideSummary).setOnClickListener(v -> openRide(ride));
            }

            RadioButton radio = row.findViewById(R.id.radioPublic);
            if (ownProfile && publicIds != null) {
                boolean shared = publicIds.contains(ride.getRideID());
                radio.setVisibility(View.VISIBLE);
                radio.setChecked(shared);
                radio.setOnClickListener(v -> togglePublic(radio, ride.getRideID(), shared));
            }
            container.addView(row);
        }
    }

    private String statusLabel(Ride ride) {
        try {
            return Status.valueOf(ride.getRideStatus()).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            return "";
        }
    }

    private void togglePublic(RadioButton radio, String rideId, boolean wasPublic) {
        boolean makePublic = !wasPublic;
        radio.setChecked(makePublic);
        radio.setEnabled(false);
        profiles.setRidePublic(userId, rideId, makePublic, saved -> {
            radio.setEnabled(true);
            if (!saved) {
                radio.setChecked(wasPublic);
                Toast.makeText(this, R.string.profile_public_failed, Toast.LENGTH_LONG).show();
                return;
            }
            loadProfile();
        });
    }

    private void openRide(Ride ride) {
        if (ride.getRideID() == null || ride.getRideID().isEmpty()) {
            return;
        }
        Intent intent = new Intent(this, RideDetailActivity.class);
        intent.putExtra(RideDetailActivity.EXTRA_RIDE_ID, ride.getRideID());
        startActivity(intent);
    }

    private void showMessage(String message) {
        tvRidesMessage.setText(message);
        tvRidesMessage.setVisibility(View.VISIBLE);
        upcomingContainer.removeAllViews();
        completedContainer.removeAllViews();
        cancelledContainer.removeAllViews();
        tvUpcomingMessage.setVisibility(View.GONE);
        tvCompletedMessage.setVisibility(View.GONE);
        tvCancelledMessage.setVisibility(View.GONE);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}

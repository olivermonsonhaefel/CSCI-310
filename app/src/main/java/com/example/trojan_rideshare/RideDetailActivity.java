package com.example.trojan_rideshare;

import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Ride detail opened from search. UCS-2.2.13: if the ride was cancelled after the search,
 * show its current status and capacity and disable Request to Join.
 * UCS-2.3: the user can add an optional note and send a join request through JoinRequestManager.
 */
public class RideDetailActivity extends AppCompatActivity {

    public static final String EXTRA_RIDE_ID = "rideId";

    private TextView tvDetails;
    private TextView tvUnavailable;
    private TextInputLayout tilJoinNote;
    private TextInputEditText etJoinNote;
    private MaterialButton btnRequestJoin;
    private ProgressBar progressBar;
    private TextView tvSharedHeading;
    private TextView tvSharedEmpty;
    private LinearLayout sharedProfiles;
    private RideSearchManager searchManager;
    private JoinRequestManager joinRequestManager;
    private final ProfileManager profiles = new ProfileManager();

    private Ride ride;
    private boolean cancelled;
    private boolean noteTooLong;
    private boolean sending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenInsets.enable(this);
        setContentView(R.layout.activity_ride_detail);
        ScreenInsets.pad(findViewById(R.id.detailScroll));

        tvDetails = findViewById(R.id.tvDetails);
        tvUnavailable = findViewById(R.id.tvUnavailable);
        tilJoinNote = findViewById(R.id.tilJoinNote);
        etJoinNote = findViewById(R.id.etJoinNote);
        btnRequestJoin = findViewById(R.id.btnRequestJoin);
        progressBar = findViewById(R.id.progressBar);
        tvSharedHeading = findViewById(R.id.tvSharedHeading);
        tvSharedEmpty = findViewById(R.id.tvSharedEmpty);
        sharedProfiles = findViewById(R.id.sharedProfiles);
        TextView tvBack = findViewById(R.id.tvBack);

        // UCS-2.3.6: leaving the form returns the user to the search where they left off
        tvBack.setOnClickListener(v -> finish());
        btnRequestJoin.setOnClickListener(v -> sendRequest());
        etJoinNote.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                checkNote();
            }
        });

        String rideId = getIntent().getStringExtra(EXTRA_RIDE_ID);
        if (rideId == null || rideId.isEmpty()) {
            Toast.makeText(this, R.string.error_ride_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        searchManager = new RideSearchManager();
        joinRequestManager = new JoinRequestManager();
        btnRequestJoin.setEnabled(false); // enabled once the ride has loaded
    }

    private void showRide(Ride loaded) {
        progressBar.setVisibility(View.GONE);
        if (loaded == null) {
            Toast.makeText(this, R.string.error_ride_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ride = loaded;
        tvDetails.setText(ride.getDisplayInfo());
        cancelled = Status.CANCELLED.name().equals(ride.getRideStatus());
        boolean closed = cancelled || Status.COMPLETED.name().equals(ride.getRideStatus());
        tvUnavailable.setVisibility(cancelled ? View.VISIBLE : View.GONE);
        tilJoinNote.setVisibility(closed ? View.GONE : View.VISIBLE);
        btnRequestJoin.setVisibility(closed ? View.GONE : View.VISIBLE);
        updateButton();
        profiles.loadUsersWhoShared(ride, this::showSharedProfiles);
    }

    private void showSharedProfiles(List<User> people) {
        sharedProfiles.removeAllViews();
        tvSharedHeading.setVisibility(View.VISIBLE);
        if (people == null || people.isEmpty()) {
            tvSharedEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvSharedEmpty.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (User person : people) {
            TextView row = (TextView) inflater.inflate(R.layout.item_shared_profile, sharedProfiles, false);
            row.setText(getString(R.string.profile_shared_person, displayName(person)));
            row.setPaintFlags(row.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            row.setOnClickListener(v -> openProfile(person.getId()));
            sharedProfiles.addView(row);
        }
    }

    private void openProfile(String userId) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        Intent intent = new Intent(this, ProfileActivity.class);
        intent.putExtra(ProfileActivity.EXTRA_USER_ID, userId);
        startActivity(intent);
    }

    private String displayName(User user) {
        if (user == null) {
            return getString(R.string.profile_unknown_name);
        }
        if (user.getFullName() != null && !user.getFullName().isEmpty()) {
            return user.getFullName();
        }
        if (user.getNickname() != null && !user.getNickname().isEmpty()) {
            return user.getNickname();
        }
        return getString(R.string.profile_unknown_name);
    }

    /** UCS-2.3.4: show "Reduce character count ({chars}/250)." and disable the button when the note is too long. */
    private void checkNote() {
        String error = JoinRequestManager.checkMessage(noteText());
        noteTooLong = error != null;
        tilJoinNote.setError(error);
        updateButton();
    }

    private void updateButton() {
        btnRequestJoin.setEnabled(ride != null && !cancelled && !noteTooLong && !sending);
    }

    private void sendRequest() {
        User user = AuthManager.getInstance().getCurrentUser();
        if (ride == null || user == null) {
            Toast.makeText(this, R.string.error_ride_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        sending = true;
        updateButton();
        progressBar.setVisibility(View.VISIBLE);

        // requestJoin reads the ride again before saving (UCS-2.3.5) and passes back the message to show
        joinRequestManager.requestJoin(ride, user, noteText(), message -> {
            sending = false;
            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (JoinRequestManager.MSG_REQUEST_SENT.equals(message)) {
                finish(); // back to the search results
            } else {
                updateButton();
            }
        });
    }

    private String noteText() {
        return etJoinNote.getText() != null ? etJoinNote.getText().toString() : "";
    }

    @Override
    protected void onResume() {
        super.onResume();
        String rideId = getIntent().getStringExtra(EXTRA_RIDE_ID);
        if (searchManager != null && rideId != null) {
            searchManager.loadRide(rideId, this::showRide);
        }
    }
}
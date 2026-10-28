package com.example.trojan_rideshare;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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
    private RideSearchManager searchManager;
    private JoinRequestManager joinRequestManager;

    private Ride ride;
    private boolean cancelled;
    private boolean noteTooLong;
    private boolean sending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_detail);

        tvDetails = findViewById(R.id.tvDetails);
        tvUnavailable = findViewById(R.id.tvUnavailable);
        tilJoinNote = findViewById(R.id.tilJoinNote);
        etJoinNote = findViewById(R.id.etJoinNote);
        btnRequestJoin = findViewById(R.id.btnRequestJoin);
        progressBar = findViewById(R.id.progressBar);
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
        tvUnavailable.setVisibility(cancelled ? View.VISIBLE : View.GONE);
        updateButton();
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
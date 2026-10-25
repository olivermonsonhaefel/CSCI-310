package com.example.trojan_rideshare;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

/**
 * Ride detail opened from search. UCS-2.2.13: if the ride was cancelled after the search,
 * show its current status and capacity and disable Request to Join.
 * Sending the request itself is feature 2.3 (JoinRequestManager) and is not done here.
 */
public class RideDetailActivity extends AppCompatActivity {

    public static final String EXTRA_RIDE_ID = "rideId";

    private TextView tvDetails;
    private TextView tvUnavailable;
    private MaterialButton btnRequestJoin;
    private ProgressBar progressBar;
    private RideSearchManager searchManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_detail);

        tvDetails = findViewById(R.id.tvDetails);
        tvUnavailable = findViewById(R.id.tvUnavailable);
        btnRequestJoin = findViewById(R.id.btnRequestJoin);
        progressBar = findViewById(R.id.progressBar);
        TextView tvBack = findViewById(R.id.tvBack);

        tvBack.setOnClickListener(v -> finish());
        btnRequestJoin.setOnClickListener(v ->
                Toast.makeText(this, R.string.join_not_in_search, Toast.LENGTH_LONG).show());

        String rideId = getIntent().getStringExtra(EXTRA_RIDE_ID);
        if (rideId == null || rideId.isEmpty()) {
            Toast.makeText(this, R.string.error_ride_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        searchManager = new RideSearchManager();
    }

    private void showRide(Ride ride) {
        progressBar.setVisibility(View.GONE);
        if (ride == null) {
            Toast.makeText(this, R.string.error_ride_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tvDetails.setText(ride.getDisplayInfo());
        boolean cancelled = Status.CANCELLED.name().equals(ride.getRideStatus());
        tvUnavailable.setVisibility(cancelled ? View.VISIBLE : View.GONE);
        btnRequestJoin.setEnabled(!cancelled);
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

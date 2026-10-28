package com.example.trojan_rideshare;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The organizer's "Pending Join Requests" list (UCS-2.3.9). Shows Pending requests on every active ride
 * the user organizes, newest first, and lets the organizer accept or reject each one.
 * The rides come from UserRideHistoryManager.getHistory and each ride's requests from
 * JoinRequestManager.getRideRequests, so no extra manager method is needed.
 */
public class JoinRequestsActivity extends AppCompatActivity {

    private static final String DATE_FORMAT = "EEE, MMM d, yyyy · h:mm a";

    private ProgressBar progressBar;
    private TextView tvPendingMessage;
    private LinearLayout pendingContainer;

    private AuthManager authManager;
    private UserRideHistoryManager historyManager;
    private JoinRequestManager joinRequestManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_join_requests);

        authManager = AuthManager.getInstance();
        if (authManager.getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        historyManager = new UserRideHistoryManager();
        joinRequestManager = new JoinRequestManager();

        progressBar = findViewById(R.id.progressBar);
        tvPendingMessage = findViewById(R.id.tvPendingMessage);
        pendingContainer = findViewById(R.id.pendingContainer);
        TextView tvBack = findViewById(R.id.tvBack);
        tvBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (historyManager != null) {
            loadPendingRequests();
        }
    }

    private void loadPendingRequests() {
        User user = authManager.getCurrentUser();
        progressBar.setVisibility(View.VISIBLE);
        historyManager.getHistory(rides -> {
            if (rides == null) {
                showLoadError();
                return;
            }
            // Only rides this user organizes that can still take riders
            List<Ride> organized = new ArrayList<>();
            for (Ride ride : rides) {
                String status = ride.getRideStatus();
                boolean active = Status.ON_TIME.name().equals(status) || Status.DELAYED.name().equals(status);
                if (active && user.getId().equals(ride.getOrganizer().getId())) {
                    organized.add(ride);
                }
            }
            if (organized.isEmpty()) {
                showRequests(new ArrayList<>());
                return;
            }

            // Load each ride's requests, then show them all once the last ride has answered
            List<JoinRequest> pending = new ArrayList<>();
            int[] remaining = {organized.size()};
            boolean[] failed = {false};
            for (Ride ride : organized) {
                joinRequestManager.getRideRequests(ride, user, requests -> {
                    if (requests == null) {
                        failed[0] = true;
                    } else {
                        for (JoinRequest request : requests) {
                            if (RequestStatus.PENDING.name().equals(request.getStatus())) {
                                pending.add(request);
                            }
                        }
                    }
                    remaining[0]--;
                    if (remaining[0] == 0) {
                        if (failed[0]) {
                            showLoadError();
                        } else {
                            JoinRequestManager.sortNewestFirst(pending);
                            showRequests(pending);
                        }
                    }
                });
            }
        });
    }

    private void showLoadError() {
        progressBar.setVisibility(View.GONE);
        Toast.makeText(this, R.string.error_load_join_requests, Toast.LENGTH_LONG).show();
    }

    private void showRequests(List<JoinRequest> requests) {
        progressBar.setVisibility(View.GONE);
        pendingContainer.removeAllViews();
        tvPendingMessage.setText(requests.isEmpty() ? getString(R.string.no_pending_join_requests) : "");
        tvPendingMessage.setVisibility(requests.isEmpty() ? View.VISIBLE : View.GONE);

        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT, Locale.US);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (JoinRequest request : requests) {
            Ride ride = request.getRide();
            View row = inflater.inflate(R.layout.item_join_request, pendingContainer, false);

            ((TextView) row.findViewById(R.id.tvRequester)).setText(requesterName(request.getRequester()));
            ((TextView) row.findViewById(R.id.tvRideTitle)).setText(ride.getTitle());
            ((TextView) row.findViewById(R.id.tvRoute)).setText(getString(R.string.label_route,
                    ride.getOrigin().getPOIName(), ride.getDestination().getPOIName()));
            ((TextView) row.findViewById(R.id.tvDeparture)).setText(format.format(ride.getDepartureTime()));
            ((TextView) row.findViewById(R.id.tvSeats)).setText(getString(R.string.label_riders_count,
                    ride.getNumOfRiders(), ride.getMaxRiders()));
            TextView tvRequested = row.findViewById(R.id.tvRequested);
            if (request.getDateRequested() != null) {
                tvRequested.setText(getString(R.string.label_requested, format.format(request.getDateRequested())));
            } else {
                tvRequested.setVisibility(View.GONE);
            }
            String note = request.getMessage();
            if (note != null && !note.isEmpty()) {
                TextView tvNote = row.findViewById(R.id.tvNote);
                tvNote.setText(getString(R.string.label_join_note, note));
                tvNote.setVisibility(View.VISIBLE);
            }

            MaterialButton btnAccept = row.findViewById(R.id.btnAccept);
            MaterialButton btnReject = row.findViewById(R.id.btnReject);
            btnAccept.setOnClickListener(v -> accept(request, btnAccept, btnReject));
            btnReject.setOnClickListener(v -> confirmReject(request, btnAccept, btnReject));
            pendingContainer.addView(row);
        }
    }

    private void accept(JoinRequest request, MaterialButton btnAccept, MaterialButton btnReject) {
        setButtonsEnabled(false, btnAccept, btnReject);
        progressBar.setVisibility(View.VISIBLE);
        joinRequestManager.acceptJoinRequest(request, authManager.getCurrentUser(),
                success -> afterAction(success, R.string.msg_request_accepted, R.string.error_accept_request,
                        btnAccept, btnReject));
    }

    // A rejected rider can't request the ride again, so the organizer confirms first
    private void confirmReject(JoinRequest request, MaterialButton btnAccept, MaterialButton btnReject) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.confirm_reject_request, requesterName(request.getRequester())))
                .setPositiveButton(R.string.action_yes, (dialog, which) -> {
                    setButtonsEnabled(false, btnAccept, btnReject);
                    progressBar.setVisibility(View.VISIBLE);
                    joinRequestManager.rejectJoinRequest(request, authManager.getCurrentUser(),
                            success -> afterAction(success, R.string.msg_request_rejected,
                                    R.string.error_reject_request, btnAccept, btnReject));
                })
                .setNegativeButton(R.string.action_no, null)
                .show();
    }

    // Reloads after every answer so seat counts and the list stay current
    private void afterAction(boolean success, int successMessageId, int errorMessageId,
                             MaterialButton btnAccept, MaterialButton btnReject) {
        progressBar.setVisibility(View.GONE);
        Toast.makeText(this, success ? successMessageId : errorMessageId, Toast.LENGTH_LONG).show();
        if (success) {
            loadPendingRequests();
        } else {
            setButtonsEnabled(true, btnAccept, btnReject);
        }
    }

    private static void setButtonsEnabled(boolean enabled, MaterialButton btnAccept, MaterialButton btnReject) {
        btnAccept.setEnabled(enabled);
        btnReject.setEnabled(enabled);
    }

    private static String requesterName(User requester) {
        String nickname = requester.getNickname();
        return nickname != null && !nickname.isEmpty() ? nickname : requester.getFullName();
    }
}
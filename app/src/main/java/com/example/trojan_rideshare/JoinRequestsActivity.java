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
 * The central place for join requests (spec Feature 3):
 * - "Pending Join Requests" (UCS-2.3.9): Pending requests on every active ride the user organizes,
 *   which the organizer can accept or reject. The rides come from UserRideHistoryManager.getHistory and
 *   each ride's requests from JoinRequestManager.getRideRequests.
 * - "Join Ride Requests" (UCS-2.3.8): every request the user sent, with a Cancel button while Pending.
 */
public class JoinRequestsActivity extends AppCompatActivity {

    private static final String DATE_FORMAT = "EEE, MMM d, yyyy · h:mm a";

    private ProgressBar progressBar;
    private TextView tvPendingMessage;
    private LinearLayout pendingContainer;
    private TextView tvMyRequestsMessage;
    private LinearLayout myRequestsContainer;

    private AuthManager authManager;
    private UserRideHistoryManager historyManager;
    private JoinRequestManager joinRequestManager;

    private int loadsInProgress;

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
        tvMyRequestsMessage = findViewById(R.id.tvMyRequestsMessage);
        myRequestsContainer = findViewById(R.id.myRequestsContainer);
        TextView tvBack = findViewById(R.id.tvBack);
        tvBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (historyManager != null) {
            loadAll();
        }
    }

    private void loadAll() {
        loadPendingRequests();
        loadMyRequests();
    }

    // ---- Pending Join Requests (organizer) ----

    private void loadPendingRequests() {
        User user = authManager.getCurrentUser();
        startLoading();
        historyManager.getHistory(rides -> {
            if (rides == null) {
                finishLoading(true);
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
                showPendingRequests(new ArrayList<>());
                finishLoading(false);
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
                        if (!failed[0]) {
                            JoinRequestManager.sortNewestFirst(pending);
                            showPendingRequests(pending);
                        }
                        finishLoading(failed[0]);
                    }
                });
            }
        });
    }

    private void showPendingRequests(List<JoinRequest> requests) {
        pendingContainer.removeAllViews();
        showEmptyMessage(tvPendingMessage, requests.isEmpty(), R.string.no_pending_join_requests);

        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT, Locale.US);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (JoinRequest request : requests) {
            Ride ride = request.getRide();
            View row = inflater.inflate(R.layout.item_join_request, pendingContainer, false);

            ((TextView) row.findViewById(R.id.tvRequester)).setText(displayName(request.getRequester()));
            ((TextView) row.findViewById(R.id.tvRideTitle)).setText(ride.getTitle());
            ((TextView) row.findViewById(R.id.tvRoute)).setText(getString(R.string.label_route,
                    ride.getOrigin().getPOIName(), ride.getDestination().getPOIName()));
            ((TextView) row.findViewById(R.id.tvDeparture)).setText(format.format(ride.getDepartureTime()));
            ((TextView) row.findViewById(R.id.tvSeats)).setText(getString(R.string.label_riders_count,
                    ride.getNumOfRiders(), ride.getMaxRiders()));
            showRequestedAndNote(row, request, format);

            MaterialButton btnAccept = row.findViewById(R.id.btnAccept);
            MaterialButton btnReject = row.findViewById(R.id.btnReject);
            btnAccept.setOnClickListener(v -> accept(request, btnAccept, btnReject));
            btnReject.setOnClickListener(v -> confirmReject(request, btnAccept, btnReject));
            pendingContainer.addView(row);
        }
    }

    private void accept(JoinRequest request, MaterialButton btnAccept, MaterialButton btnReject) {
        setEnabled(false, btnAccept, btnReject);
        progressBar.setVisibility(View.VISIBLE);
        joinRequestManager.acceptJoinRequest(request, authManager.getCurrentUser(),
                success -> afterAction(success, R.string.msg_request_accepted, R.string.error_accept_request,
                        btnAccept, btnReject));
    }

    // A rejected rider can't request the ride again, so the organizer confirms first
    private void confirmReject(JoinRequest request, MaterialButton btnAccept, MaterialButton btnReject) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.confirm_reject_request, displayName(request.getRequester())))
                .setPositiveButton(R.string.action_yes, (dialog, which) -> {
                    setEnabled(false, btnAccept, btnReject);
                    progressBar.setVisibility(View.VISIBLE);
                    joinRequestManager.rejectJoinRequest(request, authManager.getCurrentUser(),
                            success -> afterAction(success, R.string.msg_request_rejected,
                                    R.string.error_reject_request, btnAccept, btnReject));
                })
                .setNegativeButton(R.string.action_no, null)
                .show();
    }

    // ---- Join Ride Requests (requester) ----

    private void loadMyRequests() {
        startLoading();
        joinRequestManager.getMyRequests(authManager.getCurrentUser(), requests -> {
            if (requests != null) {
                showMyRequests(requests);
            }
            finishLoading(requests == null);
        });
    }

    private void showMyRequests(List<JoinRequest> requests) {
        myRequestsContainer.removeAllViews();
        showEmptyMessage(tvMyRequestsMessage, requests.isEmpty(), R.string.no_join_ride_requests);

        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT, Locale.US);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (JoinRequest request : requests) {
            Ride ride = request.getRide();
            View row = inflater.inflate(R.layout.item_my_join_request, myRequestsContainer, false);

            ((TextView) row.findViewById(R.id.tvRideTitle)).setText(ride.getTitle());
            ((TextView) row.findViewById(R.id.tvOrganizer)).setText(getString(R.string.label_organizer,
                    displayName(ride.getOrganizer())));
            ((TextView) row.findViewById(R.id.tvRoute)).setText(getString(R.string.label_route,
                    ride.getOrigin().getPOIName(), ride.getDestination().getPOIName()));
            ((TextView) row.findViewById(R.id.tvDeparture)).setText(format.format(ride.getDepartureTime()));
            ((TextView) row.findViewById(R.id.tvRequestStatus)).setText(getString(R.string.label_request_status,
                    RequestStatus.valueOf(request.getStatus()).toString()));
            showRequestedAndNote(row, request, format);

            // Spec Feature 3: a user can cancel a pending request
            if (RequestStatus.PENDING.name().equals(request.getStatus())) {
                MaterialButton btnCancel = row.findViewById(R.id.btnCancelRequest);
                btnCancel.setVisibility(View.VISIBLE);
                btnCancel.setOnClickListener(v -> cancel(request, btnCancel));
            }
            myRequestsContainer.addView(row);
        }
    }

    private void cancel(JoinRequest request, MaterialButton btnCancel) {
        setEnabled(false, btnCancel);
        progressBar.setVisibility(View.VISIBLE);
        joinRequestManager.cancelJoinRequest(request, authManager.getCurrentUser(),
                success -> afterAction(success, R.string.msg_request_cancelled, R.string.error_cancel_request,
                        btnCancel));
    }

    // ---- Shared helpers ----

    // Reloads both lists after every action so statuses and seat counts stay current
    private void afterAction(boolean success, int successMessageId, int errorMessageId, MaterialButton... buttons) {
        progressBar.setVisibility(View.GONE);
        Toast.makeText(this, success ? successMessageId : errorMessageId, Toast.LENGTH_LONG).show();
        if (success) {
            loadAll();
        } else {
            setEnabled(true, buttons);
        }
    }

    private void startLoading() {
        loadsInProgress++;
        progressBar.setVisibility(View.VISIBLE);
    }

    private void finishLoading(boolean failed) {
        loadsInProgress--;
        if (loadsInProgress <= 0) {
            loadsInProgress = 0;
            progressBar.setVisibility(View.GONE);
        }
        if (failed) {
            Toast.makeText(this, R.string.error_load_join_requests, Toast.LENGTH_LONG).show();
        }
    }

    private void showEmptyMessage(TextView message, boolean empty, int emptyTextId) {
        message.setText(empty ? getString(emptyTextId) : "");
        message.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void showRequestedAndNote(View row, JoinRequest request, SimpleDateFormat format) {
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
    }

    private static void setEnabled(boolean enabled, MaterialButton... buttons) {
        for (MaterialButton button : buttons) {
            button.setEnabled(enabled);
        }
    }

    private static String displayName(User user) {
        String nickname = user.getNickname();
        return nickname != null && !nickname.isEmpty() ? nickname : user.getFullName();
    }
}
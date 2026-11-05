package com.example.trojan_rideshare;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_ACCOUNT_CREATED = "accountCreated";
    private static final long ACCOUNT_MESSAGE_MS = 15_000;

    private AuthManager authManager;
    private User currentUser;

    private TextView tvFullName, tvGradYear;
    private TextInputEditText etEmailReadonly, etUscIdReadonly, etNickname;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideAccountMessage = () -> {
        View card = findViewById(R.id.cardInfo);
        if (card != null) {
            card.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenInsets.enable(this);
        setContentView(R.layout.activity_main);
        ScreenInsets.pad(findViewById(R.id.searchScroll));

        authManager = AuthManager.getInstance();
        currentUser = authManager.getCurrentUser();

        View cardInfo = findViewById(R.id.cardInfo);
        if (getIntent().getBooleanExtra(EXTRA_ACCOUNT_CREATED, false)) {
            cardInfo.setVisibility(View.VISIBLE);
            handler.postDelayed(hideAccountMessage, ACCOUNT_MESSAGE_MS);
        }

        MaterialButton btnMyProfile = findViewById(R.id.btnMyProfile);
        btnMyProfile.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, ProfileActivity.class)));

        MaterialButton btnCreateRide = findViewById(R.id.btnCreateRide);
        btnCreateRide.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, CreateRideActivity.class)));

        new RideSearchController(this).start();

        MaterialButton btnMyRides = findViewById(R.id.btnMyRides);
        btnMyRides.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, RideHistoryActivity.class)));

        MaterialButton btnJoinRequests = findViewById(R.id.btnJoinRequests);
        btnJoinRequests.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, JoinRequestsActivity.class)));

        View searchHeading = findViewById(R.id.tvSearchHeading);
        View searchSubtitle = findViewById(R.id.tvSearchSubtitle);
        View searchForm = findViewById(R.id.searchForm);
        MaterialButton btnSearchRides = findViewById(R.id.btnSearchRides);
        btnSearchRides.setOnClickListener(v -> {
            boolean show = searchForm.getVisibility() != View.VISIBLE;
            int visibility = show ? View.VISIBLE : View.GONE;
            searchHeading.setVisibility(visibility);
            searchSubtitle.setVisibility(visibility);
            searchForm.setVisibility(visibility);
            if (show) {
                searchForm.post(() -> {
                    ScrollView scroll = findViewById(R.id.searchScroll);
                    scroll.smoothScrollTo(0, searchHeading.getTop());
                });
            }
        });

        MaterialButton btnViewBalance = findViewById(R.id.btnViewBalance);
        btnViewBalance.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, BalanceActivity.class)));

        tvFullName = findViewById(R.id.tvFullName);
        tvGradYear = findViewById(R.id.tvGradYear);
        etEmailReadonly = findViewById(R.id.etEmailReadonly);
        etUscIdReadonly = findViewById(R.id.etUscIdReadonly);
        etNickname = findViewById(R.id.etNickname);
        MaterialButton btnUpdateNickname = findViewById(R.id.btnUpdateNickname);

        populateUserData();

        btnUpdateNickname.setOnClickListener(v -> updateNickname());

        MaterialButton btnLogout = findViewById(R.id.btnLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> {
                authManager.logoutUser("", "", "");
                Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                startActivity(intent);
                finish();
            });
        }
    }

    private void populateUserData() {
        if (currentUser != null) {
            tvFullName.setText(getString(R.string.label_full_name, currentUser.getFullName()));
            etEmailReadonly.setText(currentUser.getEmail());
            etUscIdReadonly.setText(String.valueOf(currentUser.getUscId()));
            tvGradYear.setText(getString(R.string.label_grad_year, currentUser.getGraduationYear()));
            etNickname.setText(currentUser.getNickname());
        } else {
            tvFullName.setText(getString(R.string.label_full_name, "Test User"));
            etEmailReadonly.setText("student@usc.edu");
            etUscIdReadonly.setText("1234567890");
            tvGradYear.setText(getString(R.string.label_grad_year, 2026));
            etNickname.setText("Trojan");
        }
    }

    private void updateNickname() {
        String newNickname = etNickname.getText() != null ? etNickname.getText().toString().trim() : "";
        if (newNickname.isEmpty()) {
            etNickname.setError("Nickname cannot be empty (defaults to full name if omitted)");
            return;
        }

        if (currentUser != null) {
            currentUser.updateNickname(newNickname);
        }
        Toast.makeText(this, "Displayed nickname updated to: " + newNickname, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hideAccountMessage);
        super.onDestroy();
    }
}

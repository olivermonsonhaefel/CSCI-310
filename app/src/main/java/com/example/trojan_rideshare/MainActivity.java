package com.example.trojan_rideshare;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    private AuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenInsets.enable(this);
        setContentView(R.layout.activity_main);
        ScreenInsets.pad(findViewById(R.id.searchScroll));

        authManager = AuthManager.getInstance();

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
}

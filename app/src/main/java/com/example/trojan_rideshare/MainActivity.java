package com.example.trojan_rideshare;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    private AuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        authManager = AuthManager.getInstance();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialButton btnCreateRide = findViewById(R.id.btnCreateRide);
        btnCreateRide.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, CreateRideActivity.class)));

        new RideSearchController(this).start();

        MaterialButton btnMyRides = findViewById(R.id.btnMyRides);
        btnMyRides.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, RideHistoryActivity.class)));

        MaterialButton btnJoinRequests = findViewById(R.id.btnJoinRequests);
        btnJoinRequests.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, JoinRequestsActivity.class)));

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

package com.example.trojan_rideshare;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Standalone search screen. The home page hosts the same form through {@link RideSearchController}.
 */
public class SearchRideActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenInsets.enable(this);
        setContentView(R.layout.activity_search_ride);
        ScreenInsets.pad(findViewById(R.id.searchScroll));

        if (AuthManager.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        new RideSearchController(this).start();
    }
}

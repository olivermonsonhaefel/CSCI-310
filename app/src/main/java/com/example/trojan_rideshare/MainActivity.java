package com.example.trojan_rideshare;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private AuthManager authManager;
    private User currentUser;

    private TextView tvFullName, tvGradYear;
    private TextInputEditText etEmailReadonly, etUscIdReadonly, etNickname;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        authManager = AuthManager.getInstance();
        currentUser = authManager.getCurrentUser();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        tvFullName = findViewById(R.id.tvFullName);
        tvGradYear = findViewById(R.id.tvGradYear);
        etEmailReadonly = findViewById(R.id.etEmailReadonly);
        etUscIdReadonly = findViewById(R.id.etUscIdReadonly);
        etNickname = findViewById(R.id.etNickname);
        MaterialButton btnUpdateNickname = findViewById(R.id.btnUpdateNickname);
        MaterialButton btnLogout = findViewById(R.id.btnLogout);

        populateUserData();

        btnUpdateNickname.setOnClickListener(v -> updateNickname());

        btnLogout.setOnClickListener(v -> {
            authManager.logoutUser("", "", "");
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
        });
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
}

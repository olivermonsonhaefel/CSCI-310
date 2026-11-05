package com.example.trojan_rideshare;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Calendar;

public class RegisterActivity extends AppCompatActivity {

    private TextInputLayout tilFullName, tilEmail, tilUscId, tilGradYear, tilNickname, tilPassword;
    private TextInputEditText etFullName, etEmail, etUscId, etGradYear, etNickname, etPassword;
    private MaterialButton btnRegister;
    private AuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenInsets.enable(this);
        setContentView(R.layout.activity_register);
        ScreenInsets.pad(findViewById(R.id.registerRoot));

        authManager = AuthManager.getInstance();

        tilFullName = findViewById(R.id.tilFullName);
        tilEmail = findViewById(R.id.tilEmail);
        tilUscId = findViewById(R.id.tilUscId);
        tilGradYear = findViewById(R.id.tilGradYear);
        tilNickname = findViewById(R.id.tilNickname);
        tilPassword = findViewById(R.id.tilPassword);

        etFullName = findViewById(R.id.etFullName);
        etEmail = findViewById(R.id.etEmail);
        etUscId = findViewById(R.id.etUscId);
        etGradYear = findViewById(R.id.etGradYear);
        etNickname = findViewById(R.id.etNickname);
        etPassword = findViewById(R.id.etPassword);
        btnRegister = findViewById(R.id.btnRegister);
        TextView tvLoginPrompt = findViewById(R.id.tvLoginPrompt);

        btnRegister.setOnClickListener(v -> validateAndRegister());
        tvLoginPrompt.setOnClickListener(v -> finish());
    }

    private void validateAndRegister() {
        tilFullName.setError(null);
        tilEmail.setError(null);
        tilUscId.setError(null);
        tilGradYear.setError(null);
        tilNickname.setError(null);
        tilPassword.setError(null);

        String fullName = etFullName.getText() != null ? etFullName.getText().toString().trim() : "";
        String email = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
        String uscIdStr = etUscId.getText() != null ? etUscId.getText().toString().trim() : "";
        String gradYearStr = etGradYear.getText() != null ? etGradYear.getText().toString().trim() : "";
        String nickname = etNickname.getText() != null ? etNickname.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        boolean hasError = false;

        if (fullName.length() < 3) {
            tilFullName.setError("Full name must be at least 3 characters");
            hasError = true;
        }

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError("Please enter a valid email address");
            hasError = true;
        }

        if (!uscIdStr.matches("\\d{10}")) {
            tilUscId.setError("USC ID must be exactly 10 digits");
            hasError = true;
        }
        long uscId = 0L;
        try {
            uscId = Long.parseLong(uscIdStr);
        } catch (NumberFormatException e) {
            // Handled by regex
        }

        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        int maxGradYear = currentYear + 6;
        int gradYear = 0;
        try {
            gradYear = Integer.parseInt(gradYearStr);
            if (gradYear < currentYear || gradYear > maxGradYear) {
                tilGradYear.setError("Graduation year must be between " + currentYear + " and " + maxGradYear + " (Active Student range)");
                hasError = true;
            }
        } catch (NumberFormatException e) {
            tilGradYear.setError("Graduation year must be a valid number");
            hasError = true;
        }

        if (password.length() < 6) {
            tilPassword.setError("Password must be at least 6 characters");
            hasError = true;
        }

        if (hasError) {
            return;
        }

        if (nickname.isEmpty()) {
            Toast.makeText(this, "Nickname omitted. Defaulting to full name: " + fullName, Toast.LENGTH_LONG).show();
        }

        btnRegister.setEnabled(false);

        authManager.registerUser(fullName, email, uscId, gradYear, password, nickname, success -> {
            btnRegister.setEnabled(true);
            if (success) {
                Toast.makeText(this, "Account created successfully!", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                intent.putExtra(MainActivity.EXTRA_ACCOUNT_CREATED, true);
                startActivity(intent);
                finish();
            } else {
                Toast.makeText(this, "Registration failed. Email may already be in use.", Toast.LENGTH_LONG).show();
            }
        });
    }
}

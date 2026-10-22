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

public class RegisterActivity extends AppCompatActivity {

    private TextInputLayout tilFirstName, tilLastName, tilEmail, tilPassword;
    private TextInputEditText etFirstName, etLastName, etEmail, etPassword;
    private MaterialButton btnRegister;
    private AuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        authManager = AuthManager.getInstance();

        tilFirstName = findViewById(R.id.tilFirstName);
        tilLastName = findViewById(R.id.tilLastName);
        tilEmail = findViewById(R.id.tilEmail);
        tilPassword = findViewById(R.id.tilPassword);

        etFirstName = findViewById(R.id.etFirstName);
        etLastName = findViewById(R.id.etLastName);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnRegister = findViewById(R.id.btnRegister);
        TextView tvLoginPrompt = findViewById(R.id.tvLoginPrompt);

        btnRegister.setOnClickListener(v -> validateAndRegister());
        tvLoginPrompt.setOnClickListener(v -> finish());
    }

    private void validateAndRegister() {
        // Clear previous errors
        tilFirstName.setError(null);
        tilLastName.setError(null);
        tilEmail.setError(null);
        tilPassword.setError(null);

        String fname = etFirstName.getText() != null ? etFirstName.getText().toString().trim() : "";
        String lname = etLastName.getText() != null ? etLastName.getText().toString().trim() : "";
        String email = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        boolean isValid = true;

        if (fname.length() < 2) {
            tilFirstName.setError("First name must be at least 2 characters");
            isValid = false;
        }

        if (lname.length() < 2) {
            tilLastName.setError("Last name must be at least 2 characters");
            isValid = false;
        }

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError("Please enter a valid email address");
            isValid = false;
        }

        if (password.length() < 6) {
            tilPassword.setError("Password must be at least 6 characters");
            isValid = false;
        }

        if (!isValid) {
            return;
        }

        btnRegister.setEnabled(false);

        authManager.registerUser(fname, lname, email, password, success -> {
            btnRegister.setEnabled(true);
            if (success) {
                Toast.makeText(this, "Account created & added to database successfully!", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                startActivity(intent);
                finish();
            } else {
                Toast.makeText(this, "Registration failed. Email may already be in use.", Toast.LENGTH_LONG).show();
            }
        });
    }
}

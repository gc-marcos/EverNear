package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    private EditText etEmailPhone;
    private EditText etPassword;
    private Button btnLogin;
    private TextView tvCreateAccount;

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private String userType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        userType = getIntent().getStringExtra("userType");

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        etEmailPhone = findViewById(R.id.et_email_phone);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        tvCreateAccount = findViewById(R.id.tv_create_account);

        btnLogin.setOnClickListener(v -> attemptLogin());
        tvCreateAccount.setOnClickListener(v -> openCreateAccount());
    }

    private void attemptLogin() {
        String email = etEmailPhone.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty()) {
            etEmailPhone.setError(getString(R.string.login_hint_email_phone));
            etEmailPhone.requestFocus();
            return;
        }

        if (password.isEmpty()) {
            etPassword.setError(getString(R.string.login_hint_password));
            etPassword.requestFocus();
            return;
        }

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "signInWithEmail:success");
                            FirebaseUser user = mAuth.getCurrentUser();
                            saveUserRole(user);
                        } else {
                            Log.w(TAG, "signInWithEmail:failure", task.getException());
                            Toast.makeText(LoginActivity.this, "Authentication failed.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void saveUserRole(FirebaseUser firebaseUser) {
        if (firebaseUser != null && userType != null) {
            String userId = firebaseUser.getUid();
            Map<String, Object> user = new HashMap<>();
            user.put("role", userType);

            db.collection("users").document(userId)
                    .set(user)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "DocumentSnapshot successfully written!");
                        if ("patient".equals(userType)) {
                            Intent intent = new Intent(LoginActivity.this, PatientActivity.class);
                            startActivity(intent);
                        } else if ("caregiver".equals(userType)) {
                            Intent intent = new Intent(LoginActivity.this, CaregiverActivity.class);
                            startActivity(intent);
                        }
                    })
                    .addOnFailureListener(e -> Log.w(TAG, "Error writing document", e));
        }
    }


    private void openCreateAccount() {
        String email = etEmailPhone.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty()) {
            etEmailPhone.setError(getString(R.string.login_hint_email_phone));
            etEmailPhone.requestFocus();
            return;
        }

        if (password.isEmpty()) {
            etPassword.setError(getString(R.string.login_hint_password));
            etPassword.requestFocus();
            return;
        }

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "createUserWithEmail:success");
                            FirebaseUser user = mAuth.getCurrentUser();
                            saveUserRole(user);
                        } else {
                            Log.w(TAG, "createUserWithEmail:failure", task.getException());
                            Toast.makeText(LoginActivity.this, "Authentication failed.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }
}
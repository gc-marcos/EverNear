package com.marcoscarvalho.evernear;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {

    private EditText etEmailPhone;
    private EditText etPassword;
    private Button btnLogin;
    private TextView tvCreateAccount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etEmailPhone = findViewById(R.id.et_email_phone);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        tvCreateAccount = findViewById(R.id.tv_create_account);

        btnLogin.setOnClickListener(v -> attemptLogin());
        tvCreateAccount.setOnClickListener(v -> openCreateAccount());
    }

    private void attemptLogin() {
        String emailPhone = etEmailPhone.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (emailPhone.isEmpty()) {
            etEmailPhone.setError(getString(R.string.login_hint_email_phone));
            etEmailPhone.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            etPassword.setError(getString(R.string.login_hint_password));
            etPassword.requestFocus();
            return;
        }

        // TODO: integrar com Firebase Auth
        Toast.makeText(this, getString(R.string.login_button_entrar) + " em desenvolvimento", Toast.LENGTH_SHORT).show();
    }

    private void openCreateAccount() {
        // TODO: navegar para tela de criar conta
        Toast.makeText(this, getString(R.string.login_first_access), Toast.LENGTH_SHORT).show();
    }
}

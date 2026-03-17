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
                            if (user != null) {
                                db.collection("users").document(user.getUid()).get()
                                        .addOnSuccessListener(documentSnapshot -> {
                                            if (documentSnapshot.exists()) {
                                                String tipo = documentSnapshot.getString("tipo");
                                                String codigoVinculo = documentSnapshot.getString("codigoVinculo");
                                                direcionarUsuario(tipo, codigoVinculo);
                                            } else {
                                                salvarNovoUsuario(user);
                                            }
                                        })
                                        .addOnFailureListener(e -> {
                                            Toast.makeText(LoginActivity.this, "Erro ao acessar banco de dados: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                        });
                            }
                        } else {
                            String errorMsg = task.getException() != null ? task.getException().getMessage() : "Erro desconhecido";
                            Log.w(TAG, "signInWithEmail:failure", task.getException());
                            Toast.makeText(LoginActivity.this, "Falha na autenticação: " + errorMsg,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    /**
     * Direciona o usuário para a tela correta após login ou cadastro.
     * Para pacientes, passa o codigoVinculo via Intent para exibição imediata.
     */
    private void direcionarUsuario(String tipo, String codigoVinculo) {
        if ("patient".equals(tipo) || "paciente".equals(tipo)) {
            Intent intent = new Intent(LoginActivity.this, DashboardPacienteActivity.class);
            if (codigoVinculo != null && !codigoVinculo.isEmpty()) {
                intent.putExtra("codigoVinculo", codigoVinculo);
            }
            startActivity(intent);
        } else {
            startActivity(new Intent(LoginActivity.this, DashboardCuidadorActivity.class));
        }
        finish();
    }

    private void salvarNovoUsuario(FirebaseUser firebaseUser) {
        if (firebaseUser == null || userType == null) return;

        String userId = firebaseUser.getUid();
        String nome = "Usuário " + userId.substring(0, 4);

        FirebaseHelper.salvarUsuario(userId, nome, firebaseUser.getEmail(), userType,
                new FirebaseHelper.Callback<String>() {
                    @Override
                    public void onResult(String codigoVinculo) {
                        // codigoVinculo é o código gerado para paciente, ou null para cuidador
                        direcionarUsuario(userType, codigoVinculo);
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Erro ao salvar usuário: ", e);
                        Toast.makeText(LoginActivity.this, "Erro ao salvar dados: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
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
                            if (user != null) {
                                salvarNovoUsuario(user);
                            }
                        } else {
                            Log.w(TAG, "createUserWithEmail:failure", task.getException());
                            String errorMsg = "Erro desconhecido";
                            if (task.getException() != null) {
                                errorMsg = task.getException().getLocalizedMessage();
                                Log.e(TAG, "Erro detalhado: ", task.getException());
                            }
                            Toast.makeText(LoginActivity.this, "Falha no cadastro: " + errorMsg,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }
}

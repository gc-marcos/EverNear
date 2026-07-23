package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    private EditText  etNome;
    private EditText  etTelefone;   // apenas paciente no modo cadastro
    private EditText  etEmailPhone;
    private EditText  etPassword;
    private Button    btnLogin;
    private TextView  tvCreateAccount;

    private FirebaseAuth      mAuth;
    private FirebaseFirestore db;
    private String            userType;
    private boolean           isModoCadastro = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        userType = getIntent().getStringExtra("userType");

        mAuth = FirebaseAuth.getInstance();
        db    = FirebaseFirestore.getInstance();

        etNome        = findViewById(R.id.et_nome);
        etTelefone    = findViewById(R.id.et_telefone);
        etEmailPhone  = findViewById(R.id.et_email_phone);
        etPassword    = findViewById(R.id.et_password);
        btnLogin      = findViewById(R.id.btn_login);
        tvCreateAccount = findViewById(R.id.tv_create_account);

        btnLogin.setOnClickListener(v -> {
            if (isModoCadastro) realizarCadastro();
            else realizarLogin();
        });

        tvCreateAccount.setOnClickListener(v -> alternarModo());
    }

    // ==================== Alternar login / cadastro ====================

    private void alternarModo() {
        isModoCadastro = !isModoCadastro;

        if (isModoCadastro) {
            etNome.setVisibility(View.VISIBLE);
            etNome.requestFocus();

            // Campo de telefone só aparece para pacientes
            etTelefone.setVisibility(
                    FirebaseHelper.isPaciente(userType) ? View.VISIBLE : View.GONE);

            btnLogin.setText("CADASTRAR");
            tvCreateAccount.setText("Já tenho conta → Entrar");
        } else {
            etNome.setVisibility(View.GONE);
            etNome.setText("");
            etTelefone.setVisibility(View.GONE);
            etTelefone.setText("");
            btnLogin.setText("ENTRAR");
            tvCreateAccount.setText("Primeiro acesso? Criar conta");
        }
    }

    // ==================== Login ====================

    private void realizarLogin() {
        String email    = etEmailPhone.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty()) {
            etEmailPhone.setError("Informe o e-mail");
            etEmailPhone.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            etPassword.setError("Informe a senha");
            etPassword.requestFocus();
            return;
        }

        // Lambda em vez de OnCompleteListener anônimo — consistente com o restante do projeto
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            db.collection("users").document(user.getUid()).get()
                                    .addOnSuccessListener(doc -> {
                                        if (doc.exists()) {
                                            direcionarAposLogin(doc.getString(
                                                    FirebaseHelper.Fields.TIPO));
                                        } else {
                                            criarPerfilFirestore(user, "Usuário", null);
                                        }
                                    })
                                    .addOnFailureListener(e ->
                                            Toast.makeText(LoginActivity.this,
                                                    "Erro ao acessar banco: " + e.getMessage(),
                                                    Toast.LENGTH_LONG).show());
                        }
                    } else {
                        String msg = task.getException() != null
                                ? task.getException().getMessage() : "Erro desconhecido";
                        Log.w(TAG, "signInWithEmail:failure", task.getException());
                        Toast.makeText(LoginActivity.this,
                                "Falha no login: " + msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ==================== Cadastro ====================

    private void realizarCadastro() {
        String  nome       = etNome.getText().toString().trim();
        String  email      = etEmailPhone.getText().toString().trim();
        String  password   = etPassword.getText().toString().trim();
        boolean ehPaciente = FirebaseHelper.isPaciente(userType);
        String  telefone   = ehPaciente ? etTelefone.getText().toString().trim() : null;

        // Validações
        if (nome.isEmpty()) {
            etNome.setError("Informe seu nome completo");
            etNome.requestFocus();
            return;
        }
        if (nome.length() < 2) {
            etNome.setError("Nome deve ter ao menos 2 caracteres");
            etNome.requestFocus();
            return;
        }

        // Telefone obrigatório apenas para pacientes
        if (ehPaciente) {
            if (telefone == null || telefone.isEmpty()) {
                etTelefone.setError("Informe o telefone do paciente");
                etTelefone.requestFocus();
                return;
            }
            // Remove formatação para validar apenas dígitos (mínimo 10 dígitos: DDD + número)
            String apenasDigitos = telefone.replaceAll("[^0-9]", "");
            if (apenasDigitos.length() < 10) {
                etTelefone.setError("Telefone inválido — inclua DDD e número");
                etTelefone.requestFocus();
                return;
            }
        }

        if (email.isEmpty()) {
            etEmailPhone.setError("Informe o e-mail");
            etEmailPhone.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            etPassword.setError("Informe a senha");
            etPassword.requestFocus();
            return;
        }
        if (password.length() < 6) {
            etPassword.setError("A senha deve ter pelo menos 6 caracteres");
            etPassword.requestFocus();
            return;
        }

        final String telefoneFinal = telefone;

        // Lambda em vez de OnCompleteListener anônimo — consistente com o restante do projeto
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            criarPerfilFirestore(user, nome, telefoneFinal);
                        }
                    } else {
                        Log.w(TAG, "createUserWithEmail:failure", task.getException());
                        String msg = task.getException() != null
                                ? task.getException().getLocalizedMessage() : "Erro desconhecido";
                        Toast.makeText(LoginActivity.this,
                                "Falha no cadastro: " + msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ==================== Salvar perfil ====================

    private void criarPerfilFirestore(FirebaseUser firebaseUser, String nome, String telefone) {
        if (firebaseUser == null || userType == null) return;

        FirebaseHelper.salvarUsuario(
                firebaseUser.getUid(),
                nome,
                firebaseUser.getEmail(),
                userType,
                telefone,
                new FirebaseHelper.Callback<String>() {
                    @Override
                    public void onResult(String codigoVinculo) {
                        direcionarAposLogin(userType);
                    }
                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Erro ao salvar perfil: ", e);
                        Toast.makeText(LoginActivity.this,
                                "Erro ao salvar perfil: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ==================== Navegação ====================

    /**
     * Após login/cadastro bem-sucedido, popula o cache local do BootReceiver e navega
     * para SetupPermissoesActivity.
     *
     * O cache garante que o BootReceiver consiga iniciar o serviço correto mesmo offline
     * (sem depender de uma consulta ao Firestore no momento do boot).
     */
    private void direcionarAposLogin(String tipo) {
        // Popula o cache local para que o BootReceiver funcione mesmo sem rede no boot
        BootReceiver.salvarTipoAposLogin(this, tipo);

        Intent setup = new Intent(LoginActivity.this, SetupPermissoesActivity.class);
        setup.putExtra("userType", tipo);
        // FLAG_ACTIVITY_CLEAR_TASK: remove LoginActivity da pilha de navegação
        // para que o botão Voltar não retorne à tela de login após o setup
        setup.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(setup);
        finish();
    }
}

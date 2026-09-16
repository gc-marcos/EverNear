package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

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
        if (DeviceClassifier.isPacienteDevice(this)
                && !FirebaseHelper.isPaciente(userType)
                && !FirebaseHelper.isCuidador(userType)) {
            userType = "patient";
        }

        mAuth = FirebaseAuth.getInstance();
        db    = FirebaseFirestore.getInstance();

        etNome        = findViewById(R.id.et_nome);
        etTelefone    = findViewById(R.id.et_telefone);
        etEmailPhone  = findViewById(R.id.et_email_phone);
        etPassword    = findViewById(R.id.et_password);
        btnLogin      = findViewById(R.id.btn_login);
        tvCreateAccount = findViewById(R.id.tv_create_account);

        configurarAparenciaPorPerfil();

        btnLogin.setOnClickListener(v -> {
            if (isModoCadastro) realizarCadastro();
            else realizarLogin();
        });

        tvCreateAccount.setOnClickListener(v -> alternarModo());
    }

    /**
     * A mesma Activity atende os dois perfis. Apenas o estado visual e a seta
     * mudam de acordo com o tipo recebido da MainActivity.
     */
    private void configurarAparenciaPorPerfil() {
        TextView roleView = findViewById(R.id.tv_app_name);
        ImageView backView = findViewById(R.id.login_back_icon);
        boolean ehCuidador = FirebaseHelper.isCuidador(userType);

        if (ehCuidador) {
            roleView.setText(R.string.login_role_caregiver);
            roleView.setTextColor(ContextCompat.getColor(
                    this, R.color.login_role_caregiver_text));
            roleView.setBackgroundResource(R.drawable.bg_login_role_caregiver);
            roleView.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(this, R.drawable.ic_login_caregiver),
                    null, null, null);
            btnLogin.setBackgroundResource(R.drawable.btn_login_caregiver);
        } else {
            roleView.setText(R.string.login_role_patient);
            roleView.setTextColor(ContextCompat.getColor(
                    this, R.color.login_role_patient_text));
            roleView.setBackgroundResource(R.drawable.bg_login_role_patient);
            roleView.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(this, R.drawable.ic_login_patient),
                    null, null, null);
            btnLogin.setBackgroundResource(R.drawable.btn_login_primary);
        }

        atualizarTextoConta();

        boolean ocultarSeta = DeviceClassifier.isPacienteDevice(this) && !ehCuidador;
        backView.setVisibility(ocultarSeta ? View.GONE : View.VISIBLE);
        if (!ocultarSeta) {
            backView.setOnClickListener(v -> finish());
        }
    }

    // ==================== Alternar login / cadastro ====================

    private void alternarModo() {
        isModoCadastro = !isModoCadastro;

        if (isModoCadastro) {
            etNome.setVisibility(View.VISIBLE);
            findViewById(R.id.tv_nome_label).setVisibility(View.VISIBLE);
            etNome.requestFocus();

            // Campo de telefone só aparece para pacientes
            boolean ehPaciente = FirebaseHelper.isPaciente(userType);
            etTelefone.setVisibility(ehPaciente ? View.VISIBLE : View.GONE);
            findViewById(R.id.tv_telefone_label).setVisibility(
                    ehPaciente ? View.VISIBLE : View.GONE);

            btnLogin.setText(R.string.login_button_cadastrar);
            atualizarTextoConta();
        } else {
            etNome.setVisibility(View.GONE);
            etNome.setText("");
            findViewById(R.id.tv_nome_label).setVisibility(View.GONE);
            etTelefone.setVisibility(View.GONE);
            etTelefone.setText("");
            findViewById(R.id.tv_telefone_label).setVisibility(View.GONE);
            btnLogin.setText(R.string.login_button_entrar);
            atualizarTextoConta();
        }
    }

    /**
     * Mantém a parte acionável do texto na mesma cor do perfil selecionado,
     * sem alterar o TextView nem o listener usado para alternar o modo.
     */
    private void atualizarTextoConta() {
        int textoColorido = ContextCompat.getColor(
                this,
                FirebaseHelper.isCuidador(userType)
                        ? R.color.login_role_caregiver_text
                        : R.color.login_role_patient_text);
        String texto = getString(isModoCadastro
                ? R.string.login_have_account
                : R.string.login_first_access);
        String acao = getString(isModoCadastro
                ? R.string.login_sign_in_action
                : R.string.login_create_account_action);

        SpannableString textoFormatado = new SpannableString(texto);
        int inicioAcao = texto.lastIndexOf(acao);
        if (inicioAcao >= 0) {
            textoFormatado.setSpan(
                    new ForegroundColorSpan(textoColorido),
                    inicioAcao,
                    inicioAcao + acao.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvCreateAccount.setText(textoFormatado);
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
                                                    FirebaseHelper.Fields.TIPO), false);
                                        } else {
                                            criarPerfilFirestore(user, "Usuário", null, true);
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
                            criarPerfilFirestore(user, nome, telefoneFinal, true);
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

    private void criarPerfilFirestore(FirebaseUser firebaseUser, String nome, String telefone,
                                      boolean primeiroAcesso) {
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
                        direcionarAposLogin(userType, primeiroAcesso);
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
     * para o setup somente no primeiro acesso. Em logins posteriores, abre diretamente
     * a tela principal do perfil.
     */
    private void direcionarAposLogin(String tipo, boolean primeiroAcesso) {
        // Popula o cache local para que o BootReceiver funcione mesmo sem rede no boot
        BootReceiver.salvarTipoAposLogin(this, tipo);

        Intent destino;
        if (primeiroAcesso) {
            destino = new Intent(LoginActivity.this, SetupPermissoesActivity.class);
            destino.putExtra("userType", tipo);
        } else {
            boolean ehPaciente = FirebaseHelper.isPaciente(tipo);
            destino = new Intent(LoginActivity.this,
                    ehPaciente ? PatientActivity.class : CaregiverActivity.class);
        }

        // Remove LoginActivity da pilha para que o botão Voltar não retorne à tela de login.
        destino.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(destino);
        finish();
    }
}

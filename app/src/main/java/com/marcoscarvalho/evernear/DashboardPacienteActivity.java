package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

public class DashboardPacienteActivity extends AppCompatActivity {

    private TextView tvWelcome, tvCodigo, tvStatus;
    private EditText etApelido;
    private Button btnSalvarApelido, btnShare;

    private FirebaseFirestore db;
    private String uid;
    private ListenerRegistration listenerRegistration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard_paciente);

        tvWelcome = findViewById(R.id.tv_welcome_paciente);
        tvCodigo = findViewById(R.id.tv_codigo_vinculo);
        tvStatus = findViewById(R.id.tv_status_vinculo);
        etApelido = findViewById(R.id.et_apelido_paciente);
        btnSalvarApelido = findViewById(R.id.btn_salvar_apelido_paciente);
        btnShare = findViewById(R.id.btn_share_codigo);

        db = FirebaseFirestore.getInstance();
        uid = FirebaseAuth.getInstance().getUid();

        // Exibe o código imediatamente se foi passado pela Intent
        String codigoViaIntent = getIntent().getStringExtra("codigoVinculo");
        if (codigoViaIntent != null && !codigoViaIntent.isEmpty()) {
            tvCodigo.setText(codigoViaIntent);
            configurarBotaoCompartilhar(codigoViaIntent);
        }

        btnSalvarApelido.setOnClickListener(v -> salvarApelido());

        iniciarListener();
    }

    private void iniciarListener() {
        if (uid == null) return;

        listenerRegistration = db.collection("users").document(uid)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null || snapshot == null || !snapshot.exists()) return;

                    String nome = snapshot.getString("nome");
                    String apelido = snapshot.getString("apelido");
                    String codigo = snapshot.getString("codigoVinculo");
                    String cuidadorId = snapshot.getString("cuidadorVinculado");

                    // Boas-vindas com apelido ou nome real
                    String exibir = (apelido != null && !apelido.isEmpty()) ? apelido : nome;
                    if (exibir != null) tvWelcome.setText("Olá, " + exibir);

                    // Preenche campo de apelido se ainda vazio
                    if (apelido != null && !apelido.isEmpty() && etApelido.getText().toString().isEmpty()) {
                        etApelido.setText(apelido);
                    }

                    // Exibe o código de vínculo
                    if (codigo != null && !codigo.isEmpty()) {
                        tvCodigo.setText(codigo);
                        configurarBotaoCompartilhar(codigo);
                    } else {
                        // Gera e salva um novo código se ausente
                        String novoCodigo = FirebaseHelper.gerarCodigoVinculo();
                        db.collection("users").document(uid).update("codigoVinculo", novoCodigo);
                    }

                    // Status do vínculo com cuidador
                    if (cuidadorId != null && !cuidadorId.isEmpty()) {
                        buscarNomeCuidador(cuidadorId);
                    } else {
                        tvStatus.setText("Compartilhe este código com seu cuidador");
                        tvStatus.setTextColor(android.graphics.Color.parseColor("#9AA4B2"));
                    }
                });
    }

    private void configurarBotaoCompartilhar(String codigo) {
        btnShare.setOnClickListener(v -> {
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_TEXT, "Meu código de vínculo EverNear: " + codigo);
            sendIntent.setType("text/plain");
            startActivity(Intent.createChooser(sendIntent, "Compartilhar via"));
        });
    }

    private void salvarApelido() {
        String apelido = etApelido.getText().toString().trim();
        if (apelido.isEmpty()) {
            Toast.makeText(this, "Digite um apelido primeiro", Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseHelper.salvarApelido(uid, apelido, new FirebaseHelper.Callback<Void>() {
            @Override
            public void onResult(Void result) {
                Toast.makeText(DashboardPacienteActivity.this, "Apelido salvo!", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onError(Exception e) {
                Toast.makeText(DashboardPacienteActivity.this, "Erro ao salvar apelido", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void buscarNomeCuidador(String cuidadorId) {
        db.collection("users").document(cuidadorId).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String apelido = doc.getString("apelido");
                        String nome = doc.getString("nome");
                        String exibir = (apelido != null && !apelido.isEmpty()) ? apelido : nome;
                        tvStatus.setText("Vinculado a: " + (exibir != null ? exibir : "Cuidador"));
                        tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listenerRegistration != null) listenerRegistration.remove();
    }
}

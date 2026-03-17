package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

public class DashboardPacienteActivity extends AppCompatActivity {

    private TextView tvWelcome, tvCodigo, tvStatus;
    private Button btnShare;
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
        btnShare = findViewById(R.id.btn_share_codigo);

        db = FirebaseFirestore.getInstance();
        uid = FirebaseAuth.getInstance().getUid();

        // Exibe o código imediatamente se foi passado pela Intent (sem depender de rede)
        String codigoViaIntent = getIntent().getStringExtra("codigoVinculo");
        if (codigoViaIntent != null && !codigoViaIntent.isEmpty()) {
            tvCodigo.setText(codigoViaIntent);
            tvStatus.setText("Compartilhe este código com seu cuidador");
            tvStatus.setTextColor(android.graphics.Color.parseColor("#9AA4B2"));
            configurarBotaoCompartilhar(codigoViaIntent);
        }

        // Inicia o listener do Firestore para manter tudo sincronizado
        iniciarListenerFirestore();
    }

    private void iniciarListenerFirestore() {
        if (uid == null) {
            Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_SHORT).show();
            return;
        }

        listenerRegistration = db.collection("users").document(uid)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null) {
                        // Erro silencioso — o código da Intent já está exibido
                        return;
                    }

                    if (snapshot != null && snapshot.exists()) {
                        String nome = snapshot.getString("nome");
                        String codigo = snapshot.getString("codigoVinculo");
                        String cuidadorId = snapshot.getString("cuidadorVinculado");

                        if (nome != null) {
                            tvWelcome.setText("Olá, " + nome);
                        }

                        if (codigo != null && !codigo.isEmpty()) {
                            // Atualiza a tela com o código confirmado pelo Firestore
                            tvCodigo.setText(codigo);
                            configurarBotaoCompartilhar(codigo);
                        } else {
                            // Código ausente: gera e salva um novo (o listener disparará novamente)
                            String novoCodigo = FirebaseHelper.gerarCodigoVinculo();
                            db.collection("users").document(uid)
                                    .update("codigoVinculo", novoCodigo);
                        }

                        if (cuidadorId != null && !cuidadorId.isEmpty()) {
                            buscarNomeCuidador(cuidadorId);
                        } else {
                            tvStatus.setText("Compartilhe este código com seu cuidador");
                            tvStatus.setTextColor(android.graphics.Color.parseColor("#9AA4B2"));
                        }
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

    private void buscarNomeCuidador(String cuidadorId) {
        db.collection("users").document(cuidadorId).get().addOnSuccessListener(doc -> {
            if (doc.exists()) {
                String nomeCuidador = doc.getString("nome");
                tvStatus.setText("Vinculado a: " + (nomeCuidador != null ? nomeCuidador : "Cuidador"));
                tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Remove o listener ao sair da tela para evitar vazamento de memória
        if (listenerRegistration != null) {
            listenerRegistration.remove();
        }
    }
}

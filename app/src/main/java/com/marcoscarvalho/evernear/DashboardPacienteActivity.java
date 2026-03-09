package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

public class DashboardPacienteActivity extends AppCompatActivity {

    private TextView tvWelcome, tvCodigo, tvStatus;
    private Button btnShare;
    private FirebaseFirestore db;
    private String uid;

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

        carregarDados();
    }

    private void carregarDados() {
        if (uid == null) {
            Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_SHORT).show();
            return;
        }
        
        db.collection("users").document(uid).get().addOnSuccessListener(documentSnapshot -> {
            if (documentSnapshot.exists()) {
                String nome = documentSnapshot.getString("nome");
                String codigo = documentSnapshot.getString("codigoVinculo");
                String cuidadorId = documentSnapshot.getString("cuidadorVinculado");

                if (nome != null) tvWelcome.setText("Olá, " + nome);
                
                if (codigo != null && !codigo.isEmpty()) {
                    tvCodigo.setText(codigo);
                } else {
                    // Se o código não existir, gera um novo e salva
                    String novoCodigo = FirebaseHelper.gerarCodigoVinculo();
                    db.collection("users").document(uid).update("codigoVinculo", novoCodigo);
                    tvCodigo.setText(novoCodigo);
                }
                tvCodigo.setTextSize(54);

                if (cuidadorId == null) {
                    tvStatus.setText("Compartilhe este código com seu cuidador");
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#9AA4B2"));
                } else {
                    buscarNomeCuidador(cuidadorId);
                }

                btnShare.setOnClickListener(v -> {
                    String codigoAtual = tvCodigo.getText().toString();
                    if (!codigoAtual.isEmpty() && !codigoAtual.equals("ABC123")) {
                        Intent sendIntent = new Intent();
                        sendIntent.setAction(Intent.ACTION_SEND);
                        sendIntent.putExtra(Intent.EXTRA_TEXT, "Meu código de vínculo EverNear: " + codigoAtual);
                        sendIntent.setType("text/plain");
                        startActivity(Intent.createChooser(sendIntent, "Compartilhar via"));
                    } else {
                        Toast.makeText(this, "Código ainda não gerado", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                Toast.makeText(this, "Perfil não encontrado no banco de dados", Toast.LENGTH_LONG).show();
            }
        }).addOnFailureListener(e -> {
            Toast.makeText(this, "Erro ao carregar dados: " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void buscarNomeCuidador(String cuidadorId) {
        db.collection("users").document(cuidadorId).get().addOnSuccessListener(doc -> {
            if (doc.exists()) {
                tvStatus.setText("Vinculado a: " + doc.getString("nome"));
            }
        });
    }
}

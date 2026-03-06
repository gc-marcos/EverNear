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
        if (uid == null) return;
        
        db.collection("users").document(uid).get().addOnSuccessListener(documentSnapshot -> {
            if (documentSnapshot.exists()) {
                String nome = documentSnapshot.getString("nome");
                String codigo = documentSnapshot.getString("codigoVinculo");
                String cuidadorId = documentSnapshot.getString("cuidadorVinculado");

                if (nome != null) tvWelcome.setText("Olá, " + nome);
                if (codigo != null) {
                    tvCodigo.setText(codigo);
                    tvCodigo.setTextSize(54); // Garante que o código seja grande e visível
                }

                if (cuidadorId == null) {
                    tvStatus.setText("Compartilhe este código com seu cuidador");
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#9AA4B2"));
                } else {
                    buscarNomeCuidador(cuidadorId);
                }

                btnShare.setOnClickListener(v -> {
                    if (codigo != null) {
                        Intent sendIntent = new Intent();
                        sendIntent.setAction(Intent.ACTION_SEND);
                        sendIntent.putExtra(Intent.EXTRA_TEXT, "Meu código de vínculo EverNear: " + codigo);
                        sendIntent.setType("text/plain");
                        startActivity(Intent.createChooser(sendIntent, "Compartilhar via"));
                    }
                });
            }
        }).addOnFailureListener(e -> {
            Toast.makeText(this, "Erro ao carregar dados: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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

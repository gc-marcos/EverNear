package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.List;

public class CaregiverActivity extends AppCompatActivity {

    private TextView tvPatientName;
    private TextView tvAvatarInitials;
    private TextView tvBpmValue;

    private FirebaseFirestore db;
    private String uidCuidador;
    private ListenerRegistration listenerRegistration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_caregiver);

        tvPatientName = findViewById(R.id.tv_patient_name);
        tvAvatarInitials = findViewById(R.id.tv_avatar_initials);
        tvBpmValue = findViewById(R.id.tv_bpm_value);

        db = FirebaseFirestore.getInstance();
        uidCuidador = FirebaseAuth.getInstance().getUid();

        // Botão de voltar (iv_back)
        View ivBack = findViewById(R.id.iv_back);
        if (ivBack != null) {
            ivBack.setOnClickListener(v -> finish());
        }

        carregarDadosCuidador();
    }

    private void carregarDadosCuidador() {
        if (uidCuidador == null) return;

        listenerRegistration = db.collection("users").document(uidCuidador)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null || snapshot == null || !snapshot.exists()) return;

                    List<String> pacientesUids = (List<String>) snapshot.get("pacientesVinculados");

                    if (pacientesUids == null || pacientesUids.isEmpty()) {
                        // Sem pacientes vinculados → redireciona para vincular
                        tvPatientName.setText("Nenhum paciente vinculado");
                        tvAvatarInitials.setText("?");
                        // Abre DashboardCuidadorActivity automaticamente para vincular o primeiro paciente
                        startActivity(new Intent(CaregiverActivity.this, DashboardCuidadorActivity.class));
                    } else {
                        // Carrega os dados do primeiro paciente vinculado
                        carregarDadosPaciente(pacientesUids.get(0));
                    }
                });
    }

    private void carregarDadosPaciente(String uidPaciente) {
        db.collection("users").document(uidPaciente).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String nome = doc.getString("nome");
                        String apelido = doc.getString("apelido");
                        String exibir = (apelido != null && !apelido.isEmpty()) ? apelido : nome;

                        if (exibir != null) {
                            tvPatientName.setText(exibir);
                            // Iniciais para o avatar (até 2 caracteres)
                            String iniciais = gerarIniciais(exibir);
                            tvAvatarInitials.setText(iniciais);
                        }
                    }
                });
    }

    private String gerarIniciais(String nome) {
        if (nome == null || nome.isEmpty()) return "?";
        String[] partes = nome.trim().split("\\s+");
        if (partes.length == 1) return partes[0].substring(0, Math.min(2, partes[0].length())).toUpperCase();
        return (partes[0].charAt(0) + "" + partes[partes.length - 1].charAt(0)).toUpperCase();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listenerRegistration != null) {
            listenerRegistration.remove();
        }
    }
}

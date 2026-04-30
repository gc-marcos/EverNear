package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CaregiverActivity extends AppCompatActivity {

    private TextView tvPatientName;
    private TextView tvAvatarInitials;
    private TextView tvBpmValue;

    private FirebaseFirestore db;
    private String uidCuidador;
    private ListenerRegistration cuidadorListener;
    private ListenerRegistration pacienteListener;
    private ListenerRegistration alertasListener;

    private long appStartTimestamp;
    // Evita exibir o mesmo alerta duas vezes
    private final Map<String, Boolean> alertasJaExibidos = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_caregiver);

        tvPatientName = findViewById(R.id.tv_patient_name);
        tvAvatarInitials = findViewById(R.id.tv_avatar_initials);
        tvBpmValue = findViewById(R.id.tv_bpm_value);

        db = FirebaseFirestore.getInstance();
        uidCuidador = FirebaseAuth.getInstance().getUid();
        appStartTimestamp = System.currentTimeMillis();

        View ivBack = findViewById(R.id.iv_back);
        if (ivBack != null) ivBack.setOnClickListener(v -> finish());

        carregarDadosCuidador();
        ouvirAlertas();
    }

    private void carregarDadosCuidador() {
        if (uidCuidador == null) return;

        cuidadorListener = db.collection("users").document(uidCuidador)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null || snapshot == null || !snapshot.exists()) return;

                    @SuppressWarnings("unchecked")
                    List<String> pacientesUids = (List<String>) snapshot.get("pacientesVinculados");

                    if (pacientesUids == null || pacientesUids.isEmpty()) {
                        tvPatientName.setText("Nenhum paciente vinculado");
                        tvAvatarInitials.setText("?");
                        tvBpmValue.setText("--");
                        // Redireciona para vincular o primeiro paciente
                        startActivity(new Intent(CaregiverActivity.this, DashboardCuidadorActivity.class));
                    } else {
                        ouvirPaciente(pacientesUids.get(0));
                    }
                });
    }

    /**
     * Listener em tempo real do primeiro paciente vinculado.
     * Atualiza nome, iniciais e BPM continuamente.
     */
    private void ouvirPaciente(String uidPaciente) {
        // Remove listener anterior se trocou de paciente
        if (pacienteListener != null) pacienteListener.remove();

        pacienteListener = db.collection("users").document(uidPaciente)
                .addSnapshotListener((doc, e) -> {
                    if (e != null || doc == null || !doc.exists()) return;

                    String nome = doc.getString("nome");
                    String apelido = doc.getString("apelido");
                    String exibir = (apelido != null && !apelido.isEmpty()) ? apelido : nome;

                    if (exibir != null) {
                        tvPatientName.setText(exibir);
                        tvAvatarInitials.setText(gerarIniciais(exibir));
                    }

                    Long ultimoBpm = doc.getLong("ultimoBpm");
                    if (ultimoBpm != null) {
                        tvBpmValue.setText(ultimoBpm + " bpm");
                        // Cor baseada nos limites (se disponíveis)
                        Long bpmMin = doc.getLong("bpmMin");
                        Long bpmMax = doc.getLong("bpmMax");
                        int min = bpmMin != null ? bpmMin.intValue() : 50;
                        int max = bpmMax != null ? bpmMax.intValue() : 120;

                        if (ultimoBpm < min || ultimoBpm > max) {
                            tvBpmValue.setTextColor(Color.parseColor("#FF5252"));
                        } else {
                            tvBpmValue.setTextColor(Color.parseColor("#4CAF50"));
                        }
                    }
                });
    }

    /**
     * Listener em tempo real para alertas direcionados a este cuidador.
     * Mostra um diálogo a cada novo alerta recebido após a abertura da tela.
     */
    private void ouvirAlertas() {
        if (uidCuidador == null) return;

        alertasListener = db.collection("alerts")
                .whereEqualTo("cuidadorId", uidCuidador)
                .whereEqualTo("acknowledged", false)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;

                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() != DocumentChange.Type.ADDED) continue;

                        String alertaId = dc.getDocument().getId();
                        if (alertasJaExibidos.containsKey(alertaId)) continue;
                        alertasJaExibidos.put(alertaId, true);

                        // Ignora alertas antigos (anteriores à abertura da tela)
                        Timestamp ts = dc.getDocument().getTimestamp("timestamp");
                        if (ts != null && ts.toDate().getTime() < appStartTimestamp - 5_000) continue;

                        String nomePaciente = dc.getDocument().getString("pacienteNome");
                        Long bpm = dc.getDocument().getLong("bpm");
                        String tipo = dc.getDocument().getString("tipo");

                        exibirAlerta(alertaId, nomePaciente, bpm != null ? bpm.intValue() : 0, tipo);
                    }
                });
    }

    private void exibirAlerta(String alertaId, String paciente, int bpm, String tipo) {
        String titulo;
        if ("MANUAL".equals(tipo)) {
            titulo = "🚨 EMERGÊNCIA acionada";
        } else if ("HIGH".equals(tipo)) {
            titulo = "❤️ Frequência ALTA";
        } else {
            titulo = "💙 Frequência BAIXA";
        }

        String msg = (paciente != null ? paciente : "Paciente")
                + "\n\nBPM: " + bpm
                + "\nTipo: " + tipo;

        new AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Confirmar recebimento", (dialog, which) -> {
                    db.collection("alerts").document(alertaId).update("acknowledged", true);
                    Toast.makeText(this, "Alerta confirmado", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private String gerarIniciais(String nome) {
        if (nome == null || nome.isEmpty()) return "?";
        String[] partes = nome.trim().split("\\s+");
        if (partes.length == 1) {
            return partes[0].substring(0, Math.min(2, partes[0].length())).toUpperCase();
        }
        return (partes[0].charAt(0) + "" + partes[partes.length - 1].charAt(0)).toUpperCase();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (cuidadorListener != null) cuidadorListener.remove();
        if (pacienteListener != null) pacienteListener.remove();
        if (alertasListener != null) alertasListener.remove();
    }
}

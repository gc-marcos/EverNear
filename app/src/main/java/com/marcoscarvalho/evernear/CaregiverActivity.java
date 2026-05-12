package com.marcoscarvalho.evernear;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CaregiverActivity extends AppCompatActivity {

    private static final int REQ_CALL_PHONE = 2001;

    private TextView tvPatientName;
    private TextView tvAvatarInitials;
    private TextView tvBpmValue;
    private View btnCall;

    private FirebaseFirestore db;
    private String uidCuidador;
    private ListenerRegistration cuidadorListener;
    private ListenerRegistration pacienteListener;
    private ListenerRegistration alertasListener;

    private String telefonePaciente;   // carregado do Firestore do paciente vinculado
    private long appStartTimestamp;
    private final Map<String, Boolean> alertasJaExibidos = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_caregiver);

        tvPatientName = findViewById(R.id.tv_patient_name);
        tvAvatarInitials = findViewById(R.id.tv_avatar_initials);
        tvBpmValue = findViewById(R.id.tv_bpm_value);
        btnCall = findViewById(R.id.btn_call);

        db = FirebaseFirestore.getInstance();
        uidCuidador = FirebaseAuth.getInstance().getUid();
        appStartTimestamp = System.currentTimeMillis();

        View ivBack = findViewById(R.id.iv_back);
        if (ivBack != null) ivBack.setOnClickListener(v -> finish());

        // Botão de ligar para o paciente
        if (btnCall != null) {
            btnCall.setOnClickListener(v -> ligarParaPaciente());
        }

        // Inicia o serviço de alertas em segundo plano para o cuidador
        // (garante recebimento mesmo quando o app estiver fechado)
        ContextCompat.startForegroundService(this,
                new Intent(this, CaregiverAlertService.class));

        carregarDadosCuidador();
        ouvirAlertas();
    }

    // ==================== Dados do cuidador e paciente ====================

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
                        telefonePaciente = null;
                        startActivity(new Intent(CaregiverActivity.this, DashboardCuidadorActivity.class));
                    } else {
                        ouvirPaciente(pacientesUids.get(0));
                    }
                });
    }

    /**
     * Listener em tempo real do primeiro paciente vinculado.
     * Atualiza nome, iniciais, BPM e telefone continuamente.
     */
    private void ouvirPaciente(String uidPaciente) {
        if (pacienteListener != null) pacienteListener.remove();

        pacienteListener = db.collection("users").document(uidPaciente)
                .addSnapshotListener((doc, e) -> {
                    if (e != null || doc == null || !doc.exists()) return;

                    // Nome / apelido
                    String nome = doc.getString("nome");
                    String apelido = doc.getString("apelido");
                    String exibir = (apelido != null && !apelido.isEmpty()) ? apelido : nome;
                    if (exibir != null) {
                        tvPatientName.setText(exibir);
                        tvAvatarInitials.setText(gerarIniciais(exibir));
                    }

                    // Telefone — salvo no cadastro do paciente
                    telefonePaciente = doc.getString("telefone");
                    if (btnCall != null) {
                        // Destaca o botão se houver telefone cadastrado
                        btnCall.setAlpha(telefonePaciente != null && !telefonePaciente.isEmpty()
                                ? 1f : 0.4f);
                    }

                    // BPM em tempo real
                    Long ultimoBpm = doc.getLong("ultimoBpm");
                    if (ultimoBpm != null) {
                        tvBpmValue.setText(ultimoBpm + " bpm");
                        Long bpmMin = doc.getLong("bpmMin");
                        Long bpmMax = doc.getLong("bpmMax");
                        int min = bpmMin != null ? bpmMin.intValue() : 50;
                        int max = bpmMax != null ? bpmMax.intValue() : 120;
                        tvBpmValue.setTextColor(
                                (ultimoBpm < min || ultimoBpm > max)
                                        ? Color.parseColor("#FF5252")
                                        : Color.parseColor("#4CAF50"));
                    }
                });
    }

    // ==================== Ligar para o paciente ====================

    private void ligarParaPaciente() {
        if (telefonePaciente == null || telefonePaciente.isEmpty()) {
            Toast.makeText(this,
                    "Nenhum telefone cadastrado para este paciente",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Solicita CALL_PHONE em runtime (obrigatório API 23+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL_PHONE);
            return;
        }

        discaParaTelefone(telefonePaciente);
    }

    private void discaParaTelefone(String telefone) {
        // Remove caracteres não numéricos e monta a URI tel:
        String numero = telefone.replaceAll("[^0-9+]", "");
        Intent callIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + numero));
        try {
            startActivity(callIntent);
        } catch (Exception ex) {
            Toast.makeText(this, "Não foi possível iniciar a ligação", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL_PHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                discaParaTelefone(telefonePaciente);
            } else {
                Toast.makeText(this,
                        "Permissão de chamada negada — ligue manualmente para " + telefonePaciente,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ==================== Alertas em tempo real ====================

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

                        Timestamp ts = dc.getDocument().getTimestamp("timestamp");
                        if (ts != null && ts.toDate().getTime() < appStartTimestamp - 5_000) continue;

                        String nomePaciente = dc.getDocument().getString("pacienteNome");
                        Long bpm = dc.getDocument().getLong("bpm");
                        String tipo = dc.getDocument().getString("tipo");

                        exibirAlerta(alertaId, nomePaciente,
                                bpm != null ? bpm.intValue() : 0, tipo);
                    }
                });
    }

    private void exibirAlerta(String alertaId, String paciente, int bpm, String tipo) {
        String titulo;
        if ("MANUAL".equals(tipo)) titulo = "🚨 EMERGÊNCIA acionada";
        else if ("HIGH".equals(tipo)) titulo = "❤️ Frequência ALTA";
        else titulo = "💙 Frequência BAIXA";

        String msg = (paciente != null ? paciente : "Paciente")
                + "\n\nBPM: " + bpm
                + "\nTipo: " + tipo;

        // Mostra botão de ligar no diálogo se houver telefone
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Confirmar recebimento", (dialog, which) -> {
                    db.collection("alerts").document(alertaId).update("acknowledged", true);
                    Toast.makeText(this, "Alerta confirmado", Toast.LENGTH_SHORT).show();
                });

        if (telefonePaciente != null && !telefonePaciente.isEmpty()) {
            builder.setNeutralButton("📞 Ligar agora", (dialog, which) -> {
                db.collection("alerts").document(alertaId).update("acknowledged", true);
                ligarParaPaciente();
            });
        }

        builder.show();
    }

    // ==================== Utilitários ====================

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

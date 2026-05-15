package com.marcoscarvalho.evernear;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CaregiverActivity extends AppCompatActivity {

    private static final int REQ_CALL_PHONE = 2001;

    private TextView tvPatientName;
    private TextView tvAvatarInitials;
    private TextView tvBpmValue;
    private View btnCall;
    private LinearLayout llPacientesSecundarios;

    private FirebaseFirestore db;
    private String uidCuidador;
    private ListenerRegistration cuidadorListener;
    private ListenerRegistration pacienteListener;
    private ListenerRegistration alertasListener;

    private String telefonePaciente;
    private String uidPacienteAtivo;

    private final List<String> todosUids          = new ArrayList<>();
    private final Map<String, String> nomesPorUid = new HashMap<>();

    // Alertas já exibidos nesta sessão (em memória — evita duplicata enquanto a tela está aberta)
    private final java.util.Set<String> alertasExibidos = new java.util.HashSet<>();

    // ==================== Ciclo de vida ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_caregiver);

        tvPatientName          = findViewById(R.id.tv_patient_name);
        tvAvatarInitials       = findViewById(R.id.tv_avatar_initials);
        tvBpmValue             = findViewById(R.id.tv_bpm_value);
        btnCall                = findViewById(R.id.btn_call);
        llPacientesSecundarios = findViewById(R.id.ll_pacientes_secundarios);

        db = FirebaseFirestore.getInstance();
        uidCuidador = FirebaseAuth.getInstance().getUid();

        View ivBack = findViewById(R.id.iv_back);
        if (ivBack != null) ivBack.setOnClickListener(v -> finish());

        Button btnAddPaciente = findViewById(R.id.btn_add_paciente);
        if (btnAddPaciente != null) {
            btnAddPaciente.setOnClickListener(v ->
                    startActivity(new Intent(this, DashboardCuidadorActivity.class)));
        }

        if (btnCall != null) btnCall.setOnClickListener(v -> ligarParaPaciente());

        // Inicia o serviço de alertas em segundo plano
        ContextCompat.startForegroundService(this,
                new Intent(this, CaregiverAlertService.class));

        // Solicita isenção de otimização de bateria para garantir recebimento de alertas
        solicitarIsencaoBateria();

        carregarDadosCuidador();
        ouvirAlertasComApp();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cuidadorListener != null) cuidadorListener.remove();
        if (pacienteListener  != null) pacienteListener.remove();
        if (alertasListener   != null) alertasListener.remove();
    }

    // ==================== Isenção de otimização de bateria ====================

    /**
     * Solicita ao usuário que isente o app da otimização de bateria do Android.
     * Sem isso, o sistema pode suspender o CaregiverAlertService agressivamente,
     * especialmente em fabricantes como Samsung, Huawei e Xiaomi.
     *
     * A isenção é solicitada apenas uma vez (se ainda não concedida).
     */
    private void solicitarIsencaoBateria() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;

        String packageName = getPackageName();
        if (pm.isIgnoringBatteryOptimizations(packageName)) return; // já isento

        new AlertDialog.Builder(this)
                .setTitle("Permitir recebimento de alertas")
                .setMessage("Para receber alertas do paciente mesmo com o app fechado, "
                        + "o EverNear precisa ser excluído da otimização de bateria.\n\n"
                        + "Toque em \"Permitir\" e selecione \"Não otimizar\".")
                .setCancelable(false)
                .setPositiveButton("Permitir", (dialog, which) -> {
                    Intent intent = new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + packageName));
                    try { startActivity(intent); }
                    catch (Exception e) {
                        // Fallback: abre a tela de configuração geral de bateria
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("Agora não", null)
                .show();
    }

    // ==================== Carregamento dos pacientes vinculados ====================

    private void carregarDadosCuidador() {
        if (uidCuidador == null) return;

        cuidadorListener = db.collection("users").document(uidCuidador)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null || snapshot == null || !snapshot.exists()) return;

                    @SuppressWarnings("unchecked")
                    List<String> uids = (List<String>) snapshot.get("pacientesVinculados");

                    if (uids == null || uids.isEmpty()) {
                        tvPatientName.setText("Nenhum paciente vinculado");
                        tvAvatarInitials.setText("?");
                        tvBpmValue.setText("--");
                        telefonePaciente = null;
                        if (btnCall != null) btnCall.setAlpha(0.4f);
                        llPacientesSecundarios.removeAllViews();
                        todosUids.clear();
                        nomesPorUid.clear();
                        uidPacienteAtivo = null;
                        if (pacienteListener != null) pacienteListener.remove();
                        return;
                    }

                    todosUids.clear();
                    todosUids.addAll(uids);

                    if (uidPacienteAtivo == null || !uids.contains(uidPacienteAtivo)) {
                        uidPacienteAtivo = uids.get(0);
                        ouvirPaciente(uidPacienteAtivo);
                    }

                    carregarNomesEConstruirChips(uids);
                });
    }

    private void carregarNomesEConstruirChips(List<String> uids) {
        final int[] pendentes = {uids.size()};

        for (String uid : uids) {
            if (nomesPorUid.containsKey(uid)) {
                pendentes[0]--;
                if (pendentes[0] == 0) construirChips(uids);
                continue;
            }
            db.collection("users").document(uid).get()
                    .addOnSuccessListener(doc -> {
                        if (doc.exists()) {
                            String nome    = doc.getString("nome");
                            String apelido = doc.getString("apelido");
                            String exibir  = (apelido != null && !apelido.isEmpty())
                                    ? apelido : (nome != null ? nome : "Paciente");
                            nomesPorUid.put(uid, exibir);
                        }
                        pendentes[0]--;
                        if (pendentes[0] == 0) construirChips(uids);
                    })
                    .addOnFailureListener(ex -> {
                        nomesPorUid.put(uid, "Paciente");
                        pendentes[0]--;
                        if (pendentes[0] == 0) construirChips(uids);
                    });
        }
    }

    private void construirChips(List<String> uids) {
        llPacientesSecundarios.removeAllViews();

        for (String uid : uids) {
            String nome  = nomesPorUid.getOrDefault(uid, "Paciente");
            boolean ativo = uid.equals(uidPacienteAtivo);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            itemParams.setMargins(0, 0, 20, 0);
            item.setLayoutParams(itemParams);
            item.setTag(uid);

            TextView avatar = new TextView(this);
            LinearLayout.LayoutParams avParams = new LinearLayout.LayoutParams(52, 52);
            avatar.setLayoutParams(avParams);
            avatar.setGravity(Gravity.CENTER);
            avatar.setText(gerarIniciais(nome));
            avatar.setTextColor(Color.WHITE);
            avatar.setTextSize(14f);
            avatar.setTypeface(null, android.graphics.Typeface.BOLD);

            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(ativo ? Color.parseColor("#C0392B") : Color.parseColor("#2C3E50"));
            avatar.setBackground(shape);

            TextView tvNome = new TextView(this);
            tvNome.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            tvNome.setText(nome.length() > 10 ? nome.substring(0, 9) + "…" : nome);
            tvNome.setTextColor(ativo ? Color.WHITE : Color.parseColor("#8899AA"));
            tvNome.setTextSize(11f);
            tvNome.setGravity(Gravity.CENTER);
            tvNome.setPadding(0, 6, 0, 0);

            item.addView(avatar);
            item.addView(tvNome);

            final String uidChip = uid;
            item.setOnClickListener(v -> {
                if (!uidChip.equals(uidPacienteAtivo)) {
                    uidPacienteAtivo = uidChip;
                    ouvirPaciente(uidChip);
                    construirChips(todosUids);
                }
            });

            llPacientesSecundarios.addView(item);
        }
    }

    // ==================== Listener do paciente ativo ====================

    private void ouvirPaciente(String uidPaciente) {
        if (pacienteListener != null) pacienteListener.remove();

        pacienteListener = db.collection("users").document(uidPaciente)
                .addSnapshotListener((doc, e) -> {
                    if (e != null || doc == null || !doc.exists()) return;

                    String nome    = doc.getString("nome");
                    String apelido = doc.getString("apelido");
                    String exibir  = (apelido != null && !apelido.isEmpty()) ? apelido : nome;

                    if (exibir != null) {
                        tvPatientName.setText(exibir);
                        tvAvatarInitials.setText(gerarIniciais(exibir));
                        nomesPorUid.put(uidPaciente, exibir);
                    }

                    telefonePaciente = doc.getString("telefone");
                    if (btnCall != null) {
                        btnCall.setAlpha(telefonePaciente != null && !telefonePaciente.isEmpty()
                                ? 1f : 0.4f);
                    }

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
                    } else {
                        tvBpmValue.setText("--");
                        tvBpmValue.setTextColor(Color.parseColor("#8899AA"));
                    }
                });
    }

    // ==================== Alertas em tempo real (com app aberto) ====================

    /**
     * Ouve alertas em tempo real enquanto a CaregiverActivity está aberta.
     * Exibe um AlertDialog para confirmação imediata.
     *
     * NÃO usa filtro por timestamp — qualquer alerta não confirmado é exibido,
     * inclusive alertas gerados enquanto o app estava fechado.
     * O conjunto alertasExibidos (em memória) evita duplicatas nesta sessão.
     */
    private void ouvirAlertasComApp() {
        if (uidCuidador == null) return;

        alertasListener = db.collection("alerts")
                .whereEqualTo("cuidadorId", uidCuidador)
                .whereEqualTo("acknowledged", false)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;

                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() != DocumentChange.Type.ADDED) continue;

                        String alertaId = dc.getDocument().getId();
                        if (alertasExibidos.contains(alertaId)) continue;
                        alertasExibidos.add(alertaId);

                        String nomePaciente = dc.getDocument().getString("pacienteNome");
                        Long   bpm          = dc.getDocument().getLong("bpm");
                        String tipo         = dc.getDocument().getString("tipo");

                        exibirAlertaDialog(alertaId, nomePaciente,
                                bpm != null ? bpm.intValue() : 0, tipo);
                    }
                });
    }

    private void exibirAlertaDialog(String alertaId, String paciente, int bpm, String tipo) {
        String titulo;
        if ("MANUAL".equals(tipo))    titulo = "🚨 EMERGÊNCIA acionada";
        else if ("HIGH".equals(tipo)) titulo = "❤ Frequência ALTA";
        else                          titulo = "💙 Frequência BAIXA";

        String msg = (paciente != null ? paciente : "Paciente")
                + "\n\nBPM: " + bpm + "\nTipo: " + tipo;

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Confirmar recebimento", (dialog, which) -> {
                    db.collection("alerts").document(alertaId)
                            .update("acknowledged", true);
                    Toast.makeText(this, "Alerta confirmado", Toast.LENGTH_SHORT).show();
                });

        if (telefonePaciente != null && !telefonePaciente.isEmpty()) {
            builder.setNeutralButton("📞 Ligar agora", (dialog, which) -> {
                db.collection("alerts").document(alertaId)
                        .update("acknowledged", true);
                ligarParaPaciente();
            });
        }

        builder.show();
    }

    // ==================== Ligar para o paciente ====================

    private void ligarParaPaciente() {
        if (telefonePaciente == null || telefonePaciente.isEmpty()) {
            Toast.makeText(this,
                    "Nenhum telefone cadastrado para este paciente", Toast.LENGTH_LONG).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL_PHONE);
            return;
        }
        discaParaTelefone(telefonePaciente);
    }

    private void discaParaTelefone(String telefone) {
        String numero = telefone.replaceAll("[^0-9+]", "");
        try {
            startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + numero)));
        } catch (Exception ex) {
            Toast.makeText(this, "Não foi possível iniciar a ligação", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL_PHONE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            discaParaTelefone(telefonePaciente);
        }
    }

    // ==================== Utilitários ====================

    private String gerarIniciais(String nome) {
        if (nome == null || nome.isEmpty()) return "?";
        String[] partes = nome.trim().split("\\s+");
        if (partes.length == 1)
            return partes[0].substring(0, Math.min(2, partes[0].length())).toUpperCase();
        return (partes[0].charAt(0) + "" + partes[partes.length - 1].charAt(0)).toUpperCase();
    }
}

package com.marcoscarvalho.evernear;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class CaregiverActivity extends AppCompatActivity {

    private static final int REQ_CALL_PHONE = 2001;

    /**
     * Formato de data/hora para o timestamp da última leitura de BPM.
     * Instância estática — Firestore callbacks executam na main thread, portanto
     * não há risco de acesso concorrente.
     */
    private static final SimpleDateFormat SDF_BPM_TS =
            new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());

    // ── Views ──────────────────────────────────────────────────────────────────
    private TextView     tvPatientName;
    private TextView     tvAvatarInitials;
    private TextView     tvBpmValue;
    private TextView     tvBpmTimestamp;
    private TextView     tvStatusMonitoramento;
    private View         vStatusDot;
    private View         btnCall;
    private LinearLayout llPacientesSecundarios;

    // ── Firebase ───────────────────────────────────────────────────────────────
    private FirebaseFirestore db;
    private String            uidCuidador;

    // Três listeners — todos removidos em onDestroy()
    private ListenerRegistration cuidadorListener;
    private ListenerRegistration pacienteListener;
    private ListenerRegistration alertasListener;

    // ── Estado ─────────────────────────────────────────────────────────────────
    private String telefonePaciente;
    private String uidPacienteAtivo;
    private String uidPacienteDoAlerta; // vindo da notificação

    private final List<String>        todosUids   = new ArrayList<>();
    private final Map<String, String> nomesPorUid = new HashMap<>();

    /**
     * Alertas já exibidos nesta sessão (em memória).
     * Evita re-exibir o mesmo alerta se o snapshot chegar mais de uma vez.
     *
     * Usa synchronizedSet: embora os callbacks do Firestore cheguem na main thread
     * na maioria das versões do SDK, o wrapper garante corretude em qualquer cenário.
     */
    private final Set<String> alertasExibidos = Collections.synchronizedSet(new HashSet<>());

    /**
     * Timestamp do momento em que a Activity foi criada.
     * Alertas com {@code timestamp} anterior a este valor são ignorados —
     * evita dialogs de alertas antigos ao reabrir o app.
     */
    private Date timestampAbertura;

    // ==================== Ciclo de vida ========================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_caregiver);

        // Registra o momento de abertura ANTES de qualquer listener
        timestampAbertura = new Date();

        tvPatientName          = findViewById(R.id.tv_patient_name);
        tvAvatarInitials       = findViewById(R.id.tv_avatar_initials);
        tvBpmValue             = findViewById(R.id.tv_bpm_value);
        tvBpmTimestamp         = findViewById(R.id.tv_bpm_timestamp);
        tvStatusMonitoramento  = findViewById(R.id.tv_status_monitoramento);
        vStatusDot             = findViewById(R.id.v_status_dot);
        btnCall                = findViewById(R.id.btn_call);
        llPacientesSecundarios = findViewById(R.id.ll_pacientes_secundarios);

        db          = FirebaseFirestore.getInstance();
        uidCuidador = FirebaseAuth.getInstance().getUid();

        uidPacienteDoAlerta = getIntent().getStringExtra("pacienteId");

        View ivBack = findViewById(R.id.iv_back);
        if (ivBack != null) ivBack.setOnClickListener(v -> finish());

        Button btnAddPaciente = findViewById(R.id.btn_add_paciente);
        if (btnAddPaciente != null) {
            btnAddPaciente.setOnClickListener(v ->
                    startActivity(new Intent(this, DashboardCuidadorActivity.class)));
        }

        if (btnCall != null) btnCall.setOnClickListener(v -> ligarParaPaciente());

        ContextCompat.startForegroundService(this,
                new Intent(this, CaregiverAlertService.class));

        carregarDadosCuidador();
        ouvirAlertasComApp();
    }

    /**
     * Trata notificações quando o app já está aberto (launchMode="singleTop").
     * Troca o paciente exibido sem recriar a Activity.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        String novoPacienteId = intent.getStringExtra("pacienteId");
        if (novoPacienteId == null || novoPacienteId.isEmpty()) return;
        if (novoPacienteId.equals(uidPacienteAtivo)) return;

        uidPacienteDoAlerta = novoPacienteId;

        if (todosUids.contains(novoPacienteId)) {
            uidPacienteAtivo    = novoPacienteId;
            uidPacienteDoAlerta = null;
            ouvirPaciente(novoPacienteId);
            construirChips(todosUids);
        }
        // Se a lista ainda não carregou, uidPacienteDoAlerta é consumido em carregarDadosCuidador()
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cuidadorListener != null) cuidadorListener.remove();
        if (pacienteListener  != null) pacienteListener.remove();
        if (alertasListener   != null) alertasListener.remove();
    }

    // ==================== Carregamento dos pacientes vinculados ================

    private void carregarDadosCuidador() {
        if (uidCuidador == null) return;

        cuidadorListener = db.collection("users").document(uidCuidador)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null || snapshot == null || !snapshot.exists()) return;

                    @SuppressWarnings("unchecked")
                    List<String> uids = (List<String>) snapshot.get(
                            FirebaseHelper.Fields.PACIENTES_VINCULADOS);

                    if (uids == null || uids.isEmpty()) {
                        mostrarEstadoSemPaciente();
                        return;
                    }

                    todosUids.clear();
                    todosUids.addAll(uids);

                    // Prioridade de seleção:
                    // 1. pacienteId da notificação de alerta
                    // 2. paciente já ativo que ainda está na lista
                    // 3. primeiro da lista (fallback)
                    if (uidPacienteDoAlerta != null && uids.contains(uidPacienteDoAlerta)) {
                        if (!uidPacienteDoAlerta.equals(uidPacienteAtivo)) {
                            uidPacienteAtivo = uidPacienteDoAlerta;
                            ouvirPaciente(uidPacienteAtivo);
                        }
                        uidPacienteDoAlerta = null;
                    } else if (uidPacienteAtivo == null || !uids.contains(uidPacienteAtivo)) {
                        uidPacienteAtivo = uids.get(0);
                        ouvirPaciente(uidPacienteAtivo);
                    }

                    carregarNomesEConstruirChips(uids);
                });
    }

    private void mostrarEstadoSemPaciente() {
        tvPatientName.setText("Nenhum paciente vinculado");
        tvAvatarInitials.setText("?");
        tvBpmValue.setText("--");
        if (tvBpmTimestamp != null) tvBpmTimestamp.setText("");
        atualizarStatusMonitoramento(null);
        telefonePaciente = null;
        if (btnCall != null) btnCall.setAlpha(0.4f);
        llPacientesSecundarios.removeAllViews();
        todosUids.clear();
        nomesPorUid.clear();
        uidPacienteAtivo = null;
        if (pacienteListener != null) {
            pacienteListener.remove();
            pacienteListener = null;
        }
    }

    /**
     * Usa AtomicInteger no lugar do array int[] para evitar condição de corrida
     * nos callbacks assíncronos paralelos do Firestore.
     */
    private void carregarNomesEConstruirChips(List<String> uids) {
        AtomicInteger pendentes = new AtomicInteger(uids.size());

        for (String uid : uids) {
            if (nomesPorUid.containsKey(uid)) {
                if (pendentes.decrementAndGet() == 0) construirChips(uids);
                continue;
            }
            db.collection("users").document(uid).get()
                    .addOnSuccessListener(doc -> {
                        if (doc.exists()) {
                            String apelido = doc.getString(FirebaseHelper.Fields.APELIDO);
                            String nome    = doc.getString(FirebaseHelper.Fields.NOME);
                            nomesPorUid.put(uid,
                                    FirebaseHelper.nomeExibir(apelido, nome, "Paciente"));
                        } else {
                            nomesPorUid.put(uid, "Paciente");
                        }
                        if (pendentes.decrementAndGet() == 0) construirChips(uids);
                    })
                    .addOnFailureListener(ex -> {
                        nomesPorUid.put(uid, "Paciente");
                        if (pendentes.decrementAndGet() == 0) construirChips(uids);
                    });
        }
    }

    /**
     * Atualiza apenas os chips que mudaram de estado (ativo/inativo)
     * em vez de recriar todas as views — evita flickering visual.
     */
    private void construirChips(List<String> uids) {
        // Tenta atualizar chips existentes antes de recriar tudo
        if (llPacientesSecundarios.getChildCount() == uids.size()) {
            for (int i = 0; i < llPacientesSecundarios.getChildCount(); i++) {
                View item = llPacientesSecundarios.getChildAt(i);
                String uidChip = (String) item.getTag();
                if (uidChip == null) break; // lista mudou — recria tudo
                atualizarEstadoChip(item, uidChip);
            }
            return;
        }

        // Recria do zero se a quantidade de pacientes mudou
        llPacientesSecundarios.removeAllViews();

        for (String uid : uids) {
            String  nome  = nomesPorUid.getOrDefault(uid, "Paciente");
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
            avatar.setTag("avatar"); // facilita busca em atualizarEstadoChip
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
            tvNome.setTag("nome"); // facilita busca em atualizarEstadoChip
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

    /** Atualiza cor e estado visual de um chip sem recriar a view. */
    private void atualizarEstadoChip(View item, String uid) {
        boolean ativo = uid.equals(uidPacienteAtivo);

        TextView avatar = item.findViewWithTag("avatar");
        TextView tvNome = item.findViewWithTag("nome");
        if (avatar == null || tvNome == null) return;

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(ativo ? Color.parseColor("#C0392B") : Color.parseColor("#2C3E50"));
        avatar.setBackground(shape);

        tvNome.setTextColor(ativo ? Color.WHITE : Color.parseColor("#8899AA"));
    }

    // ==================== Listener do paciente ativo ===========================

    private void ouvirPaciente(String uidPaciente) {
        if (pacienteListener != null) pacienteListener.remove();

        pacienteListener = db.collection("users").document(uidPaciente)
                .addSnapshotListener((doc, e) -> {
                    if (e != null || doc == null || !doc.exists()) return;

                    String apelido = doc.getString(FirebaseHelper.Fields.APELIDO);
                    String nome    = doc.getString(FirebaseHelper.Fields.NOME);
                    String exibir  = FirebaseHelper.nomeExibir(apelido, nome, "Paciente");

                    tvPatientName.setText(exibir);
                    tvAvatarInitials.setText(gerarIniciais(exibir));
                    nomesPorUid.put(uidPaciente, exibir);

                    telefonePaciente = doc.getString(FirebaseHelper.Fields.TELEFONE);
                    if (btnCall != null) {
                        btnCall.setAlpha(
                                telefonePaciente != null && !telefonePaciente.isEmpty()
                                        ? 1f : 0.4f);
                    }

                    Long ultimoBpm = doc.getLong(FirebaseHelper.Fields.ULTIMO_BPM);
                    if (ultimoBpm != null) {
                        tvBpmValue.setText(ultimoBpm + " bpm");

                        Long bpmMin = doc.getLong(FirebaseHelper.Fields.BPM_MIN);
                        Long bpmMax = doc.getLong(FirebaseHelper.Fields.BPM_MAX);
                        int min = bpmMin != null ? bpmMin.intValue() : 50;
                        int max = bpmMax != null ? bpmMax.intValue() : 120;

                        tvBpmValue.setTextColor(
                                (ultimoBpm < min || ultimoBpm > max)
                                        ? Color.parseColor("#FF5252")
                                        : Color.parseColor("#4CAF50"));

                        if (tvBpmTimestamp != null) {
                            Date bpmAt = doc.getDate(FirebaseHelper.Fields.ULTIMO_BPM_TIMESTAMP);
                            tvBpmTimestamp.setText(bpmAt != null
                                    ? "às " + formatarHoraCompleta(bpmAt)
                                    : "");
                        }
                    } else {
                        tvBpmValue.setText("--");
                        tvBpmValue.setTextColor(Color.parseColor("#8899AA"));
                        if (tvBpmTimestamp != null) tvBpmTimestamp.setText("");
                    }

                    String status = doc.getString(FirebaseHelper.Fields.STATUS_MONITORAMENTO);
                    atualizarStatusMonitoramento(status);
                });
    }

    // ==================== Alertas em tempo real (com app aberto) ===============

    /**
     * Filtra alertas pelo campo {@code timestamp} >= {@code timestampAbertura}.
     * Evita exibir dialogs de alertas antigos ao reabrir o app.
     *
     * O campo usado é "timestamp" — o mesmo gravado por {@link FirebaseHelper#enviarAlerta}.
     * Se o campo não existir no documento, o alerta é exibido mesmo assim (fail-safe).
     */
    private void ouvirAlertasComApp() {
        if (uidCuidador == null) return;

        alertasListener = db.collection("alerts")
                .whereEqualTo(FirebaseHelper.Fields.CUIDADOR_ID, uidCuidador)
                .whereEqualTo(FirebaseHelper.Fields.ACKNOWLEDGED, false)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;

                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() != DocumentChange.Type.ADDED) continue;

                        String alertaId = dc.getDocument().getId();
                        if (alertasExibidos.contains(alertaId)) continue;

                        // Ignora alertas criados antes da abertura da tela
                        Date alertaTimestamp = dc.getDocument().getDate(
                                FirebaseHelper.Fields.TIMESTAMP);
                        if (alertaTimestamp != null
                                && alertaTimestamp.before(timestampAbertura)) {
                            alertasExibidos.add(alertaId); // marca para não reprocessar
                            continue;
                        }

                        alertasExibidos.add(alertaId);

                        String nomePaciente = dc.getDocument().getString(
                                FirebaseHelper.Fields.PACIENTE_NOME);
                        Long   bpm          = dc.getDocument().getLong(
                                FirebaseHelper.Fields.BPM);
                        String tipo         = dc.getDocument().getString(
                                FirebaseHelper.Fields.TIPO_ALERTA);

                        exibirAlertaDialog(alertaId, nomePaciente,
                                bpm != null ? bpm.intValue() : 0, tipo);
                    }
                });
    }

    /**
     * Verifica se a Activity ainda está ativa antes de exibir o dialog.
     * Evita crash quando o callback do Firestore chega após o usuário sair da tela.
     */
    private void exibirAlertaDialog(String alertaId, String paciente, int bpm, String tipo) {
        if (isFinishing() || isDestroyed()) return;

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
                    confirmarAlerta(alertaId);
                    Toast.makeText(this, "Alerta confirmado", Toast.LENGTH_SHORT).show();
                });

        if (telefonePaciente != null && !telefonePaciente.isEmpty()) {
            builder.setNeutralButton("📞 Ligar agora", (dialog, which) -> {
                confirmarAlerta(alertaId);
                ligarParaPaciente();
            });
        }

        builder.show();
    }

    /**
     * Confirma o alerta via FirebaseHelper para garantir auditoria completa:
     * acknowledged=true, acknowledgedBy=uidCuidador, acknowledgedAt=serverTimestamp.
     *
     * Usa {@code uidCuidador} — campo já disponível na classe — em vez de consultar
     * FirebaseAuth novamente.
     */
    private void confirmarAlerta(String alertaId) {
        if (uidCuidador == null) {
            Toast.makeText(this, "Erro: sessão expirada", Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseHelper.confirmarAlerta(alertaId, uidCuidador, new FirebaseHelper.Callback<Void>() {
            @Override public void onResult(Void v) { /* confirmação silenciosa */ }
            @Override public void onError(Exception e) {
                Toast.makeText(CaregiverActivity.this,
                        "Erro ao confirmar alerta", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== Ligar para o paciente ================================

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
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL_PHONE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            discaParaTelefone(telefonePaciente);
        }
    }

    // ==================== Utilitários ==========================================

    private String gerarIniciais(String nome) {
        if (nome == null || nome.isEmpty()) return "?";
        String[] partes = nome.trim().split("\\s+");
        if (partes.length == 1)
            return partes[0].substring(0, Math.min(2, partes[0].length())).toUpperCase();
        return (partes[0].charAt(0) + "" + partes[partes.length - 1].charAt(0)).toUpperCase();
    }

    /**
     * Atualiza o indicador visual de status do monitoramento.
     *
     * Cores e textos mapeados do campo "statusMonitoramento" do Firestore
     * (gravado pelo HeartRateService via FirebaseHelper.salvarStatusMonitoramento):
     *   ATIVO        → verde   — sensor lendo normalmente
     *   RECONECTANDO → amarelo — watchdog tentando recuperar o sensor
     *   PARADO       → cinza   — serviço encerrado intencionalmente
     *   SEM_SENSOR   → vermelho — hardware indisponível
     *   null / ""    → cinza   — sem dados ainda
     */
    private void atualizarStatusMonitoramento(String status) {
        if (tvStatusMonitoramento == null || vStatusDot == null) return;

        String texto;
        int corTexto;
        int corDot;

        if (status == null || status.isEmpty()) {
            texto    = "Sem dados";
            corTexto = Color.parseColor("#9AA4B2");
            corDot   = Color.parseColor("#9AA4B2");
        } else {
            switch (status) {
                case "ATIVO":
                    texto    = "Monitoramento ativo";
                    corTexto = Color.parseColor("#4CAF50");
                    corDot   = Color.parseColor("#4CAF50");
                    break;
                case "RECONECTANDO":
                    texto    = "Reconectando sensor...";
                    corTexto = Color.parseColor("#FFC107");
                    corDot   = Color.parseColor("#FFC107");
                    break;
                case "PARADO":
                    texto    = "Monitoramento parado";
                    corTexto = Color.parseColor("#9AA4B2");
                    corDot   = Color.parseColor("#9AA4B2");
                    break;
                case "SEM_SENSOR":
                    texto    = "Sensor indisponível";
                    corTexto = Color.parseColor("#FF5252");
                    corDot   = Color.parseColor("#FF5252");
                    break;
                default:
                    texto    = status;
                    corTexto = Color.parseColor("#9AA4B2");
                    corDot   = Color.parseColor("#9AA4B2");
                    break;
            }
        }

        tvStatusMonitoramento.setText(texto);
        tvStatusMonitoramento.setTextColor(corTexto);

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(corDot);
        vStatusDot.setBackground(dot);
    }

    /** Formata Date como "dd/MM HH:mm" para exibir data e horário da última leitura. */
    private String formatarHoraCompleta(Date data) {
        return SDF_BPM_TS.format(data);
    }
}

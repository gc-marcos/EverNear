package com.marcoscarvalho.evernear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.marcoscarvalho.evernear.BuildConfig;

/**
 * Tela principal do paciente no smartwatch.
 *
 * O monitoramento contínuo e o envio de alertas são feitos pelo HeartRateService
 * (foreground service com WakeLock + HandlerThread dedicado). Esta Activity:
 *  - Exibe BPM em tempo real via listener estático do serviço
 *  - Gerencia permissões de sensor
 *  - Aciona calibração e emergência manual via serviço
 *
 * ── Calibração e tela acesa ──────────────────────────────────────────────────
 * Durante a calibração (~30 leituras × 3 s = ~90 s), a tela é mantida acesa
 * com FLAG_KEEP_SCREEN_ON. Isso:
 *  1. Evita que o timeout de inatividade apague a tela no meio da calibração.
 *  2. Garante que o sensor de hardware continue entregando leituras (em alguns
 *     modelos Wear OS, o sensor é suspenso quando a tela apaga).
 * A flag é removida assim que a calibração termina ou a Activity sai da tela.
 */
public class PatientActivity extends AppCompatActivity implements HeartRateMonitor.Listener {

    private TextView tvBpmValue, tvStatus, tvLimites;
    private Button   btnEmergency, btnVerCodigo, btnCalibrar;

    // Controla se a tela deve ficar acesa (apenas durante calibração)
    private boolean telaAcesaParaCalibracao = false;

    /**
     * Receiver para detectar GPS desativado enquanto esta Activity está visível.
     *
     * Registrado em onResume() e removido em onPause().
     * Quando o usuário desativa o GPS nas configurações do relógio enquanto o
     * app está aberto na tela, este receiver dispara e exibe o diálogo bloqueante
     * impedindo o uso do app sem GPS.
     */
    private BroadcastReceiver gpsDesativadoReceiver;

    /**
     * Último BPM recebido pelo callback onHeartRate().
     * Usado em dispararEmergenciaManual() em vez de parsear a TextView, que pode
     * conter "--", texto de calibração ou outros valores não numéricos.
     */
    private int lastBpm = 0;

    private  Button btnDebugGeo;

    // ==================== Ciclo de vida ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient);

        tvBpmValue   = findViewById(R.id.tv_bpm_value);
        tvStatus     = findViewById(R.id.tv_status);
        tvLimites    = findViewById(R.id.tv_limites);
        btnEmergency = findViewById(R.id.btn_emergency);
        btnVerCodigo = findViewById(R.id.btn_ver_codigo);
        btnCalibrar  = findViewById(R.id.btn_calibrar);
        btnDebugGeo = findViewById(R.id.btn_debug_geo);

        btnVerCodigo.setOnClickListener(v ->
                startActivity(new Intent(this, DashboardPacienteActivity.class)));

        btnEmergency.setOnClickListener(v -> dispararEmergenciaManual());

        btnCalibrar.setOnClickListener(v -> iniciarCalibracao());

        atualizarLimitesUI();

        if (BuildConfig.DEBUG) {
            btnDebugGeo.setVisibility(View.VISIBLE);
            btnDebugGeo.setOnClickListener(v ->
                    startActivity(new Intent(
                            PatientActivity.this,
                            com.marcoscarvalho.evernear.debug.DebugLocationActivity.class
                    ))
            );
        } else {
            btnDebugGeo.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Valida pré-requisitos de localização antes de iniciar o serviço.
        // Cobre o caso em que o usuário desativou o GPS após o setup inicial.
        if (!LocationHelper.temPermissao(this) || !LocationHelper.gpsHabilitado(this)) {
            mostrarDialogGpsNecessario();
            return;
        }

        HeartRateService.setActivityListener(this);
        // Permissões já foram solicitadas em SetupPermissoesActivity — inicia o serviço diretamente
        iniciarServico();

        // Se voltou ao primeiro plano durante calibração, mantém tela acesa
        HeartRateService svc = HeartRateService.getInstance();
        if (svc != null && svc.getMonitor() != null && svc.getMonitor().isCalibrating()) {
            manterTelaAcesa(true);
        }

        // Registra receiver para detectar GPS desativado enquanto esta tela está visível.
        // Isso cobre o cenário: usuário abre configurações do relógio enquanto o app está
        // visível e desativa o GPS → o diálogo bloqueante aparece imediatamente.
        gpsDesativadoReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (HeartRateService.ACTION_GPS_DESATIVADO.equals(intent.getAction())) {
                    // GPS foi desativado com esta Activity visível — exibe diálogo bloqueante
                    mostrarDialogGpsNecessario();
                }
            }
        };
        IntentFilter filtro = new IntentFilter(HeartRateService.ACTION_GPS_DESATIVADO);
        registerReceiver(gpsDesativadoReceiver, filtro);
    }

    @Override
    protected void onPause() {
        super.onPause();
        HeartRateService.setActivityListener(null);
        // Remove a flag ao sair da tela — o serviço continua calibrando em background
        manterTelaAcesa(false);

        // Remove o receiver de GPS — não é necessário enquanto a Activity não está visível
        // (o HeartRateService ainda detecta mudanças e envia notificação ao paciente)
        if (gpsDesativadoReceiver != null) {
            try { unregisterReceiver(gpsDesativadoReceiver); } catch (Exception ignored) {}
            gpsDesativadoReceiver = null;
        }
    }

    /**
     * Exibe diálogo bloqueante quando GPS ou permissão de localização estão indisponíveis.
     *
     * "Abrir configurações" → leva o usuário às configurações de localização do sistema.
     * "Voltar" → encerra a Activity (o paciente não pode usar o app sem GPS).
     *
     * Quando o usuário volta das configurações, {@code onResume()} é chamado novamente
     * e verifica se as condições foram atendidas.
     */
    private void mostrarDialogGpsNecessario() {
        if (isFinishing() || isDestroyed()) return;

        boolean semPermissao = !LocationHelper.temPermissao(this);
        boolean gpsDesligado = !LocationHelper.gpsHabilitado(this);

        String mensagem;
        if (semPermissao && gpsDesligado) {
            mensagem = "O EverNear precisa da permissão de localização e do GPS ativado "
                    + "para proteger você em emergências.\n\n"
                    + "Conceda a permissão e ative o GPS para continuar.";
        } else if (semPermissao) {
            mensagem = "A permissão de localização não foi concedida.\n\n"
                    + "Acesse as configurações do app para concedê-la.";
        } else {
            mensagem = "O GPS está desativado.\n\n"
                    + "Ative a localização para que o EverNear possa protegê-lo "
                    + "em emergências.";
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("📍 Localização necessária")
                .setMessage(mensagem)
                .setCancelable(false)
                .setPositiveButton("Abrir configurações", (d, w) ->
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("Voltar", (d, w) -> finish())
                .show();
    }

    // ==================== Calibração ====================

    private void iniciarCalibracao() {
        // Mantém tela acesa durante a calibração (evita que o timeout de inatividade
        // apague a tela e, em alguns dispositivos, suspenda o sensor)
        manterTelaAcesa(true);

        Intent intent = new Intent(this, HeartRateService.class);
        intent.setAction(HeartRateService.ACTION_CALIBRAR);
        ContextCompat.startForegroundService(this, intent);

        btnCalibrar.setEnabled(false);
        btnCalibrar.setAlpha(0.5f);
        tvStatus.setText("Calibrando — fique em repouso...");
        tvStatus.setTextColor(Color.parseColor("#FFC107"));

        Toast.makeText(this,
                "Calibração iniciada — mantenha o relógio no pulso e fique em repouso",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Liga/desliga o FLAG_KEEP_SCREEN_ON na janela desta Activity.
     * Quando ligado: o Android não apaga a tela por timeout enquanto a Activity estiver visível.
     * Quando desligado: comportamento normal retorna.
     */
    private void manterTelaAcesa(boolean manter) {
        if (manter == telaAcesaParaCalibracao) return; // evita chamadas redundantes
        telaAcesaParaCalibracao = manter;

        if (manter) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ==================== Serviço ====================

    private void iniciarServico() {
        ContextCompat.startForegroundService(this, new Intent(this, HeartRateService.class));
        atualizarLimitesUI();
    }

    // ==================== Callbacks do HeartRateMonitor ====================

    @Override
    public void onHeartRate(int bpm) {
        lastBpm = bpm; // salva para uso em emergência manual sem depender da TextView
        runOnUiThread(() -> {
            tvBpmValue.setText(String.valueOf(bpm));
            atualizarStatusVisual(bpm);
        });
    }

    @Override
    public void onStatusChange(String status) {
        runOnUiThread(() -> {
            tvStatus.setText(status);
            tvStatus.setTextColor(Color.parseColor("#9AA4B2"));
        });
    }

    @Override
    public void onAnomaly(int bpm, HeartRateMonitor.AnomalyType tipo) {
        runOnUiThread(() -> {
            String msg = tipo == HeartRateMonitor.AnomalyType.HIGH
                    ? "Frequência ALTA: " + bpm + " bpm"
                    : "Frequência BAIXA: " + bpm + " bpm";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onCalibrationProgress(int collected, int total) {
        runOnUiThread(() -> {
            tvStatus.setText("Calibrando... " + collected + "/" + total);
            tvStatus.setTextColor(Color.parseColor("#FFC107"));
            // Garante que a tela continua acesa durante cada leitura de calibração
            manterTelaAcesa(true);
        });
    }

    @Override
    public void onCalibrationComplete(int baseline, int min, int max) {
        runOnUiThread(() -> {
            // Calibração concluída: libera a tela para o comportamento normal de timeout
            manterTelaAcesa(false);
            btnCalibrar.setEnabled(true);
            btnCalibrar.setAlpha(1f);

            tvStatus.setText("Calibrado — baseline " + baseline + " bpm");
            tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            atualizarLimitesUI();

            Toast.makeText(this,
                    "Calibração concluída!\n"
                            + "Baseline: " + baseline + " bpm\n"
                            + "Intervalo normal: " + min + "–" + max + " bpm",
                    Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Watchdog interno esgotou todos os níveis de recuperação.
     * O HeartRateService já está reiniciando o monitor — a Activity só atualiza a UI.
     */
    @Override
    public void onNecessarioReiniciar() {
        runOnUiThread(() -> {
            tvStatus.setText("Reconectando sensor...");
            tvStatus.setTextColor(android.graphics.Color.parseColor("#FFC107"));
        });
    }

    /**
     * O dispositivo não possui sensor cardíaco ou ele se tornou irrecuperável.
     * Exibe um diálogo informativo e retorna à tela de seleção de papel (MainActivity).
     * O EverNear não pode funcionar sem sensor físico — não há modo simulado.
     */
    @Override
    public void onSensorIndisponivel() {
        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("Sensor cardíaco não encontrado")
                    .setMessage(
                            "O EverNear requer um sensor de frequência cardíaca para funcionar.\n\n"
                                    + "Este dispositivo não possui esse sensor ou ele não está respondendo.\n\n"
                                    + "O aplicativo não pode ser utilizado neste relógio.")
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setCancelable(false)
                    .setPositiveButton("Entendido", (dialog, which) -> {
                        // Volta para a seleção de papel e limpa a pilha
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                    })
                    .show();
        });
    }

    // ==================== UI ====================

    private void atualizarLimitesUI() {
        HeartRateService svc = HeartRateService.getInstance();
        if (svc != null && svc.getMonitor() != null) {
            tvLimites.setText("Limites: "
                    + svc.getMonitor().getBpmMin() + "–"
                    + svc.getMonitor().getBpmMax() + " bpm");
        } else {
            SharedPreferences p = getSharedPreferences("heart_rate_prefs", MODE_PRIVATE);
            tvLimites.setText("Limites: "
                    + p.getInt("bpm_min", 50) + "–"
                    + p.getInt("bpm_max", 120) + " bpm");
        }
    }

    private void atualizarStatusVisual(int bpm) {
        HeartRateService svc = HeartRateService.getInstance();
        if (svc == null || svc.getMonitor() == null) return;
        if (svc.getMonitor().isCalibrating()) return;

        int min = svc.getMonitor().getBpmMin();
        int max = svc.getMonitor().getBpmMax();

        if (bpm < min) {
            tvStatus.setText("ATENÇÃO — abaixo do normal");
            tvStatus.setTextColor(Color.parseColor("#FFC107"));
        } else if (bpm > max) {
            tvStatus.setText("ALERTA — acima do normal");
            tvStatus.setTextColor(Color.parseColor("#FF5252"));
        } else {
            tvStatus.setText("Normal");
            tvStatus.setTextColor(Color.parseColor("#4CAF50"));
        }
    }

    // ==================== Emergência manual ====================

    private void dispararEmergenciaManual() {
        HeartRateService svc = HeartRateService.getInstance();
        if (svc == null || svc.getCuidadoresVinculados().isEmpty()) {
            Toast.makeText(this, "Vincule-se a um cuidador primeiro",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // Usa lastBpm (atualizado pelo callback onHeartRate) em vez de parsear a TextView,
        // que pode conter "--" ou texto de calibração — evitando envio silencioso de bpm=0.
        svc.dispararEmergenciaManual(lastBpm);
        Toast.makeText(this, "Alerta de emergência enviado!", Toast.LENGTH_LONG).show();
    }
}

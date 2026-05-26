package com.marcoscarvalho.evernear;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

    private static final int REQ_BODY_SENSORS            = 1001;
    private static final int REQ_BODY_SENSORS_BACKGROUND = 1002;
    private static final int REQ_POST_NOTIFICATIONS      = 1003;

    private TextView tvBpmValue, tvStatus, tvLimites;
    private Button   btnEmergency, btnVerCodigo, btnCalibrar;

    // Controla se a tela deve ficar acesa (apenas durante calibração)
    private boolean telaAcesaParaCalibracao = false;

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

        btnVerCodigo.setOnClickListener(v ->
                startActivity(new Intent(this, DashboardPacienteActivity.class)));

        btnEmergency.setOnClickListener(v -> dispararEmergenciaManual());

        btnCalibrar.setOnClickListener(v -> iniciarCalibracao());

        atualizarLimitesUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HeartRateService.setActivityListener(this);
        verificarPermissoes();

        // Se a Activity voltou ao primeiro plano durante uma calibração em andamento,
        // mantém a tela acesa para não interromper
        HeartRateService svc = HeartRateService.getInstance();
        if (svc != null && svc.getMonitor() != null && svc.getMonitor().isCalibrating()) {
            manterTelaAcesa(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        HeartRateService.setActivityListener(null);
        // Remove a flag ao sair da tela — o serviço continua calibrando em background
        manterTelaAcesa(false);
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

    // ==================== Fluxo de permissões ====================

    private void verificarPermissoes() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BODY_SENSORS}, REQ_BODY_SENSORS);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS_BACKGROUND)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BODY_SENSORS_BACKGROUND},
                        REQ_BODY_SENSORS_BACKGROUND);
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIFICATIONS);
                return;
            }
        }
        iniciarServico();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQ_BODY_SENSORS:
                if (grantResults.length == 0
                        || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    tvStatus.setText("Permissão negada — usando simulador");
                }
                verificarPermissoes();
                break;
            case REQ_BODY_SENSORS_BACKGROUND:
                if (grantResults.length == 0
                        || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    tvStatus.setText("Sensor ativo (somente com tela aberta)");
                }
                verificarPermissoes();
                break;
            case REQ_POST_NOTIFICATIONS:
                iniciarServico();
                break;
        }
    }

    private void iniciarServico() {
        ContextCompat.startForegroundService(this, new Intent(this, HeartRateService.class));
        atualizarLimitesUI();
    }

    // ==================== Callbacks do HeartRateMonitor ====================

    @Override
    public void onHeartRate(int bpm) {
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
        int bpmAtual = 0;
        try { bpmAtual = Integer.parseInt(tvBpmValue.getText().toString()); }
        catch (NumberFormatException ignored) {}

        svc.dispararEmergenciaManual(bpmAtual);
        Toast.makeText(this, "Alerta de emergência enviado!", Toast.LENGTH_LONG).show();
    }
}

package com.marcoscarvalho.evernear;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;

/**
 * Tela principal do paciente no smartwatch.
 *
 * O monitoramento e o envio de alertas ao Firestore são feitos pelo HeartRateService
 * (processo em segundo plano). Esta Activity apenas:
 *  - Exibe o BPM em tempo real (via listener estático do serviço)
 *  - Aciona calibração / emergência manual via serviço
 */
public class PatientActivity extends AppCompatActivity implements HeartRateMonitor.Listener {

    private static final String TAG = "PatientActivity";
    private static final int REQ_BODY_SENSORS    = 1001;
    private static final int REQ_POST_NOTIFICATIONS = 1002;

    private TextView tvBpmValue, tvStatus, tvLimites;
    private Button btnEmergency, btnVerCodigo, btnCalibrar;

    private String uidPaciente;

    // ==================== Ciclo de vida ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient);

        tvBpmValue = findViewById(R.id.tv_bpm_value);
        tvStatus   = findViewById(R.id.tv_status);
        tvLimites  = findViewById(R.id.tv_limites);
        btnEmergency = findViewById(R.id.btn_emergency);
        btnVerCodigo = findViewById(R.id.btn_ver_codigo);
        btnCalibrar  = findViewById(R.id.btn_calibrar);

        uidPaciente = FirebaseAuth.getInstance().getUid();

        btnVerCodigo.setOnClickListener(v ->
                startActivity(new Intent(this, DashboardPacienteActivity.class)));

        // Emergência: delega ao serviço (que conhece todos os cuidadores vinculados)
        btnEmergency.setOnClickListener(v -> dispararEmergenciaManual());

        // Calibrar: envia Intent ao serviço
        btnCalibrar.setOnClickListener(v -> {
            Intent intent = new Intent(this, HeartRateService.class);
            intent.setAction(HeartRateService.ACTION_CALIBRAR);
            ContextCompat.startForegroundService(this, intent);
            Toast.makeText(this, "Calibração iniciada — fique em repouso",
                    Toast.LENGTH_LONG).show();
        });

        atualizarLimitesUI();
        verificarPermissoes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Registra esta Activity para receber atualizações em tempo real do serviço
        HeartRateService.setActivityListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Remove o listener — o serviço continua monitorando sem custo de UI
        HeartRateService.setActivityListener(null);
    }

    // ==================== Permissões ====================

    private void verificarPermissoes() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BODY_SENSORS}, REQ_BODY_SENSORS);
            return;
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
        if (requestCode == REQ_BODY_SENSORS) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                tvStatus.setText("Permissão de sensor negada — usando simulador");
            }
            verificarPermissoes(); // continua para POST_NOTIFICATIONS
        } else if (requestCode == REQ_POST_NOTIFICATIONS) {
            iniciarServico();
        }
    }

    private void iniciarServico() {
        ContextCompat.startForegroundService(this, new Intent(this, HeartRateService.class));
        atualizarLimitesUI();
    }

    // ==================== Callbacks do HeartRateMonitor (via Serviço) ====================

    @Override
    public void onHeartRate(int bpm) {
        tvBpmValue.setText(String.valueOf(bpm));
        atualizarStatusVisual(bpm);
    }

    @Override
    public void onStatusChange(String status) {
        tvStatus.setText(status);
        tvStatus.setTextColor(Color.parseColor("#9AA4B2"));
    }

    @Override
    public void onAnomaly(int bpm, HeartRateMonitor.AnomalyType tipo) {
        // Mostra mensagem local no relógio; o envio ao Firebase é feito pelo serviço
        String msg = tipo == HeartRateMonitor.AnomalyType.HIGH
                ? "Freq. ALTA: " + bpm + " bpm"
                : "Freq. BAIXA: " + bpm + " bpm";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onCalibrationProgress(int collected, int total) {
        tvStatus.setText("Calibrando... " + collected + "/" + total);
        tvStatus.setTextColor(Color.parseColor("#FFC107"));
    }

    @Override
    public void onCalibrationComplete(int baseline, int min, int max) {
        tvStatus.setText("Calibrado — baseline " + baseline + " bpm");
        tvStatus.setTextColor(Color.parseColor("#4CAF50"));
        atualizarLimitesUI();
        Toast.makeText(this,
                "Calibração concluída!\nBaseline: " + baseline
                        + " | Intervalo: " + min + "–" + max + " bpm",
                Toast.LENGTH_LONG).show();
    }

    // ==================== UI ====================

    private void atualizarLimitesUI() {
        HeartRateService svc = HeartRateService.getInstance();
        if (svc != null && svc.getMonitor() != null) {
            tvLimites.setText("Limites: "
                    + svc.getMonitor().getBpmMin() + "–"
                    + svc.getMonitor().getBpmMax() + " bpm");
        } else {
            SharedPreferences prefs = getSharedPreferences("heart_rate_prefs", MODE_PRIVATE);
            int min = prefs.getInt("bpm_min", 50);
            int max = prefs.getInt("bpm_max", 120);
            tvLimites.setText("Limites: " + min + "–" + max + " bpm");
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

        // Delega ao serviço — ele envia para TODOS os cuidadores simultaneamente
        svc.dispararEmergenciaManual(bpmAtual);
        Toast.makeText(this, "Alerta de emergência enviado!", Toast.LENGTH_LONG).show();
    }
}

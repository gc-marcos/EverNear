package com.marcoscarvalho.evernear;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

public class PatientActivity extends AppCompatActivity implements HeartRateMonitor.Listener {

    private static final String TAG = "PatientActivity";
    private static final int REQ_PERMISSIONS = 1001;

    private TextView tvBpmValue, tvStatus, tvLimites;
    private Button btnEmergency, btnVerCodigo, btnCalibrar;

    private HeartRateMonitor monitor;
    private String uidPaciente;
    private String nomePaciente = "Paciente";
    private String uidCuidadorVinculado;

    // Throttle: envia BPM ao Firestore no máximo a cada 5s
    private long lastFirestoreUpdate = 0;
    private static final long FIRESTORE_UPDATE_INTERVAL_MS = 5_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient);

        tvBpmValue = findViewById(R.id.tv_bpm_value);
        tvStatus = findViewById(R.id.tv_status);
        tvLimites = findViewById(R.id.tv_limites);
        btnEmergency = findViewById(R.id.btn_emergency);
        btnVerCodigo = findViewById(R.id.btn_ver_codigo);
        btnCalibrar = findViewById(R.id.btn_calibrar);

        uidPaciente = FirebaseAuth.getInstance().getUid();

        btnVerCodigo.setOnClickListener(v ->
                startActivity(new Intent(this, DashboardPacienteActivity.class)));

        btnEmergency.setOnClickListener(v -> dispararEmergenciaManual());

        btnCalibrar.setOnClickListener(v -> {
            if (monitor != null) {
                monitor.recalibrar();
                Toast.makeText(this, "Calibração iniciada — fique em repouso",
                        Toast.LENGTH_LONG).show();
            }
        });

        carregarDadosPaciente();
        verificarPermissoes();
    }

    private void carregarDadosPaciente() {
        if (uidPaciente == null) return;
        FirebaseFirestore.getInstance().collection("users").document(uidPaciente).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String n = doc.getString("nome");
                        if (n != null) nomePaciente = n;
                        uidCuidadorVinculado = doc.getString("cuidadorVinculado");
                    }
                });
    }

    // ==================== Permissões ====================

    private void verificarPermissoes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean bodySensors = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;

            if (!bodySensors) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BODY_SENSORS},
                        REQ_PERMISSIONS);
                return;
            }
        }
        iniciarMonitor();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                iniciarMonitor();
            } else {
                tvStatus.setText("Permissão BODY_SENSORS negada — usando simulador");
                iniciarMonitor(); // ainda inicia (cai no simulador)
            }
        }
    }

    // ==================== Ciclo de vida ====================

    private void iniciarMonitor() {
        if (monitor == null) {
            monitor = new HeartRateMonitor(this, this);
        }
        monitor.iniciar();
        atualizarLimitesUI();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Para economia de bateria: desliga o monitor quando a tela sai
        if (monitor != null) monitor.parar();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (monitor != null) monitor.iniciar();
    }

    // ==================== Callbacks do HeartRateMonitor ====================

    @Override
    public void onHeartRate(int bpm) {
        tvBpmValue.setText(String.valueOf(bpm));
        atualizarStatusVisual(bpm);

        // Atualiza Firestore com throttle (a cada 5s) para o cuidador acompanhar
        long agora = System.currentTimeMillis();
        if (uidPaciente != null && agora - lastFirestoreUpdate >= FIRESTORE_UPDATE_INTERVAL_MS) {
            lastFirestoreUpdate = agora;
            try {
                FirebaseHelper.atualizarBpm(uidPaciente, bpm);
            } catch (Exception e) {
                Log.w(TAG, "Falha ao atualizar BPM no Firestore: " + e.getMessage());
            }
        }
    }

    @Override
    public void onStatusChange(String status) {
        tvStatus.setText(status);
    }

    @Override
    public void onAnomaly(int bpm, HeartRateMonitor.AnomalyType tipo) {
        String mensagem = (tipo == HeartRateMonitor.AnomalyType.HIGH)
                ? "Frequência ALTA detectada: " + bpm + " bpm"
                : "Frequência BAIXA detectada: " + bpm + " bpm";
        Log.w(TAG, "ANOMALIA → " + mensagem);
        Toast.makeText(this, mensagem, Toast.LENGTH_LONG).show();

        // Tenta enviar alerta ao Firebase (não bloqueia se faltar cuidador)
        if (uidPaciente == null) return;
        if (uidCuidadorVinculado == null || uidCuidadorVinculado.isEmpty()) {
            Log.w(TAG, "Sem cuidador vinculado — alerta não enviado");
            return;
        }

        FirebaseHelper.enviarAlerta(uidPaciente, nomePaciente, uidCuidadorVinculado,
                bpm, tipo.name(), new FirebaseHelper.Callback<String>() {
                    @Override
                    public void onResult(String alertaId) {
                        Log.d(TAG, "Alerta enviado: " + alertaId);
                    }
                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Falha ao enviar alerta", e);
                    }
                });
    }

    @Override
    public void onCalibrationProgress(int collected, int total) {
        tvStatus.setText("Calibrando... " + collected + "/" + total);
    }

    @Override
    public void onCalibrationComplete(int baseline, int min, int max) {
        tvStatus.setText("Calibração concluída — baseline " + baseline + " bpm");
        atualizarLimitesUI();
        if (uidPaciente != null) {
            FirebaseHelper.salvarBaseline(uidPaciente, baseline, min, max);
        }
        Toast.makeText(this,
                "Calibrado! Baseline: " + baseline + " bpm (alerta abaixo de " + min + " ou acima de " + max + ")",
                Toast.LENGTH_LONG).show();
    }

    // ==================== UI ====================

    private void atualizarLimitesUI() {
        if (monitor != null) {
            tvLimites.setText("Limites: " + monitor.getBpmMin() + "–" + monitor.getBpmMax() + " bpm");
        }
    }

    private void atualizarStatusVisual(int bpm) {
        if (monitor == null) return;
        if (monitor.isCalibrating()) return; // status sobrescrito por progresso

        if (bpm < monitor.getBpmMin()) {
            tvStatus.setText("ATENÇÃO — abaixo do normal");
            tvStatus.setTextColor(Color.parseColor("#FFC107"));
        } else if (bpm > monitor.getBpmMax()) {
            tvStatus.setText("ALERTA — acima do normal");
            tvStatus.setTextColor(Color.parseColor("#FF5252"));
        } else {
            tvStatus.setText("Normal");
            tvStatus.setTextColor(Color.parseColor("#4CAF50"));
        }
    }

    private void dispararEmergenciaManual() {
        if (uidPaciente == null || uidCuidadorVinculado == null) {
            Toast.makeText(this, "Vincule-se a um cuidador primeiro", Toast.LENGTH_SHORT).show();
            return;
        }
        int bpmAtual = 0;
        try {
            bpmAtual = Integer.parseInt(tvBpmValue.getText().toString());
        } catch (NumberFormatException ignored) {}
        FirebaseHelper.enviarAlerta(uidPaciente, nomePaciente, uidCuidadorVinculado,
                bpmAtual, "MANUAL", new FirebaseHelper.Callback<String>() {
                    @Override
                    public void onResult(String alertaId) {
                        Toast.makeText(PatientActivity.this,
                                "Alerta de emergência enviado", Toast.LENGTH_LONG).show();
                    }
                    @Override
                    public void onError(Exception e) {
                        Toast.makeText(PatientActivity.this,
                                "Falha ao enviar alerta", Toast.LENGTH_LONG).show();
                    }
                });
    }
}

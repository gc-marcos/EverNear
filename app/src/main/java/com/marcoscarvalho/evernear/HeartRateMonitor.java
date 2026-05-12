package com.marcoscarvalho.evernear;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Random;


public class HeartRateMonitor implements SensorEventListener {

    private static final String TAG = "HeartRateMonitor";

    // SharedPreferences
    private static final String PREFS = "heart_rate_prefs";
    private static final String KEY_BASELINE = "baseline";
    private static final String KEY_MIN = "bpm_min";
    private static final String KEY_MAX = "bpm_max";
    private static final String KEY_CALIBRATED = "calibrated";

    // Parâmetros de calibração
    private static final int CALIBRATION_SAMPLES = 30;       // ~30 leituras para baseline
    private static final double THRESHOLD_PERCENT = 0.25;    // baseline ±25%
    private static final int DEFAULT_MIN = 50;
    private static final int DEFAULT_MAX = 120;
    private static final int ABSOLUTE_MIN = 40;              // limite de segurança absoluto
    private static final int ABSOLUTE_MAX = 180;

    // Detecção de anomalia
    private static final int CONSECUTIVE_READINGS_FOR_ALERT = 3;
    private static final long ALERT_COOLDOWN_MS = 60_000L;   // 60s entre alertas

    public enum AnomalyType { LOW, HIGH }

    public interface Listener {
        void onHeartRate(int bpm);
        void onStatusChange(String status);
        void onAnomaly(int bpm, AnomalyType tipo);
        void onCalibrationComplete(int baseline, int min, int max);
        void onCalibrationProgress(int collected, int total);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    // Sensor
    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private boolean usingSensor = false;

    // Simulador (fallback)
    private Runnable simulatorRunnable;
    private final Random random = new Random();
    private int simulatorBpm = 75;

    // Calibração
    private boolean calibrating = false;
    private int calibrationCount = 0;
    private int calibrationSum = 0;

    // Limites atuais
    private int bpmMin;
    private int bpmMax;
    private int baseline;

    // Anomalia
    private int consecutiveOutOfRange = 0;
    private long lastAlertTime = 0;
    private AnomalyType lastAnomalyType = null;

    // Throttle de leituras (economia de bateria + processamento)
    private long lastReadingTime = 0;
    private static final long MIN_READING_INTERVAL_MS = 1000L; // máx 1 leitura/seg

    public HeartRateMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        carregarLimites();
    }

    private void carregarLimites() {
        baseline = prefs.getInt(KEY_BASELINE, 75);
        bpmMin = prefs.getInt(KEY_MIN, DEFAULT_MIN);
        bpmMax = prefs.getInt(KEY_MAX, DEFAULT_MAX);

        // Se nunca foi calibrado, agenda calibração na primeira execução
        if (!prefs.getBoolean(KEY_CALIBRATED, false)) {
            calibrating = true;
            calibrationCount = 0;
            calibrationSum = 0;
        }
    }

    public int getBpmMin() { return bpmMin; }
    public int getBpmMax() { return bpmMax; }
    public int getBaseline() { return baseline; }
    public boolean isCalibrating() { return calibrating; }

    /** Inicia uma nova calibração manual (botão "Calibrar"). */
    public void recalibrar() {
        calibrating = true;
        calibrationCount = 0;
        calibrationSum = 0;
        notifyStatus("Calibrando — fique em repouso...");
    }

    /** Inicia o monitoramento (escolhe a melhor estratégia disponível). */
    public void iniciar() {
        if (tentarSensorManager()) {
            Log.d(TAG, "Usando SensorManager (TYPE_HEART_RATE)");
            return;
        }
        Log.w(TAG, "Sem sensor disponível — usando simulador");
//        iniciarSimulador();
    }

    public void parar() {
        if (usingSensor && sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (simulatorRunnable != null) {
            mainHandler.removeCallbacks(simulatorRunnable);
            simulatorRunnable = null;
        }
        usingSensor = false;
    }

    // ==================== SensorManager (Wear OS / Android nativo) ====================

    private boolean tentarSensorManager() {
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) {
                Log.w(TAG, "SensorManager indisponível");
                return false;
            }
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (heartRateSensor == null) {
                Log.w(TAG, "Sensor TYPE_HEART_RATE não encontrado neste dispositivo");
                return false;
            }

            // SENSOR_DELAY_NORMAL ≈ 200ms entre leituras → bom equilíbrio bateria/responsividade
            boolean ok = sensorManager.registerListener(
                    this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
            if (ok) {
                usingSensor = true;
                notifyStatus("Sensor cardíaco ativo");
                return true;
            }
            Log.w(TAG, "registerListener retornou false");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar SensorManager", e);
            return false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HEART_RATE) return;
        if (event.values.length == 0) return;

        int bpm = (int) event.values[0];
        if (bpm <= 0) return; // valores 0 indicam que o sensor ainda não está pronto/contato ruim

        processarLeitura(bpm);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() != Sensor.TYPE_HEART_RATE) return;
        switch (accuracy) {
            case SensorManager.SENSOR_STATUS_NO_CONTACT:
                notifyStatus("Sem contato com a pele");
                break;
            case SensorManager.SENSOR_STATUS_UNRELIABLE:
                notifyStatus("Leitura instável — ajuste o relógio");
                break;
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                notifyStatus("Precisão baixa");
                break;
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                notifyStatus("Sensor cardíaco ativo");
                break;
        }
    }

    // ==================== Simulador (apenas testes) ====================

    private void iniciarSimulador() {
        notifyStatus("Modo simulação (sem sensor)");
        simulatorRunnable = new Runnable() {
            @Override
            public void run() {
                // Variação aleatória ±5 bpm, com chance pequena de pico/queda
                int delta = random.nextInt(11) - 5;
                simulatorBpm = Math.max(45, Math.min(160, simulatorBpm + delta));
                if (random.nextInt(50) == 0) simulatorBpm += random.nextInt(40) - 20; // ocasional
                simulatorBpm = Math.max(40, Math.min(180, simulatorBpm));
                processarLeitura(simulatorBpm);
                mainHandler.postDelayed(this, 2000);
            }
        };
        mainHandler.postDelayed(simulatorRunnable, 1000);
    }

    // ==================== Processamento Comum ====================

    private void processarLeitura(int bpm) {
        long agora = System.currentTimeMillis();
        if (agora - lastReadingTime < MIN_READING_INTERVAL_MS) return;
        lastReadingTime = agora;

        // Notifica UI
        mainHandler.post(() -> listener.onHeartRate(bpm));

        // Calibração em andamento
        if (calibrating) {
            calibrationSum += bpm;
            calibrationCount++;
            mainHandler.post(() -> listener.onCalibrationProgress(calibrationCount, CALIBRATION_SAMPLES));

            if (calibrationCount >= CALIBRATION_SAMPLES) {
                concluirCalibracao();
            }
            return; // não detecta anomalia durante calibração
        }

        // Detecção de anomalia
        if (bpm < bpmMin || bpm > bpmMax) {
            consecutiveOutOfRange++;
            AnomalyType tipo = bpm < bpmMin ? AnomalyType.LOW : AnomalyType.HIGH;

            if (consecutiveOutOfRange >= CONSECUTIVE_READINGS_FOR_ALERT) {
                // Debounce: só alerta se passou cooldown OU mudou o tipo
                boolean cooldownOk = (agora - lastAlertTime) >= ALERT_COOLDOWN_MS;
                boolean tipoMudou = (tipo != lastAnomalyType);
                if (cooldownOk || tipoMudou) {
                    lastAlertTime = agora;
                    lastAnomalyType = tipo;
                    final int bpmFinal = bpm;
                    mainHandler.post(() -> listener.onAnomaly(bpmFinal, tipo));
                }
            }
        } else {
            consecutiveOutOfRange = 0;
            lastAnomalyType = null;
        }
    }

    private void concluirCalibracao() {
        baseline = calibrationSum / Math.max(1, calibrationCount);

        // Limites = baseline ±25%, respeitando piso/teto absolutos de segurança
        int novoMin = (int) Math.max(ABSOLUTE_MIN, baseline * (1.0 - THRESHOLD_PERCENT));
        int novoMax = (int) Math.min(ABSOLUTE_MAX, baseline * (1.0 + THRESHOLD_PERCENT));
        bpmMin = novoMin;
        bpmMax = novoMax;

        prefs.edit()
                .putInt(KEY_BASELINE, baseline)
                .putInt(KEY_MIN, bpmMin)
                .putInt(KEY_MAX, bpmMax)
                .putBoolean(KEY_CALIBRATED, true)
                .apply();

        calibrating = false;
        calibrationCount = 0;
        calibrationSum = 0;
        consecutiveOutOfRange = 0;

        mainHandler.post(() -> listener.onCalibrationComplete(baseline, bpmMin, bpmMax));
    }

    private void notifyStatus(String s) {
        mainHandler.post(() -> listener.onStatusChange(s));
    }
}

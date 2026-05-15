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

/**
 * Gerencia a leitura de frequência cardíaca via SensorManager (Wear OS / Android).
 *
 * ┌─ Estratégia de leitura ──────────────────────────────────────────────────┐
 * │  1. Tenta registrar o sensor TYPE_HEART_RATE do hardware.                │
 * │     - samplingPeriodUs = SENSOR_DELAY_NORMAL (~200 ms)                   │
 * │     - maxReportLatencyNs = 0 → sem batching; entrega imediata.           │
 * │       (Batching economiza bateria, mas atrasa leituras críticas.)        │
 * │  2. Se o sensor não estiver disponível (emulador ou dispositivo sem       │
 * │     suporte), usa simulador com variação realista de BPM.                │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Detecção de anomalia ───────────────────────────────────────────────────┐
 * │  - Requer CONSECUTIVE_READINGS_FOR_ALERT leituras consecutivas fora do  │
 * │    intervalo antes de disparar (evita falsos positivos por spike único). │
 * │  - Cooldown de 60 s entre alertas do mesmo tipo.                        │
 * │  - Mudança de tipo (LOW → HIGH) ignora o cooldown.                      │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
public class HeartRateMonitor implements SensorEventListener {

    private static final String TAG = "HeartRateMonitor";

    // SharedPreferences
    private static final String PREFS         = "heart_rate_prefs";
    private static final String KEY_BASELINE  = "baseline";
    private static final String KEY_MIN       = "bpm_min";
    private static final String KEY_MAX       = "bpm_max";
    private static final String KEY_CALIBRATED = "calibrated";

    // Calibração
    private static final int    CALIBRATION_SAMPLES  = 30;
    private static final double THRESHOLD_PERCENT     = 0.25;  // baseline ±25 %
    private static final int    DEFAULT_MIN           = 50;
    private static final int    DEFAULT_MAX           = 120;
    private static final int    ABSOLUTE_MIN          = 40;
    private static final int    ABSOLUTE_MAX          = 180;

    // Detecção de anomalia
    private static final int  CONSECUTIVE_READINGS_FOR_ALERT = 3;
    private static final long ALERT_COOLDOWN_MS               = 60_000L;

    // Throttle de leituras: evita processar 200 ms/leitura × continuamente
    private static final long MIN_READING_INTERVAL_MS = 1_000L; // máx 1 leitura/s

    public enum AnomalyType { LOW, HIGH }

    public interface Listener {
        void onHeartRate(int bpm);
        void onStatusChange(String status);
        void onAnomaly(int bpm, AnomalyType tipo);
        void onCalibrationComplete(int baseline, int min, int max);
        void onCalibrationProgress(int collected, int total);
    }

    private final Context  context;
    private final Listener listener;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    // Sensor de hardware
    private SensorManager sensorManager;
    private Sensor        heartRateSensor;
    private boolean       usingSensor = false;

    // Simulador de fallback
    private Runnable simulatorRunnable;
    private final Random random = new Random();
    private int simulatorBpm = 75;

    // Estado de calibração
    private boolean calibrating       = false;
    private int     calibrationCount  = 0;
    private int     calibrationSum    = 0;

    // Limites em uso
    private int bpmMin;
    private int bpmMax;
    private int baseline;

    // Detecção de anomalia
    private int         consecutiveOutOfRange = 0;
    private long        lastAlertTime         = 0;
    private AnomalyType lastAnomalyType       = null;

    // Throttle
    private long lastReadingTime = 0;

    // ==================== Construtor ====================

    public HeartRateMonitor(Context context, Listener listener) {
        this.context  = context.getApplicationContext();
        this.listener = listener;
        this.prefs    = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        carregarLimites();
    }

    // ==================== API pública ====================

    public int  getBpmMin()      { return bpmMin; }
    public int  getBpmMax()      { return bpmMax; }
    public int  getBaseline()    { return baseline; }
    public boolean isCalibrating() { return calibrating; }

    /** Inicia nova calibração manual (botão "Calibrar" na PatientActivity). */
    public void recalibrar() {
        calibrating      = true;
        calibrationCount = 0;
        calibrationSum   = 0;
        notifyStatus("Calibrando — fique em repouso...");
    }

    /**
     * Inicia o monitoramento.
     * Estratégia: tenta sensor de hardware primeiro; usa simulador como fallback.
     */
    public void iniciar() {
        if (tentarSensorManager()) {
            Log.d(TAG, "SensorManager ativo — TYPE_HEART_RATE registrado com sucesso");
            return;
        }
        // Sensor de hardware indisponível (emulador ou dispositivo sem suporte)
        Log.w(TAG, "Sensor de hardware indisponível — ativando simulador de BPM");
        iniciarSimulador();
    }

    /** Para o monitoramento e libera recursos. */
    public void parar() {
        if (usingSensor && sensorManager != null) {
            sensorManager.unregisterListener(this);
            Log.d(TAG, "SensorManager: listener removido");
        }
        if (simulatorRunnable != null) {
            mainHandler.removeCallbacks(simulatorRunnable);
            simulatorRunnable = null;
        }
        usingSensor = false;
    }

    // ==================== SensorManager ====================

    private boolean tentarSensorManager() {
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) {
                Log.w(TAG, "SensorManager indisponível no sistema");
                return false;
            }

            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (heartRateSensor == null) {
                Log.w(TAG, "Sensor TYPE_HEART_RATE não encontrado neste dispositivo");
                return false;
            }

            // maxReportLatencyUs = 0 → sem batching; entrega cada leitura imediatamente.
            // SENSOR_DELAY_NORMAL ≈ 200 ms entre amostras — bom equilíbrio entre
            // responsividade e consumo de bateria.
            boolean ok = sensorManager.registerListener(
                    this,
                    heartRateSensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    0 /* maxReportLatencyUs — 0 = sem batching */);

            if (ok) {
                usingSensor = true;
                notifyStatus("Sensor cardíaco ativo");
                return true;
            }
            Log.w(TAG, "registerListener retornou false");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar SensorManager: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HEART_RATE) return;
        if (event.values.length == 0) return;

        int bpm = (int) event.values[0];
        if (bpm <= 0) return; // 0 = sensor sem contato ou não pronto ainda

        processarLeitura(bpm);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() != Sensor.TYPE_HEART_RATE) return;
        switch (accuracy) {
            case SensorManager.SENSOR_STATUS_NO_CONTACT:
                notifyStatus("Sem contato — ajuste o relógio no pulso");
                break;
            case SensorManager.SENSOR_STATUS_UNRELIABLE:
                notifyStatus("Leitura instável — ajuste o relógio");
                break;
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                notifyStatus("Precisão baixa — mantendo monitoramento");
                break;
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                notifyStatus("Sensor cardíaco ativo");
                break;
        }
    }

    // ==================== Simulador de fallback ====================

    /**
     * Gera BPM simulado com variação realista.
     * Usado apenas quando não há sensor de hardware disponível (ex: emulador).
     * NÃO é usado em produção com relógio físico.
     */
    private void iniciarSimulador() {
        notifyStatus("Modo simulação (sem sensor de hardware)");
        simulatorRunnable = new Runnable() {
            @Override
            public void run() {
                // Variação normal ±3 bpm, spike ocasional para testes de anomalia
                int delta = random.nextInt(7) - 3;
                simulatorBpm = Math.max(40, Math.min(180, simulatorBpm + delta));

                // 2% de chance de gerar um valor fora do intervalo (para testes)
                if (random.nextInt(50) == 0) {
                    simulatorBpm += (random.nextBoolean() ? 1 : -1) * (25 + random.nextInt(20));
                    simulatorBpm = Math.max(40, Math.min(180, simulatorBpm));
                }

                processarLeitura(simulatorBpm);
                mainHandler.postDelayed(this, 2_000L); // 1 leitura a cada 2 s
            }
        };
        mainHandler.postDelayed(simulatorRunnable, 1_000L);
    }

    // ==================== Processamento comum ====================

    private void processarLeitura(int bpm) {
        long agora = System.currentTimeMillis();
        if (agora - lastReadingTime < MIN_READING_INTERVAL_MS) return;
        lastReadingTime = agora;

        // Notifica UI / serviço com o BPM bruto
        mainHandler.post(() -> listener.onHeartRate(bpm));

        // Durante calibração: acumula amostras, não detecta anomalia
        if (calibrating) {
            calibrationSum += bpm;
            calibrationCount++;
            final int contagem = calibrationCount;
            mainHandler.post(() -> listener.onCalibrationProgress(contagem, CALIBRATION_SAMPLES));
            if (calibrationCount >= CALIBRATION_SAMPLES) concluirCalibracao();
            return;
        }

        // Detecção de anomalia: requer N leituras consecutivas fora do intervalo
        if (bpm < bpmMin || bpm > bpmMax) {
            consecutiveOutOfRange++;
            AnomalyType tipo = bpm < bpmMin ? AnomalyType.LOW : AnomalyType.HIGH;

            if (consecutiveOutOfRange >= CONSECUTIVE_READINGS_FOR_ALERT) {
                boolean cooldownOk = (agora - lastAlertTime) >= ALERT_COOLDOWN_MS;
                boolean tipoMudou  = (tipo != lastAnomalyType);

                if (cooldownOk || tipoMudou) {
                    lastAlertTime    = agora;
                    lastAnomalyType  = tipo;
                    final int bpmFinal = bpm;
                    mainHandler.post(() -> listener.onAnomaly(bpmFinal, tipo));
                }
            }
        } else {
            // BPM voltou ao normal — reseta contador
            consecutiveOutOfRange = 0;
            lastAnomalyType       = null;
        }
    }

    // ==================== Calibração ====================

    private void carregarLimites() {
        baseline = prefs.getInt(KEY_BASELINE, 75);
        bpmMin   = prefs.getInt(KEY_MIN, DEFAULT_MIN);
        bpmMax   = prefs.getInt(KEY_MAX, DEFAULT_MAX);

        // Primeira execução (sem calibração prévia): agenda calibração automática
        if (!prefs.getBoolean(KEY_CALIBRATED, false)) {
            calibrating      = true;
            calibrationCount = 0;
            calibrationSum   = 0;
        }
    }

    private void concluirCalibracao() {
        baseline = calibrationSum / Math.max(1, calibrationCount);

        // Limites = baseline ±25%, respeitando piso/teto absolutos de segurança
        bpmMin = (int) Math.max(ABSOLUTE_MIN, baseline * (1.0 - THRESHOLD_PERCENT));
        bpmMax = (int) Math.min(ABSOLUTE_MAX, baseline * (1.0 + THRESHOLD_PERCENT));

        prefs.edit()
                .putInt(KEY_BASELINE,  baseline)
                .putInt(KEY_MIN,       bpmMin)
                .putInt(KEY_MAX,       bpmMax)
                .putBoolean(KEY_CALIBRATED, true)
                .apply();

        calibrating      = false;
        calibrationCount = 0;
        calibrationSum   = 0;
        consecutiveOutOfRange = 0;

        final int b = baseline, mn = bpmMin, mx = bpmMax;
        mainHandler.post(() -> listener.onCalibrationComplete(b, mn, mx));
    }

    // ==================== Utilitários ====================

    private void notifyStatus(String s) {
        mainHandler.post(() -> listener.onStatusChange(s));
    }
}

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

import androidx.health.services.client.HealthServices;
import androidx.health.services.client.HealthServicesClient;
import androidx.health.services.client.MeasureCallback;
import androidx.health.services.client.MeasureClient;
import androidx.health.services.client.data.Availability;
import androidx.health.services.client.data.DataPointContainer;
import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.DataTypeAvailability;
import androidx.health.services.client.data.MeasureCapabilities;
import androidx.health.services.client.data.SampleDataPoint;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;
import java.util.Random;

/**
 * Monitor de frequência cardíaca em tempo real.
 *
 * Estratégia (em cascata, escolhendo a primeira disponível):
 *  1. Health Services API (Wear OS) — preferencial em smartwatches
 *  2. SensorManager TYPE_HEART_RATE — fallback para wearables Android
 *  3. Simulador — para emuladores e dispositivos sem sensor
 *
 * Calibração:
 *  - Coleta automática nas primeiras N leituras → cria baseline
 *  - Limites mínimo/máximo derivados do baseline (±25%) ou valores absolutos
 *  - Botão "Calibrar" recoleta o baseline em repouso
 *
 * Detecção de anomalia:
 *  - 3 leituras consecutivas fora do intervalo → alerta
 *  - Cooldown de 60s entre alertas para evitar spam
 */
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

    // Health Services
    private MeasureClient measureClient;
    private MeasureCallback hsCallback;
    private boolean usingHealthServices = false;

    // Sensor fallback
    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private boolean usingSensor = false;

    // Simulator fallback
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

    // Throttle de UI/Firestore
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
        if (tentarHealthServices()) {
            Log.d(TAG, "Usando Health Services API");
            return;
        }
        if (tentarSensorManager()) {
            Log.d(TAG, "Usando SensorManager (TYPE_HEART_RATE)");
            return;
        }
        Log.w(TAG, "Sem sensor disponível — usando simulador");
        iniciarSimulador();
    }

    public void parar() {
        if (usingHealthServices && measureClient != null && hsCallback != null) {
            try {
                measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, hsCallback);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao desregistrar Health Services", e);
            }
        }
        if (usingSensor && sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (simulatorRunnable != null) {
            mainHandler.removeCallbacks(simulatorRunnable);
            simulatorRunnable = null;
        }
        usingHealthServices = false;
        usingSensor = false;
    }

    // ==================== Health Services API (Wear OS) ====================

    private boolean tentarHealthServices() {
        try {
            HealthServicesClient client = HealthServices.getClient(context);
            measureClient = client.getMeasureClient();

            ListenableFuture<MeasureCapabilities> future = measureClient.getCapabilitiesAsync();
            Futures.addCallback(future, new FutureCallback<MeasureCapabilities>() {
                @Override
                public void onSuccess(MeasureCapabilities capabilities) {
                    if (capabilities != null
                            && capabilities.getSupportedDataTypesMeasure().contains(DataType.HEART_RATE_BPM)) {
                        registrarHealthServicesCallback();
                    } else {
                        Log.w(TAG, "HEART_RATE_BPM não suportado — fallback");
                        if (!tentarSensorManager()) iniciarSimulador();
                    }
                }
                @Override
                public void onFailure(Throwable t) {
                    Log.e(TAG, "Falha ao obter capabilities — fallback", t);
                    if (!tentarSensorManager()) iniciarSimulador();
                }
            }, MoreExecutors.directExecutor());

            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Health Services indisponível: " + t.getMessage());
            return false;
        }
    }

    private void registrarHealthServicesCallback() {
        hsCallback = new MeasureCallback() {
            @Override
            public void onAvailabilityChanged(DataType<?, ?> dataType, Availability availability) {
                if (availability instanceof DataTypeAvailability) {
                    DataTypeAvailability dta = (DataTypeAvailability) availability;
                    notifyStatus("Sensor: " + dta.toString());
                }
            }
            @Override
            public void onDataReceived(DataPointContainer data) {
                List<SampleDataPoint<Double>> pontos = data.getData(DataType.HEART_RATE_BPM);
                for (SampleDataPoint<Double> p : pontos) {
                    int bpm = (int) Math.round(p.getValue());
                    if (bpm > 0) processarLeitura(bpm);
                }
            }
        };
        try {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, hsCallback);
            usingHealthServices = true;
            notifyStatus("Conectado ao sensor cardíaco");
        } catch (Exception e) {
            Log.e(TAG, "Falha ao registrar callback Health Services", e);
            if (!tentarSensorManager()) iniciarSimulador();
        }
    }

    // ==================== SensorManager Fallback ====================

    private boolean tentarSensorManager() {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return false;
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        if (heartRateSensor == null) return false;

        boolean ok = sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (ok) {
            usingSensor = true;
            notifyStatus("Sensor cardíaco ativo");
            return true;
        }
        return false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_HEART_RATE && event.values.length > 0) {
            int bpm = (int) event.values[0];
            if (bpm > 0) processarLeitura(bpm);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }

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

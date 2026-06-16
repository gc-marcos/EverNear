package com.marcoscarvalho.evernear;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.Random;

/**
 * Gerencia a leitura de frequência cardíaca via SensorManager (Wear OS / Android).
 *
 * ┌─ Estratégia para monitoramento contínuo com tela bloqueada ─────────────┐
 * │  Problema: no Wear OS, quando a tela apaga, o sensor ótico de HR pode   │
 * │  parar de entregar eventos mesmo com PARTIAL_WAKE_LOCK adquirido.        │
 * │                                                                           │
 * │  Solução — watchdog em HandlerThread dedicado:                           │
 * │  • Um thread de background (sensorThread) roda separado da UI.           │
 * │  • A cada WATCHDOG_INTERVAL_MS (10 s), verifica se chegaram leituras.    │
 * │  • Se o sensor ficou SENSOR_SILENCE_MS (10 s) sem eventos → remove e    │
 * │    registra novamente o listener, forçando o sensor a acordar.           │
 * │  • O HandlerThread mantém a CPU ativa por conta própria (não depende    │
 * │    do looper principal que pode ser throttled com a tela apagada).       │
 * └────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Simulador de fallback ──────────────────────────────────────────────────┐
 * │  Também roda no HandlerThread, não no looper principal. Isso evita que   │
 * │  o Android atrase mensagens do main looper quando a tela está apagada.   │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
public class HeartRateMonitor implements SensorEventListener {

    private static final String TAG = "HeartRateMonitor";

    // SharedPreferences
    private static final String PREFS          = "heart_rate_prefs";
    private static final String KEY_BASELINE   = "baseline";
    private static final String KEY_MIN        = "bpm_min";
    private static final String KEY_MAX        = "bpm_max";
    private static final String KEY_CALIBRATED = "calibrated";

    // Calibração
    private static final int    CALIBRATION_SAMPLES = 30;
    private static final double THRESHOLD_PERCENT    = 0.25;  // baseline ±25 %
    private static final int    DEFAULT_MIN          = 50;
    private static final int    DEFAULT_MAX          = 120;
    private static final int    ABSOLUTE_MIN         = 40;
    private static final int    ABSOLUTE_MAX         = 180;

    // Detecção de anomalia
    private static final int  CONSECUTIVE_READINGS_FOR_ALERT = 3;
    private static final long ALERT_COOLDOWN_MS               = 60_000L;

    // Throttle de leituras do sensor físico (evita flood do hardware)
    private static final long MIN_READING_INTERVAL_MS = 1_000L; // 1 leitura processada/s

    // Watchdog — detecta silêncio do sensor e re-registra o listener
    private static final long WATCHDOG_INTERVAL_MS  = 10_000L; // checa a cada 10 s
    private static final long SENSOR_SILENCE_MS     = 10_000L; // considera morto após 10 s sem leitura

    // Simulador de fallback — intervalo entre leituras simuladas
    private static final long SIMULATOR_INTERVAL_MS = 3_000L;  // 1 leitura a cada 3 s (para testes)

    public enum AnomalyType { LOW, HIGH }

    public interface Listener {
        void onHeartRate(int bpm);
        void onStatusChange(String status);
        void onAnomaly(int bpm, AnomalyType tipo);
        void onCalibrationComplete(int baseline, int min, int max);
        void onCalibrationProgress(int collected, int total);
    }

    private final Context            context;
    private final Listener           listener;
    private final Handler            mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences  prefs;

    // Thread dedicado para watchdog e simulador (não bloqueia UI; sobrevive ao throttling do main looper)
    private HandlerThread sensorThread;
    private Handler       bgHandler;

    // Sensor de hardware
    private SensorManager sensorManager;
    private Sensor        heartRateSensor;
    private volatile boolean usingSensor = false;

    // Simulador de fallback
    private Runnable simulatorRunnable;
    private final Random random = new Random();
    private int simulatorBpm = 75;

    // Estado de calibração
    private volatile boolean calibrating      = false;
    private int              calibrationCount = 0;
    private int              calibrationSum   = 0;

    // Limites em uso
    private int bpmMin;
    private int bpmMax;
    private int baseline;

    // Detecção de anomalia
    private int         consecutiveOutOfRange = 0;
    private long        lastAlertTime         = 0;
    private AnomalyType lastAnomalyType       = null;

    // Throttle de leituras do sensor físico
    private volatile long lastReadingTime = 0;

    // Watchdog: timestamp da última leitura entregue pelo sensor de hardware
    private volatile long lastSensorEventTime = System.currentTimeMillis();

    // ==================== Construtor ====================

    public HeartRateMonitor(Context context, Listener listener) {
        this.context  = context.getApplicationContext();
        this.listener = listener;
        this.prefs    = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        carregarLimites();
        iniciarThreadDedIcado();
    }

    // ==================== API pública ====================

    public int     getBpmMin()     { return bpmMin; }
    public int     getBpmMax()     { return bpmMax; }
    public int     getBaseline()   { return baseline; }
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
     * Tenta sensor de hardware primeiro; usa simulador como fallback.
     */
    public void iniciar() {
        if (tentarSensorManager()) {
            Log.d(TAG, "SensorManager ativo — TYPE_HEART_RATE registrado");
            iniciarWatchdog();
            return;
        }
        Log.w(TAG, "Sensor de hardware indisponível — ativando simulador");
        iniciarSimulador();
    }

    /** Para o monitoramento e libera todos os recursos. */
    public void parar() {
        // Remove watchdog e simulador
        if (bgHandler != null) bgHandler.removeCallbacksAndMessages(null);

        // Desregistra sensor de hardware
        if (usingSensor && sensorManager != null) {
            try { sensorManager.unregisterListener(this); }
            catch (Exception ignored) {}
            Log.d(TAG, "SensorManager: listener removido");
        }
        usingSensor = false;
        simulatorRunnable = null;

        // Encerra thread de background
        if (sensorThread != null) {
            sensorThread.quitSafely();
            sensorThread = null;
        }
    }

    // ==================== Thread dedicado ====================

    /**
     * HandlerThread: thread de background permanente.
     * Roda o watchdog e o simulador — separado do looper principal para não
     * ser throttled quando a tela do Wear OS apaga.
     */
    private void iniciarThreadDedIcado() {
        sensorThread = new HandlerThread("EverNear-SensorThread",
                android.os.Process.THREAD_PRIORITY_FOREGROUND);
        sensorThread.start();
        bgHandler = new Handler(sensorThread.getLooper());
    }

    // ==================== SensorManager ====================

    private boolean tentarSensorManager() {
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) return false;

            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (heartRateSensor == null) {
                Log.w(TAG, "Sensor TYPE_HEART_RATE não encontrado neste dispositivo");
                return false;
            }

            return registrarListenerSensor();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar SensorManager: " + e.getMessage());
            return false;
        }
    }

    /**
     * Registra o listener no sensor de hardware.
     * Chamado tanto na inicialização quanto pelo watchdog ao re-registrar.
     *
     * CRÍTICO — por que usamos bgHandler aqui:
     * A sobrecarga registerListener(listener, sensor, delay, handler) entrega os eventos
     * SensorChanged diretamente no looper do handler fornecido. Sem um handler explícito,
     * os eventos vão para o main looper — que o Wear OS throttle (reduz drasticamente
     * a frequência) quando a tela apaga, podendo pausar o sensor completamente.
     *
     * Com bgHandler (HandlerThread "EverNear-SensorThread"), os eventos chegam ao thread
     * dedicado que roda com prioridade FOREGROUND e não é afetado pelo throttling da UI,
     * garantindo leituras contínuas mesmo com a tela do relógio apagada.
     */
    private boolean registrarListenerSensor() {
        if (sensorManager == null || heartRateSensor == null || bgHandler == null) return false;
        try {
            // Registra no bgHandler: sensor entrega eventos no thread dedicado, não no main looper
            boolean ok = sensorManager.registerListener(
                    this,
                    heartRateSensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    bgHandler);

            if (ok) {
                usingSensor = true;
                lastSensorEventTime = System.currentTimeMillis();
                notifyStatus("Sensor cardíaco ativo");
                Log.d(TAG, "registerListener OK → entrega no EverNear-SensorThread");
                return true;
            }
            Log.w(TAG, "registerListener retornou false");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao registrar listener do sensor: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HEART_RATE) return;
        if (event.values.length == 0) return;

        int bpm = (int) event.values[0];
        if (bpm <= 0) return; // sensor sem contato ou não inicializado

        // Atualiza timestamp do watchdog (sensor está vivo)
        lastSensorEventTime = System.currentTimeMillis();

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

    // ==================== Watchdog do sensor ====================

    /**
     * Watchdog executado no HandlerThread (não na UI).
     *
     * A cada WATCHDOG_INTERVAL_MS verifica se o sensor ainda está entregando leituras.
     * Se o silêncio ultrapassar SENSOR_SILENCE_MS (10 s), o listener é removido e
     * re-registrado — isso força o sensor ótico a acordar mesmo após modo ambient/sleep
     * do Wear OS, sem precisar reiniciar o serviço inteiro.
     */
    private void iniciarWatchdog() {
        bgHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!usingSensor || sensorManager == null) return;

                long silencioMs = System.currentTimeMillis() - lastSensorEventTime;
                if (silencioMs > SENSOR_SILENCE_MS) {
                    Log.w(TAG, "Watchdog: " + (silencioMs / 1000) + "s sem leitura — re-registrando sensor");
                    try {
                        sensorManager.unregisterListener(HeartRateMonitor.this);
                    } catch (Exception ignored) {}
                    boolean ok = registrarListenerSensor();
                    if (!ok) {
                        Log.w(TAG, "Watchdog: re-registro falhou — ativando simulador");
                        usingSensor = false;
                        iniciarSimulador();
                        return; // sai do watchdog: simulador assume
                    }
                }
                // Agenda próxima verificação
                bgHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        }, WATCHDOG_INTERVAL_MS);
    }

    // ==================== Simulador de fallback ====================

    /**
     * Gera BPM simulado com variação realista.
     * Roda no HandlerThread (bgHandler) — não no main looper — para não ser
     * throttled quando a tela apaga no Wear OS.
     * Intervalo: SIMULATOR_INTERVAL_MS (3 s para testes).
     */
    private void iniciarSimulador() {
        if (bgHandler == null) return;
        notifyStatus("Modo simulação — sensor indisponível");

        simulatorRunnable = new Runnable() {
            @Override
            public void run() {
                if (simulatorRunnable == null) return; // foi parado

                // Variação normal ±3 bpm
                int delta = random.nextInt(7) - 3;
                simulatorBpm = Math.max(40, Math.min(180, simulatorBpm + delta));

                // 2% de chance de gerar spike fora do intervalo (para testes de alerta)
                if (random.nextInt(50) == 0) {
                    simulatorBpm += (random.nextBoolean() ? 1 : -1) * (25 + random.nextInt(20));
                    simulatorBpm = Math.max(40, Math.min(180, simulatorBpm));
                }

                processarLeitura(simulatorBpm);
                if (simulatorRunnable != null) {
                    bgHandler.postDelayed(this, SIMULATOR_INTERVAL_MS);
                }
            }
        };
        bgHandler.postDelayed(simulatorRunnable, 1_000L);
    }

    // ==================== Processamento comum ====================

    private void processarLeitura(int bpm) {
        long agora = System.currentTimeMillis();
        if (agora - lastReadingTime < MIN_READING_INTERVAL_MS) return;
        lastReadingTime = agora;

        // Notifica UI / serviço com o BPM bruto (sempre no main thread para segurança de UI)
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
                    lastAlertTime   = agora;
                    lastAnomalyType = tipo;
                    final int bpmFinal = bpm;
                    mainHandler.post(() -> listener.onAnomaly(bpmFinal, tipo));
                }
            }
        } else {
            consecutiveOutOfRange = 0;
            lastAnomalyType       = null;
        }
    }

    // ==================== Calibração ====================

    private void carregarLimites() {
        baseline = prefs.getInt(KEY_BASELINE, 75);
        bpmMin   = prefs.getInt(KEY_MIN, DEFAULT_MIN);
        bpmMax   = prefs.getInt(KEY_MAX, DEFAULT_MAX);

        if (!prefs.getBoolean(KEY_CALIBRATED, false)) {
            calibrating      = true;
            calibrationCount = 0;
            calibrationSum   = 0;
        }
    }

    private void concluirCalibracao() {
        baseline = calibrationSum / Math.max(1, calibrationCount);
        bpmMin   = (int) Math.max(ABSOLUTE_MIN, baseline * (1.0 - THRESHOLD_PERCENT));
        bpmMax   = (int) Math.min(ABSOLUTE_MAX, baseline * (1.0 + THRESHOLD_PERCENT));

        prefs.edit()
                .putInt(KEY_BASELINE,   baseline)
                .putInt(KEY_MIN,        bpmMin)
                .putInt(KEY_MAX,        bpmMax)
                .putBoolean(KEY_CALIBRATED, true)
                .apply();

        calibrating           = false;
        calibrationCount      = 0;
        calibrationSum        = 0;
        consecutiveOutOfRange = 0;

        final int b = baseline, mn = bpmMin, mx = bpmMax;
        mainHandler.post(() -> listener.onCalibrationComplete(b, mn, mx));
    }

    // ==================== Utilitários ====================

    private void notifyStatus(String s) {
        mainHandler.post(() -> listener.onStatusChange(s));
    }
}

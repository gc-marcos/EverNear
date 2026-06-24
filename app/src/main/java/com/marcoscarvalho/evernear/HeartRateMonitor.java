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

/**
 * Gerencia a leitura de frequência cardíaca via SensorManager (Wear OS / Android).
 *
 * ┌─ Arquitetura ───────────────────────────────────────────────────────────────┐
 * │  • Roda como auxiliar do HeartRateService (Foreground Service).              │
 * │  • Todos os sensores e o watchdog são registrados no EverNear-SensorThread   │
 * │    (HandlerThread, prioridade FOREGROUND). Isso evita que o Wear OS          │
 * │    throttle as entregas de eventos quando a tela apaga.                      │
 * │  • SENSOR_DELAY_FASTEST: sinaliza ao SensorManager máxima prioridade de     │
 * │    entrega — crucial para que o sensor cardíaco continue funcionando com a   │
 * │    tela apagada no Wear OS.                                                  │
 * └─────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Três níveis de recuperação do sensor ──────────────────────────────────────┐
 * │  NÍVEL 1 — Modo normal (watchdog a cada 10 s):                              │
 * │    Silêncio > 30 s → desregistra e re-registra o SensorEventListener.       │
 * │    Até MAX_SENSOR_RETRIES (3) tentativas rápidas.                            │
 * │                                                                              │
 * │  NÍVEL 2 — Modo lento (watchdog a cada 60 s):                               │
 * │    Após 3 falhas rápidas, continua re-registrando a cada 60 s.              │
 * │    Até SLOW_MODE_MAX_RETRIES (10) tentativas lentas (~10 min no total).     │
 * │                                                                              │
 * │  NÍVEL 3 — Reinicialização pelo serviço:                                    │
 * │    Após SLOW_MODE_MAX_RETRIES falhas, chama onNecessarioReiniciar().        │
 * │    O HeartRateService destrói e recria este monitor completamente.          │
 * └─────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Detecção de atividade física ──────────────────────────────────────────────┐
 * │  Durante corrida/caminhada o BPM sobe naturalmente gerando falsos alertas.  │
 * │  TYPE_STEP_COUNTER (preferido) ou TYPE_ACCELEROMETER (fallback).            │
 * │  Durante movimento: limite superior ampliado em MOVIMENTO_BPM_BUFFER (30). │
 * │  Alertas LOW sempre disparam — bradicardia é perigosa mesmo em exercício.  │
 * └─────────────────────────────────────────────────────────────────────────────┘
 */
public class HeartRateMonitor implements SensorEventListener {

    private static final String TAG = "HeartRateMonitor";

    // ── SharedPreferences ──────────────────────────────────────────────────────
    private static final String PREFS          = "heart_rate_prefs";
    private static final String KEY_BASELINE   = "baseline";
    private static final String KEY_MIN        = "bpm_min";
    private static final String KEY_MAX        = "bpm_max";
    private static final String KEY_CALIBRATED = "calibrated";

    // ── Calibração ─────────────────────────────────────────────────────────────
    private static final int    CALIBRATION_SAMPLES = 30;
    private static final double THRESHOLD_PERCENT    = 0.25;
    private static final int    DEFAULT_MIN          = 50;
    private static final int    DEFAULT_MAX          = 120;
    private static final int    ABSOLUTE_MIN         = 40;
    private static final int    ABSOLUTE_MAX         = 180;

    // ── Detecção de anomalia ───────────────────────────────────────────────────
    private static final int  CONSECUTIVE_READINGS_FOR_ALERT = 3;
    private static final long ALERT_COOLDOWN_MS               = 60_000L;
    private static final long MIN_READING_INTERVAL_MS         = 1_000L;

    // ── Watchdog ──────────────────────────────────────────────────────────────
    /** Intervalo do watchdog em modo normal (3 retentativas rápidas). */
    private static final long WATCHDOG_INTERVAL_MS      = 10_000L;
    /** Intervalo do watchdog em modo lento (após esgotar retentativas rápidas). */
    private static final long WATCHDOG_SLOW_INTERVAL_MS = 60_000L;
    /** Silêncio mínimo para acionar tentativa de re-registro do listener. */
    private static final long SENSOR_SILENCE_MS         = 30_000L;
    /** Retentativas rápidas antes de entrar no modo lento. */
    private static final int  MAX_SENSOR_RETRIES        = 3;
    /**
     * Retentativas no modo lento antes de solicitar reinicialização completa.
     * 10 tentativas × 60 s = ~10 min sem leitura → chama onNecessarioReiniciar().
     * O HeartRateService destrói e recria o monitor.
     */
    private static final int  SLOW_MODE_MAX_RETRIES     = 10;

    // ── Detecção de atividade física ──────────────────────────────────────────
    private static final int   STEP_THRESHOLD         = 3;
    private static final long  MOVEMENT_TIMEOUT_MS    = 60_000L;
    private static final int   MOVIMENTO_BPM_BUFFER   = 30;
    private static final float ACCEL_MOVE_THRESHOLD   = 2.5f;
    private static final int   ACCEL_CONSECUTIVE_MOVE = 5;
    private static final long  ACCEL_THROTTLE_MS      = 500L;

    // ── Enums e interface ─────────────────────────────────────────────────────

    public enum AnomalyType { LOW, HIGH }

    public interface Listener {
        void onHeartRate(int bpm);
        void onStatusChange(String status);
        void onAnomaly(int bpm, AnomalyType tipo);
        void onCalibrationComplete(int baseline, int min, int max);
        void onCalibrationProgress(int collected, int total);
        /**
         * Chamado APENAS quando o hardware TYPE_HEART_RATE não está presente
         * neste dispositivo. Silêncio temporário não dispara este callback.
         */
        void onSensorIndisponivel();
        /**
         * Chamado quando todos os níveis de recuperação do watchdog falharam
         * (SLOW_MODE_MAX_RETRIES tentativas em modo lento sem leituras).
         * O HeartRateService deve destruir e recriar este monitor.
         */
        void onNecessarioReiniciar();
    }

    // ── Infraestrutura ─────────────────────────────────────────────────────────
    private final Context           context;
    private final Listener          listener;
    private final Handler           mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private HandlerThread sensorThread;
    private Handler       bgHandler;

    // ── Sensor cardíaco ────────────────────────────────────────────────────────
    private SensorManager    sensorManager;
    private Sensor           heartRateSensor;
    private volatile boolean usingSensor         = false;
    private volatile long    lastSensorEventTime = System.currentTimeMillis();

    // ── Estado do watchdog (executados apenas em bgHandler — sem sincronização) ─
    /** Retentativas rápidas acumuladas. Quando >= MAX_SENSOR_RETRIES → modo lento. */
    private int     sensorRetryCount  = 0;
    /** Indica que o watchdog está em modo lento. */
    private boolean watchdogModoLento = false;
    /** Retentativas em modo lento acumuladas. Quando >= SLOW_MODE_MAX_RETRIES → reiniciar. */
    private int     slowModeRetryCount = 0;

    // ── Sensor de atividade física ────────────────────────────────────────────
    private Sensor  activitySensor;
    private boolean useStepCounter = false;
    private float   lastStepCount    = -1f;

    // Acelerômetro
    private final float[] gravity   = {0f, 0f, 9.81f};
    private int   accelMoveCount    = 0;
    private long  lastAccelProcTime = 0L;

    // Estado de movimento (volatile para leitura segura em processarLeitura)
    private volatile boolean emMovimento       = false;
    private volatile long    lastMovimentoTime = 0L;

    // ── Calibração (exclusivo ao bgHandler) ───────────────────────────────────
    private volatile boolean calibrating      = false;
    private int              calibrationCount = 0;
    private int              calibrationSum   = 0;

    // ── Limites de BPM ───────────────────────────────────────────────────────
    private int bpmMin;
    private int bpmMax;
    private int baseline;

    // ── Detecção de anomalia (exclusivo ao bgHandler) ─────────────────────────
    private int         consecutiveOutOfRange = 0;
    private long        lastAlertTime         = 0L;
    private AnomalyType lastAnomalyType       = null;
    private long        lastReadingTime        = 0L;

    // ==================== Construtor ====================

    public HeartRateMonitor(Context context, Listener listener) {
        this.context  = context.getApplicationContext();
        this.listener = listener;
        this.prefs    = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        carregarLimites();
        iniciarThreadDedicado();
    }

    // ==================== API pública ====================

    public int     getBpmMin()     { return bpmMin; }
    public int     getBpmMax()     { return bpmMax; }
    public int     getBaseline()   { return baseline; }
    public boolean isCalibrating() { return calibrating; }

    /**
     * Inicia o monitoramento.
     * Se o hardware TYPE_HEART_RATE não estiver presente neste dispositivo,
     * notifica via onSensorIndisponivel() — sem fallback de simulação.
     */
    public void iniciar() {
        Log.i(TAG, "══ Iniciando monitoramento cardíaco ══");
        if (tentarSensorManager()) {
            Log.i(TAG, "TYPE_HEART_RATE ativo → eventos em EverNear-SensorThread");
            tentarSensorAtividade();
            iniciarWatchdog();
        } else {
            Log.e(TAG, "Hardware TYPE_HEART_RATE indisponível neste dispositivo");
            notifySensorIndisponivel();
        }
    }

    /**
     * Inicia nova calibração manual.
     * Postado no bgHandler para evitar race conditions com calibrationCount/Sum.
     */
    public void recalibrar() {
        if (bgHandler == null) return;
        bgHandler.post(() -> {
            calibrating      = true;
            calibrationCount = 0;
            calibrationSum   = 0;
            Log.i(TAG, "── Calibração reiniciada manualmente ──");
            notifyStatus("Calibrando — fique em repouso...");
        });
    }

    /** Para o monitoramento e libera todos os recursos. */
    public void parar() {
        Log.i(TAG, "══ Parando monitoramento cardíaco ══");

        if (bgHandler != null) bgHandler.removeCallbacksAndMessages(null);

        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
                Log.d(TAG, "Todos os listeners de sensor removidos");
            } catch (Exception e) {
                Log.w(TAG, "Erro ao desregistrar sensores: " + e.getMessage());
            }
        }

        usingSensor        = false;
        sensorRetryCount   = 0;
        watchdogModoLento  = false;
        slowModeRetryCount = 0;
        emMovimento        = false;

        if (sensorThread != null) {
            sensorThread.quitSafely();
            sensorThread = null;
            bgHandler    = null;
        }
    }

    // ==================== Thread dedicado ====================

    /**
     * HandlerThread de prioridade FOREGROUND.
     * Todos os sensores são registrados aqui, evitando o throttling que o
     * Wear OS aplica ao main looper quando a tela apaga.
     */
    private void iniciarThreadDedicado() {
        sensorThread = new HandlerThread("EverNear-SensorThread",
                android.os.Process.THREAD_PRIORITY_FOREGROUND);
        sensorThread.start();
        bgHandler = new Handler(sensorThread.getLooper());
        Log.d(TAG, "EverNear-SensorThread iniciado (prioridade FOREGROUND)");
    }

    // ==================== Sensor cardíaco ====================

    private boolean tentarSensorManager() {
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) {
                Log.w(TAG, "SensorManager indisponível");
                return false;
            }
            // Tenta versão wakeup primeiro (entrega leituras mesmo durante suspend)
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true);
            if (heartRateSensor == null) {
                heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            }
            if (heartRateSensor == null) {
                Log.w(TAG, "Hardware TYPE_HEART_RATE não encontrado neste dispositivo");
                return false;
            }
            Log.i(TAG, "Sensor cardíaco: " + heartRateSensor.getName()
                    + " | wakeUp=" + heartRateSensor.isWakeUpSensor());
            return registrarListenerSensor();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao obter SensorManager: " + e.getMessage());
            return false;
        }
    }

    /**
     * Registra o listener no sensor cardíaco entregando eventos no bgHandler.
     *
     * SENSOR_DELAY_FASTEST: máxima prioridade de entrega.
     * No Wear OS, SENSOR_DELAY_NORMAL pode ser throttled quando a tela apaga.
     * FASTEST sinaliza ao sistema que não reduza a frequência de entrega.
     *
     * maxReportLatencyUs=0: desativa FIFO batching.
     * Garante entrega imediata de cada leitura — essencial para detecção em
     * tempo real. Com latência > 0, leituras poderiam ser retidas por minutos.
     */
    private boolean registrarListenerSensor() {
        if (sensorManager == null || heartRateSensor == null || bgHandler == null) return false;
        try {
            boolean ok = sensorManager.registerListener(
                    this,
                    heartRateSensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    0,           // maxReportLatencyUs=0: sem batching
                    bgHandler);
            if (ok) {
                usingSensor         = true;
                lastSensorEventTime = System.currentTimeMillis();
                Log.d(TAG, "registerListener HR (FASTEST, latência=0): OK");
                return true;
            }
            Log.w(TAG, "registerListener HR: retornou false");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao registrar listener HR: " + e.getMessage());
            return false;
        }
    }

    // ==================== Sensor de atividade física ====================

    /**
     * Registra sensor para detecção de atividade física.
     * Usa SENSOR_DELAY_NORMAL (não FASTEST): latência de movimento de poucos
     * segundos é aceitável e economiza bateria.
     */
    private void tentarSensorAtividade() {
        if (sensorManager == null) return;

        Sensor stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (stepSensor != null) {
            boolean ok = sensorManager.registerListener(
                    this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, bgHandler);
            if (ok) {
                activitySensor = stepSensor;
                useStepCounter = true;
                Log.i(TAG, "Sensor de atividade: TYPE_STEP_COUNTER registrado");
                return;
            }
        }

        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) {
            boolean ok = sensorManager.registerListener(
                    this, accel, SensorManager.SENSOR_DELAY_NORMAL, bgHandler);
            if (ok) {
                activitySensor = accel;
                useStepCounter = false;
                Log.i(TAG, "Sensor de atividade: TYPE_ACCELEROMETER registrado (fallback)");
                return;
            }
        }
        Log.w(TAG, "Nenhum sensor de atividade — detecção de movimento desabilitada");
    }

    // ==================== Callbacks do SensorManager ====================

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_HEART_RATE:
                processarSensorCardiaco(event);
                break;
            case Sensor.TYPE_STEP_COUNTER:
                processarPassos(event);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                processarAcelerometro(event);
                break;
        }
    }

    private void processarSensorCardiaco(SensorEvent event) {
        if (event.values.length == 0) return;
        int bpm = (int) event.values[0];
        if (bpm <= 0) return;

        lastSensorEventTime = System.currentTimeMillis();

        // Sensor voltou a funcionar — reseta todos os contadores de recuperação
        if (sensorRetryCount > 0 || watchdogModoLento || slowModeRetryCount > 0) {
            Log.i(TAG, "Sensor cardíaco recuperado após "
                    + sensorRetryCount + " retentativas rápidas + "
                    + slowModeRetryCount + " lentas"
                    + (watchdogModoLento ? " [modo lento]" : ""));
            sensorRetryCount   = 0;
            slowModeRetryCount = 0;
            watchdogModoLento  = false;
            notifyStatus("Sensor cardíaco ativo");
        }
        processarLeitura(bpm);
    }

    private void processarPassos(SensorEvent event) {
        if (event.values.length == 0) return;
        float totalPassos = event.values[0];
        long  agora       = System.currentTimeMillis();

        if (lastStepCount >= 0) {
            float delta = totalPassos - lastStepCount;
            if (delta >= STEP_THRESHOLD) {
                if (!emMovimento) {
                    Log.d(TAG, "Atividade física: +" + (int) delta + " passos");
                }
                emMovimento       = true;
                lastMovimentoTime = agora;
            }
        }
        lastStepCount = totalPassos;
    }

    private void processarAcelerometro(SensorEvent event) {
        if (event.values.length < 3) return;
        long agora = System.currentTimeMillis();
        if (agora - lastAccelProcTime < ACCEL_THROTTLE_MS) return;
        lastAccelProcTime = agora;

        gravity[0] = 0.8f * gravity[0] + 0.2f * event.values[0];
        gravity[1] = 0.8f * gravity[1] + 0.2f * event.values[1];
        gravity[2] = 0.8f * gravity[2] + 0.2f * event.values[2];

        float ax  = event.values[0] - gravity[0];
        float ay  = event.values[1] - gravity[1];
        float az  = event.values[2] - gravity[2];
        float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);

        if (mag > ACCEL_MOVE_THRESHOLD) {
            accelMoveCount = Math.min(accelMoveCount + 1, ACCEL_CONSECUTIVE_MOVE);
            if (accelMoveCount >= ACCEL_CONSECUTIVE_MOVE) {
                if (!emMovimento) {
                    Log.d(TAG, "Atividade via acelerômetro: mag="
                            + String.format("%.1f", mag) + " m/s²");
                }
                emMovimento       = true;
                lastMovimentoTime = agora;
            }
        } else {
            if (accelMoveCount > 0) accelMoveCount--;
        }
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

    // ==================== Watchdog ====================

    /**
     * Executado no bgHandler. Dois modos de operação:
     *
     * MODO NORMAL  → verifica a cada 10 s. Silêncio > 30 s: re-registra listener.
     *                Até MAX_SENSOR_RETRIES (3) tentativas antes de ir ao modo lento.
     *
     * MODO LENTO   → verifica a cada 60 s. Continua re-registrando.
     *                Após SLOW_MODE_MAX_RETRIES (10) falhas: chama onNecessarioReiniciar()
     *                para que o HeartRateService destrua e recrie este monitor.
     *
     * O watchdog só para quando parar() é chamado (bgHandler.removeCallbacksAndMessages).
     */
    private void iniciarWatchdog() {
        bgHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bgHandler == null) return;

                verificarTimeoutMovimento();
                verificarSaudeSensor();

                long proximoIntervalo = watchdogModoLento
                        ? WATCHDOG_SLOW_INTERVAL_MS
                        : WATCHDOG_INTERVAL_MS;
                bgHandler.postDelayed(this, proximoIntervalo);
            }
        }, WATCHDOG_INTERVAL_MS);
        Log.d(TAG, "Watchdog iniciado — normal=" + WATCHDOG_INTERVAL_MS / 1000
                + "s | lento=" + WATCHDOG_SLOW_INTERVAL_MS / 1000 + "s");
    }

    private void verificarTimeoutMovimento() {
        if (!emMovimento) return;
        long silencio = System.currentTimeMillis() - lastMovimentoTime;
        if (silencio > MOVEMENT_TIMEOUT_MS) {
            emMovimento = false;
            Log.d(TAG, "Atividade: inativo há " + (silencio / 1000) + "s → repouso");
        }
    }

    /**
     * Verifica saúde do sensor e tenta recuperação automática em 3 níveis:
     *
     * Nível 1 — Modo normal: re-registra SensorEventListener (até 3 vezes).
     * Nível 2 — Modo lento: continua re-registrando a cada 60 s (até 10 vezes).
     * Nível 3 — Reinicialização: chama onNecessarioReiniciar() para que o
     *            HeartRateService recrie o HeartRateMonitor completamente.
     */
    private void verificarSaudeSensor() {
        if (sensorManager == null) return;

        long silencioMs = System.currentTimeMillis() - lastSensorEventTime;

        // Sensor respondendo normalmente — reseta todos os contadores
        if (silencioMs <= SENSOR_SILENCE_MS) {
            if (sensorRetryCount > 0 || watchdogModoLento) {
                Log.d(TAG, "Watchdog: sensor OK — retomando modo normal");
                sensorRetryCount   = 0;
                slowModeRetryCount = 0;
                watchdogModoLento  = false;
            }
            return;
        }

        // Sensor em silêncio: tenta re-registro
        sensorRetryCount++;
        Log.w(TAG, "Watchdog [" + (watchdogModoLento ? "LENTO #" + slowModeRetryCount : "NORMAL")
                + "]: " + (silencioMs / 1000) + "s sem leitura → re-registro #" + sensorRetryCount);

        try { sensorManager.unregisterListener(this, heartRateSensor); }
        catch (Exception ignored) {}
        usingSensor = false;

        boolean ok = registrarListenerSensor();

        if (ok) {
            Log.i(TAG, "Watchdog: listener re-registrado (retentativa " + sensorRetryCount + ")");
            return;
        }

        // Re-registro falhou — decide próximo passo
        if (!watchdogModoLento && sensorRetryCount >= MAX_SENSOR_RETRIES) {
            // ── Nível 2: transição para modo lento ──────────────────────────
            watchdogModoLento  = true;
            slowModeRetryCount = 0;
            Log.w(TAG, "Watchdog: " + MAX_SENSOR_RETRIES
                    + " tentativas rápidas esgotadas → MODO LENTO ("
                    + WATCHDOG_SLOW_INTERVAL_MS / 1000 + "s/ciclo). "
                    + "Relógio no pulso? Bateria ok?");
            notifyStatus("Sensor sem leitura — verifique o relógio no pulso");

        } else if (watchdogModoLento) {
            // ── Nível 2 em curso: conta retentativas lentas ─────────────────
            slowModeRetryCount++;
            Log.w(TAG, "Watchdog LENTO: tentativa " + slowModeRetryCount
                    + "/" + SLOW_MODE_MAX_RETRIES);

            if (slowModeRetryCount >= SLOW_MODE_MAX_RETRIES) {
                // ── Nível 3: solicita reinicialização completa do monitor ────
                Log.e(TAG, "Watchdog: " + SLOW_MODE_MAX_RETRIES
                        + " tentativas lentas esgotadas → solicitando reinicialização do monitor");
                notifyNecessarioReiniciar();
                // O HeartRateService chamará parar(), cancelando este watchdog
            }

        } else {
            Log.w(TAG, "Watchdog: re-registro falhou (tentativa " + sensorRetryCount + ")");
        }
    }

    // ==================== Processamento da leitura ====================

    /**
     * Processa um BPM do sensor físico.
     * Sempre executado no EverNear-SensorThread — thread safety garantido.
     */
    private void processarLeitura(int bpm) {
        long agora = System.currentTimeMillis();
        if (agora - lastReadingTime < MIN_READING_INTERVAL_MS) return;
        lastReadingTime = agora;

        mainHandler.post(() -> listener.onHeartRate(bpm));

        // ── Calibração ──────────────────────────────────────────────────────
        if (calibrating) {
            calibrationSum += bpm;
            calibrationCount++;
            final int contagem = calibrationCount;
            mainHandler.post(() -> listener.onCalibrationProgress(contagem, CALIBRATION_SAMPLES));
            if (calibrationCount >= CALIBRATION_SAMPLES) concluirCalibracao();
            return;
        }

        // ── Limites efetivos (ajustados por atividade) ──────────────────────
        int     bpmMaxEfetivo = emMovimento ? (bpmMax + MOVIMENTO_BPM_BUFFER) : bpmMax;
        boolean faixaBaixa    = bpm < bpmMin;
        boolean faixaAlta     = bpm > bpmMaxEfetivo;

        // ── Detecção de anomalia ─────────────────────────────────────────────
        if (faixaBaixa || faixaAlta) {
            consecutiveOutOfRange++;
            AnomalyType tipo = faixaBaixa ? AnomalyType.LOW : AnomalyType.HIGH;

            if (consecutiveOutOfRange >= CONSECUTIVE_READINGS_FOR_ALERT) {
                boolean cooldownOk = (agora - lastAlertTime) >= ALERT_COOLDOWN_MS;
                boolean tipoMudou  = (tipo != lastAnomalyType);

                if (cooldownOk || tipoMudou) {
                    lastAlertTime   = agora;
                    lastAnomalyType = tipo;

                    Log.i(TAG, "Alerta: bpm=" + bpm + " tipo=" + tipo
                            + " | min=" + bpmMin + " maxEfetivo=" + bpmMaxEfetivo
                            + (emMovimento ? " [movimento]" : ""));

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
            Log.i(TAG, "── Calibração automática inicial agendada ──");
        } else {
            Log.d(TAG, "Limites: baseline=" + baseline
                    + " min=" + bpmMin + " max=" + bpmMax);
        }
    }

    private void concluirCalibracao() {
        baseline = calibrationSum / Math.max(1, calibrationCount);
        bpmMin   = (int) Math.max(ABSOLUTE_MIN, baseline * (1.0 - THRESHOLD_PERCENT));
        bpmMax   = (int) Math.min(ABSOLUTE_MAX, baseline * (1.0 + THRESHOLD_PERCENT));

        prefs.edit()
                .putInt(KEY_BASELINE,       baseline)
                .putInt(KEY_MIN,            bpmMin)
                .putInt(KEY_MAX,            bpmMax)
                .putBoolean(KEY_CALIBRATED, true)
                .apply();

        calibrating           = false;
        calibrationCount      = 0;
        calibrationSum        = 0;
        consecutiveOutOfRange = 0;

        Log.i(TAG, "── Calibração concluída: baseline=" + baseline
                + " min=" + bpmMin + " max=" + bpmMax + " ──");

        final int b = baseline, mn = bpmMin, mx = bpmMax;
        mainHandler.post(() -> listener.onCalibrationComplete(b, mn, mx));
    }

    // ==================== Utilitários ====================

    private void notifyStatus(String s) {
        mainHandler.post(() -> listener.onStatusChange(s));
    }

    private void notifySensorIndisponivel() {
        mainHandler.post(() -> listener.onSensorIndisponivel());
    }

    private void notifyNecessarioReiniciar() {
        mainHandler.post(() -> listener.onNecessarioReiniciar());
    }
}

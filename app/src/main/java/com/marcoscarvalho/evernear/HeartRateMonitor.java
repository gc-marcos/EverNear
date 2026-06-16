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
 * ┌─ Arquitetura ───────────────────────────────────────────────────────────────┐
 * │  • Roda como auxiliar do HeartRateService (Foreground Service).              │
 * │  • Todos os sensores e o watchdog são registrados no EverNear-SensorThread   │
 * │    (HandlerThread, prioridade FOREGROUND). Isso evita que o Wear OS          │
 * │    throttle as entregas de eventos quando a tela apaga.                      │
 * │  • processarLeitura() é sempre chamado neste thread → sem necessidade de     │
 * │    sincronização para os campos de calibração/anomalia.                      │
 * │  • recalibrar() posta tarefa no bgHandler para evitar race condition.        │
 * └─────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Detecção de atividade física ──────────────────────────────────────────────┐
 * │  Durante corrida/caminhada o BPM sobe naturalmente acima dos limites normais │
 * │  gerando falsos alertas. A solução:                                          │
 * │  1. TYPE_STEP_COUNTER (preferido — menor consumo de bateria).                │
 * │  2. TYPE_ACCELEROMETER como fallback.                                        │
 * │  Durante movimento: limite superior ampliado em MOVIMENTO_BPM_BUFFER (30).   │
 * │  Alertas de frequência BAIXA sempre disparam, mesmo durante exercício.       │
 * └─────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Recuperação automática do sensor ─────────────────────────────────────────┐
 * │  O watchdog tenta re-registrar o listener até MAX_SENSOR_RETRIES (3) vezes. │
 * │  Só ativa o simulador após todas as tentativas falharem.                    │
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
    private static final double THRESHOLD_PERCENT    = 0.25;   // baseline ±25 %
    private static final int    DEFAULT_MIN          = 50;
    private static final int    DEFAULT_MAX          = 120;
    private static final int    ABSOLUTE_MIN         = 40;
    private static final int    ABSOLUTE_MAX         = 180;

    // ── Detecção de anomalia ───────────────────────────────────────────────────
    private static final int  CONSECUTIVE_READINGS_FOR_ALERT = 3;
    private static final long ALERT_COOLDOWN_MS               = 60_000L;
    private static final long MIN_READING_INTERVAL_MS         = 1_000L;

    // ── Watchdog ──────────────────────────────────────────────────────────────
    /** Frequência de verificação do watchdog. */
    private static final long WATCHDOG_INTERVAL_MS = 10_000L;
    /**
     * Tempo sem leitura antes de tentar re-registrar o sensor.
     * Alterado de 10 s para 30 s para evitar re-registros por pequenas pausas
     * normais na entrega de eventos (ex.: modo ambient do Wear OS).
     */
    private static final long SENSOR_SILENCE_MS    = 30_000L;
    /** Número máximo de tentativas de re-registro antes de cair para o simulador. */
    private static final int  MAX_SENSOR_RETRIES   = 3;

    // ── Simulador de fallback ─────────────────────────────────────────────────
    private static final long SIMULATOR_INTERVAL_MS = 3_000L;

    // ── Detecção de atividade física ──────────────────────────────────────────
    /** Passos mínimos em um ciclo de watchdog (10 s) para considerar "em movimento". */
    private static final int   STEP_THRESHOLD            = 3;
    /** Período sem passos/aceleração após o qual o estado "em movimento" é limpo. */
    private static final long  MOVEMENT_TIMEOUT_MS       = 60_000L;
    /** Bônus adicionado ao bpmMax durante atividade física para evitar falsos alertas. */
    private static final int   MOVIMENTO_BPM_BUFFER      = 30;
    /** Magnitude de aceleração linear (m/s²) acima da qual considera movimento. */
    private static final float ACCEL_MOVE_THRESHOLD      = 2.5f;
    /** Leituras consecutivas do acelerômetro acima do limiar → em movimento. */
    private static final int   ACCEL_CONSECUTIVE_MOVE    = 5;
    /** Throttle de processamento do acelerômetro (máx. 2 x / s) para poupar bateria. */
    private static final long  ACCEL_THROTTLE_MS         = 500L;

    // ── Enums e interface ─────────────────────────────────────────────────────

    public enum AnomalyType { LOW, HIGH }

    public interface Listener {
        void onHeartRate(int bpm);
        void onStatusChange(String status);
        void onAnomaly(int bpm, AnomalyType tipo);
        void onCalibrationComplete(int baseline, int min, int max);
        void onCalibrationProgress(int collected, int total);
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
    /** Contador de tentativas de re-registro (executado apenas em bgHandler). */
    private int sensorRetryCount = 0;

    // ── Sensor de atividade física ────────────────────────────────────────────
    private Sensor  activitySensor;
    private boolean useStepCounter = false;

    // Step counter
    private float lastStepCount    = -1f;

    // Acelerômetro
    private final float[] gravity   = {0f, 0f, 9.81f};
    private int   accelMoveCount    = 0;
    private long  lastAccelProcTime = 0L;

    // Estado de movimento (volatile para leitura segura em getBpmMaxEfetivo)
    private volatile boolean emMovimento       = false;
    private volatile long    lastMovimentoTime = 0L;

    // ── Simulador ─────────────────────────────────────────────────────────────
    private Runnable   simulatorRunnable;
    private final Random random      = new Random();
    private int        simulatorBpm  = 75;

    // ── Calibração ────────────────────────────────────────────────────────────
    // Acessados EXCLUSIVAMENTE no bgHandler → sem sincronização adicional necessária
    private volatile boolean calibrating      = false;
    private int              calibrationCount = 0;
    private int              calibrationSum   = 0;

    // ── Limites de BPM ───────────────────────────────────────────────────────
    private int bpmMin;
    private int bpmMax;
    private int baseline;

    // ── Detecção de anomalia ──────────────────────────────────────────────────
    // Acessados EXCLUSIVAMENTE no bgHandler → sem sincronização
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
     * Tenta sensor de hardware primeiro; usa simulador como fallback.
     * Registra sensor de atividade física para reduzir falsos positivos.
     */
    public void iniciar() {
        Log.i(TAG, "══ Iniciando monitoramento cardíaco ══");
        if (tentarSensorManager()) {
            Log.i(TAG, "TYPE_HEART_RATE ativo → eventos em EverNear-SensorThread");
            tentarSensorAtividade();
            iniciarWatchdog();
        } else {
            Log.w(TAG, "Sensor de hardware indisponível — ativando simulador");
            tentarSensorAtividade();
            iniciarSimulador();
        }
    }

    /**
     * Inicia nova calibração manual.
     * Postado no bgHandler para ser executado no mesmo thread que processarLeitura(),
     * evitando race conditions com calibrationCount / calibrationSum.
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

        usingSensor       = false;
        simulatorRunnable = null;
        sensorRetryCount  = 0;
        emMovimento       = false;

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
        Log.d(TAG, "EverNear-SensorThread iniciado");
    }

    // ==================== Sensor cardíaco ====================

    private boolean tentarSensorManager() {
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) {
                Log.w(TAG, "SensorManager indisponível");
                return false;
            }
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (heartRateSensor == null) {
                Log.w(TAG, "TYPE_HEART_RATE não encontrado neste dispositivo");
                return false;
            }
            return registrarListenerSensor();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao obter SensorManager: " + e.getMessage());
            return false;
        }
    }

    /**
     * Registra o listener no sensor cardíaco entregando eventos no bgHandler.
     *
     * Sem o handler explícito, os eventos iriam para o main looper — que o
     * Wear OS reduz agressivamente quando a tela apaga. Com bgHandler, os eventos
     * chegam no EverNear-SensorThread (prioridade FOREGROUND), garantindo
     * continuidade mesmo com tela apagada ou app em segundo plano.
     */
    private boolean registrarListenerSensor() {
        if (sensorManager == null || heartRateSensor == null || bgHandler == null) return false;
        try {
            boolean ok = sensorManager.registerListener(
                    this,
                    heartRateSensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    bgHandler);
            if (ok) {
                usingSensor         = true;
                lastSensorEventTime = System.currentTimeMillis();
                Log.d(TAG, "registerListener HR: OK");
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
     *
     * Prioridade:
     *  1. TYPE_STEP_COUNTER — acorda apenas quando há passos; baixíssimo consumo
     *  2. TYPE_ACCELEROMETER — fallback contínuo, com throttle de 500 ms
     *
     * Requer permissão ACTIVITY_RECOGNITION (API 29+), já declarada no Manifest
     * e solicitada em SetupPermissoesActivity.
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
        Log.w(TAG, "Nenhum sensor de atividade disponível — detecção de movimento desabilitada");
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

        // Sensor voltou a funcionar: registra recuperação e reseta contador de retentativas
        if (sensorRetryCount > 0) {
            Log.i(TAG, "Sensor cardíaco recuperado após " + sensorRetryCount + " tentativa(s)");
            sensorRetryCount = 0;
        }
        processarLeitura(bpm);
    }

    /**
     * TYPE_STEP_COUNTER: contagem cumulativa desde o boot.
     * Compara com a leitura anterior para calcular delta de passos.
     * Se delta >= STEP_THRESHOLD em um ciclo → usuário em movimento.
     */
    private void processarPassos(SensorEvent event) {
        if (event.values.length == 0) return;
        float totalPassos = event.values[0];
        long  agora       = System.currentTimeMillis();

        if (lastStepCount >= 0) {
            float delta = totalPassos - lastStepCount;
            if (delta >= STEP_THRESHOLD) {
                if (!emMovimento) {
                    Log.d(TAG, "Atividade física detectada: +" + (int) delta + " passos");
                }
                emMovimento      = true;
                lastMovimentoTime = agora;
            }
        }
        lastStepCount = totalPassos;
    }

    /**
     * TYPE_ACCELEROMETER: detecta movimento por magnitude de aceleração linear.
     * Filtro passa-baixa isola a componente gravitacional (α = 0.8).
     * Magnitude residual > ACCEL_MOVE_THRESHOLD por N leituras → em movimento.
     * Throttle: processa no máximo 1 leitura a cada ACCEL_THROTTLE_MS (500 ms).
     */
    private void processarAcelerometro(SensorEvent event) {
        if (event.values.length < 3) return;
        long agora = System.currentTimeMillis();
        if (agora - lastAccelProcTime < ACCEL_THROTTLE_MS) return;
        lastAccelProcTime = agora;

        // Filtro passa-baixa: estima vetor de gravidade
        gravity[0] = 0.8f * gravity[0] + 0.2f * event.values[0];
        gravity[1] = 0.8f * gravity[1] + 0.2f * event.values[1];
        gravity[2] = 0.8f * gravity[2] + 0.2f * event.values[2];

        // Aceleração linear (remove gravidade)
        float ax  = event.values[0] - gravity[0];
        float ay  = event.values[1] - gravity[1];
        float az  = event.values[2] - gravity[2];
        float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);

        if (mag > ACCEL_MOVE_THRESHOLD) {
            accelMoveCount = Math.min(accelMoveCount + 1, ACCEL_CONSECUTIVE_MOVE);
            if (accelMoveCount >= ACCEL_CONSECUTIVE_MOVE) {
                if (!emMovimento) {
                    Log.d(TAG, "Atividade física detectada via acelerômetro: mag="
                            + String.format("%.1f", mag) + " m/s²");
                }
                emMovimento      = true;
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
                Log.d(TAG, "Sensor cardíaco: sem contato com a pele");
                notifyStatus("Sem contato — ajuste o relógio no pulso");
                break;
            case SensorManager.SENSOR_STATUS_UNRELIABLE:
                Log.d(TAG, "Sensor cardíaco: leitura instável");
                notifyStatus("Leitura instável — ajuste o relógio");
                break;
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                Log.d(TAG, "Sensor cardíaco: precisão baixa");
                notifyStatus("Precisão baixa — mantendo monitoramento");
                break;
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                Log.d(TAG, "Sensor cardíaco: precisão OK (" + accuracy + ")");
                notifyStatus("Sensor cardíaco ativo");
                break;
        }
    }

    // ==================== Watchdog ====================

    /**
     * Executado no bgHandler a cada WATCHDOG_INTERVAL_MS (10 s).
     *
     * Responsabilidades:
     *  1. Verificar timeout de movimento (sem passos por 60 s → parado)
     *  2. Verificar saúde do sensor cardíaco
     *  3. Tentar recuperação automática (até MAX_SENSOR_RETRIES = 3)
     *  4. Ativar simulador se todas as tentativas falharem
     */
    private void iniciarWatchdog() {
        bgHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                verificarTimeoutMovimento();
                verificarSaudeSensor();
                // Reagenda apenas se ainda há sensor ativo ou estamos em retentativas
                if (usingSensor || (sensorRetryCount > 0 && sensorRetryCount < MAX_SENSOR_RETRIES)) {
                    bgHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
                }
            }
        }, WATCHDOG_INTERVAL_MS);
    }

    /** Se não houve passos/aceleração por MOVEMENT_TIMEOUT_MS → limpa flag de movimento. */
    private void verificarTimeoutMovimento() {
        if (!emMovimento) return;
        long silencio = System.currentTimeMillis() - lastMovimentoTime;
        if (silencio > MOVEMENT_TIMEOUT_MS) {
            emMovimento = false;
            Log.d(TAG, "Atividade física: inativo há " + (silencio / 1000) + "s → modo repouso");
        }
    }

    /**
     * Verifica se o sensor cardíaco está entregando leituras.
     * Estratégia de recuperação:
     *  - Silêncio > SENSOR_SILENCE_MS (30 s): desregistra e tenta re-registrar
     *  - Sucesso: reseta contador; continua watchdog normalmente
     *  - Falha: incrementa contador; tenta novamente no próximo ciclo
     *  - Após MAX_SENSOR_RETRIES (3) falhas: ativa simulador definitivamente
     */
    private void verificarSaudeSensor() {
        if (!usingSensor || sensorManager == null) return;

        long silencioMs = System.currentTimeMillis() - lastSensorEventTime;

        if (silencioMs <= SENSOR_SILENCE_MS) {
            // Sensor saudável: reseta retries se necessário
            if (sensorRetryCount > 0) {
                Log.d(TAG, "Watchdog: sensor cardíaco OK — retries zerados");
                sensorRetryCount = 0;
            }
            return;
        }

        // Silêncio além do limiar: tenta recuperação
        sensorRetryCount++;
        Log.w(TAG, "Watchdog: " + (silencioMs / 1000) + "s sem leitura cardíaca"
                + " → tentativa de recuperação " + sensorRetryCount + "/" + MAX_SENSOR_RETRIES);

        try { sensorManager.unregisterListener(this, heartRateSensor); }
        catch (Exception ignored) {}

        boolean ok = registrarListenerSensor();
        if (ok) {
            Log.i(TAG, "Watchdog: sensor cardíaco re-registrado com sucesso"
                    + " (tentativa " + sensorRetryCount + ")");
            // Não reseta sensorRetryCount aqui: reseta quando chegar evento real do sensor
        } else if (sensorRetryCount >= MAX_SENSOR_RETRIES) {
            Log.e(TAG, "Watchdog: " + MAX_SENSOR_RETRIES
                    + " tentativas esgotadas → ativando simulador de fallback");
            usingSensor = false;
            iniciarSimulador();
            // Watchdog para de verificar (usingSensor=false fará verificarSaudeSensor retornar)
        } else {
            Log.w(TAG, "Watchdog: re-registro falhou na tentativa " + sensorRetryCount
                    + " — próxima em " + (WATCHDOG_INTERVAL_MS / 1000) + "s");
        }
    }

    // ==================== Simulador de fallback ====================

    /**
     * Gera BPM simulado com variação realista.
     * Executado no bgHandler para não sofrer throttling do main looper.
     * Inclui spikes periódicos para testar o sistema de alertas.
     */
    private void iniciarSimulador() {
        if (bgHandler == null) return;
        Log.w(TAG, "── Simulador de fallback ativado (sensor indisponível) ──");
        notifyStatus("Modo simulação — sensor indisponível");

        simulatorRunnable = new Runnable() {
            @Override
            public void run() {
                if (simulatorRunnable == null) return;

                // Variação aleatória ±3 bpm
                int delta = random.nextInt(7) - 3;
                simulatorBpm = Math.max(40, Math.min(180, simulatorBpm + delta));

                // 2% de chance de spike para teste de alertas
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

    // ==================== Processamento da leitura ====================

    /**
     * Processa um valor de BPM (sensor físico ou simulador).
     * SEMPRE executado no EverNear-SensorThread — thread safety garantido.
     *
     * Detecção de anomalia com ajuste de atividade física:
     *  - bpmMaxEfetivo = bpmMax + MOVIMENTO_BPM_BUFFER (30) quando em movimento
     *  - Alertas HIGH são suprimidos se BPM está dentro do limite ampliado
     *  - Alertas LOW sempre disparam (bradicardia é perigosa mesmo em exercício)
     */
    private void processarLeitura(int bpm) {
        long agora = System.currentTimeMillis();
        if (agora - lastReadingTime < MIN_READING_INTERVAL_MS) return;
        lastReadingTime = agora;

        // Notifica UI no main thread (seguro: listener espera chamadas de qualquer thread)
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
        // Durante movimento: limite superior ampliado para prevenir falsos alertas.
        // Limite inferior mantido: bradicardia durante exercício é sinal de alerta real.
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

                    Log.i(TAG, "Alerta disparado: bpm=" + bpm + " tipo=" + tipo
                            + " | min=" + bpmMin + " maxEfetivo=" + bpmMaxEfetivo
                            + (emMovimento ? " [em movimento]" : ""));

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
            Log.d(TAG, "Limites carregados: baseline=" + baseline
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
}

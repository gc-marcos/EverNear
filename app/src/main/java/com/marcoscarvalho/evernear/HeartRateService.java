package com.marcoscarvalho.evernear;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Serviço de monitoramento contínuo — roda no SMARTWATCH do paciente.
 *
 * ┌─ Por que funciona com tela apagada e app fechado? ─────────────────────────┐
 * │  1. Foreground Service: Android não encerra processos em primeiro plano.   │
 * │  2. WakeLock (PARTIAL): mantém CPU ativa mesmo com tela apagada.          │
 * │     Renovado periodicamente para evitar encerramento por OEMs.             │
 * │  3. START_STICKY: Android reinicia o serviço após encerramento forçado.   │
 * │  4. onTaskRemoved + AlarmManager.setExactAndAllowWhileIdle: reinicia em   │
 * │     10 s mesmo durante Doze (tela apagada).                                │
 * │  5. BootReceiver: reinicia quando o relógio é ligado.                     │
 * │  6. HeartRateMonitor: SENSOR_DELAY_FASTEST + thread FOREGROUND.           │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Três níveis de recuperação ───────────────────────────────────────────────┐
 * │  NÍVEL 1 (HeartRateMonitor): re-registra SensorEventListener (watchdog).  │
 * │  NÍVEL 2 (HeartRateService): reinicia HeartRateMonitor completamente.     │
 * │    Acionado por:                                                            │
 * │     a) HeartRateMonitor.onNecessarioReiniciar() — watchdog esgotado.      │
 * │     b) Watchdog do serviço — ausência de leituras por SERVICE_TIMEOUT.    │
 * │  NÍVEL 3 (HeartRateService): reinicia o próprio serviço via stopSelf()    │
 * │    + AlarmManager após MAX_MONITOR_RESTARTS tentativas frustradas.         │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Escalada de alertas via AlarmManager ────────────────────────────────────┐
 * │  Handler.postDelayed é suspenso em Doze. AlarmManager.setExactAndAllow-   │
 * │  WhileIdle() dispara mesmo com tela apagada, garantindo escalada de 5 min.│
 * └────────────────────────────────────────────────────────────────────────────┘
 */
public class HeartRateService extends Service implements HeartRateMonitor.Listener {

    private static final String TAG = "HeartRateService";

    // ── Actions ───────────────────────────────────────────────────────────────
    public static final String ACTION_CALIBRAR = "com.marcoscarvalho.evernear.ACTION_CALIBRAR";
    public static final String ACTION_PARAR    = "com.marcoscarvalho.evernear.ACTION_PARAR";
    /** Disparado pelo AlarmManager para verificar e escalar alerta não confirmado. */
    public static final String ACTION_ESCALAR  = "com.marcoscarvalho.evernear.ACTION_ESCALAR";

    // ── Extras para ACTION_ESCALAR ────────────────────────────────────────────
    private static final String EXTRA_ALERTA_ID      = "alertaId";
    private static final String EXTRA_PROXIMO_INDICE = "proximoIndice";
    private static final String EXTRA_BPM            = "bpm";
    private static final String EXTRA_TIPO           = "tipo";

    // ── Canais de notificação ─────────────────────────────────────────────────
    private static final String CHANNEL_ID = "evernear_monitor";
    private static final int    NOTIF_ID   = 1001;

    // ── Escalada ──────────────────────────────────────────────────────────────
    private static final long ESCALADA_MS = 5 * 60 * 1000L;

    // ── WakeLock ──────────────────────────────────────────────────────────────
    private static final String WAKELOCK_TAG        = "EverNear:HeartRateWakeLock";
    /** Renovação periódica — alguns OEMs encerram WakeLocks antigos. */
    private static final long   WAKELOCK_RENEWAL_MS = 9 * 60 * 1000L;

    // ── Watchdog do serviço ────────────────────────────────────────────────────
    /** Intervalo de verificação do watchdog do serviço. */
    private static final long SERVICE_WATCHDOG_INTERVAL_MS = 2 * 60_000L;   // 2 min
    /**
     * Ausência de leituras além deste tempo aciona reinicialização do monitor.
     * Deve ser maior que o tempo do watchdog do monitor em modo lento:
     * 3 retentativas normais (30s) + transição + 1ª tentativa lenta (60s) = ~3 min.
     */
    private static final long SERVICE_WATCHDOG_TIMEOUT_MS  = 5 * 60_000L;   // 5 min
    /** Máximo de reinicializações do HeartRateMonitor antes de reiniciar o serviço. */
    private static final int  MAX_MONITOR_RESTARTS          = 3;

    // ── Throttle de atualizações ──────────────────────────────────────────────
    private static final long NOTIF_UPDATE_INTERVAL_MS     = 3_000L;
    /** 10 s em produção: reduz consumo de bateria e cota do Firestore. */
    private static final long FIRESTORE_UPDATE_INTERVAL_MS = 10_000L;

    // ── Estado estático ───────────────────────────────────────────────────────
    private static HeartRateService instance;
    private static WeakReference<HeartRateMonitor.Listener> activityListenerRef;

    // ── Estado da instância ───────────────────────────────────────────────────
    private HeartRateMonitor    monitor;
    private NotificationManager notifManager;
    private PowerManager.WakeLock wakeLock;

    // Handlers no main looper (seguro com WakeLock ativo)
    private final Handler wakeLockRenewalHandler  = new Handler(Looper.getMainLooper());
    private final Handler serviceWatchdogHandler  = new Handler(Looper.getMainLooper());

    // Dados do paciente
    private String               uidPaciente;
    private String               nomePaciente         = "Paciente";
    private List<String>         cuidadoresVinculados = new ArrayList<>();
    private ListenerRegistration pacienteDataListener;
    private boolean              monitorIniciado      = false;

    // ── Watchdog do serviço — estado ──────────────────────────────────────────
    /** Timestamp da última leitura recebida via onHeartRate(). */
    private volatile long lastHeartRateReceivedTime = System.currentTimeMillis();
    /** Quantas vezes o monitor foi reiniciado nesta sessão do serviço. */
    private int monitorRestartCount = 0;

    // Throttle
    private long lastNotifUpdate     = 0;
    private long lastFirestoreUpdate = 0;

    // ==================== API estática ====================

    public static HeartRateService getInstance() { return instance; }
    public HeartRateMonitor getMonitor() { return monitor; }

    public static void setActivityListener(@Nullable HeartRateMonitor.Listener listener) {
        activityListenerRef = listener != null ? new WeakReference<>(listener) : null;
    }

    public List<String> getCuidadoresVinculados() {
        return new ArrayList<>(cuidadoresVinculados);
    }

    // ==================== Ciclo de vida ====================

    @Override
    public void onCreate() {
        super.onCreate();
        instance     = this;
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        criarCanalNotificacao();
        adquirirWakeLock();
        agendarRenovacaoWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (ACTION_CALIBRAR.equals(action)) {
                if (monitor != null) monitor.recalibrar();
                return START_STICKY;
            }

            if (ACTION_PARAR.equals(action)) {
                pararServico();
                return START_NOT_STICKY;
            }

            // Escalada via AlarmManager — dispara mesmo com tela apagada (Doze)
            if (ACTION_ESCALAR.equals(action)) {
                String alertaId      = intent.getStringExtra(EXTRA_ALERTA_ID);
                int    proximoIndice = intent.getIntExtra(EXTRA_PROXIMO_INDICE, 0);
                int    bpm           = intent.getIntExtra(EXTRA_BPM, 0);
                String tipo          = intent.getStringExtra(EXTRA_TIPO);
                if (alertaId != null && tipo != null) {
                    Log.d(TAG, "ACTION_ESCALAR recebido: alerta=" + alertaId
                            + " próximo=" + proximoIndice);
                    verificarEEscalar(alertaId, proximoIndice, bpm, tipo);
                }
                return START_STICKY;
            }
        }

        startForeground(NOTIF_ID, buildNotification("Iniciando monitoramento...", "--"));
        carregarDadosPaciente();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        wakeLockRenewalHandler.removeCallbacksAndMessages(null);
        serviceWatchdogHandler.removeCallbacksAndMessages(null);
        if (pacienteDataListener != null) pacienteDataListener.remove();
        if (monitor != null) monitor.parar();
        liberarWakeLock();
        instance = null;
        super.onDestroy();
    }

    /**
     * App removido dos recentes. Agenda reinício em 10 s via AlarmManager.
     * setExactAndAllowWhileIdle(): dispara mesmo durante modo Doze.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "App removido dos recentes — reinício em 10s");
        PendingIntent pi = PendingIntent.getService(
                this, 1, new Intent(this, HeartRateService.class),
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 10_000L, pi);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ==================== WakeLock ====================

    private void adquirirWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
            wakeLock.setReferenceCounted(false);
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
                Log.d(TAG, "WakeLock adquirido");
            }
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível adquirir WakeLock: " + e.getMessage());
        }
    }

    private void liberarWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock liberado");
            }
        } catch (Exception e) {
            Log.w(TAG, "Erro ao liberar WakeLock: " + e.getMessage());
        }
    }

    /**
     * Renova o WakeLock a cada 9 min.
     * Alguns OEMs (Xiaomi, Huawei, Samsung agressivo) encerram WakeLocks
     * mantidos por muito tempo. A renovação garante CPU ativa indefinidamente.
     */
    private void agendarRenovacaoWakeLock() {
        wakeLockRenewalHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (wakeLock != null) {
                        if (!wakeLock.isHeld()) {
                            wakeLock.acquire();
                            Log.d(TAG, "WakeLock renovado (estava liberado)");
                        } else {
                            wakeLock.release();
                            wakeLock.acquire();
                            Log.d(TAG, "WakeLock renovado (ciclo de manutenção)");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Erro ao renovar WakeLock: " + e.getMessage());
                }
                wakeLockRenewalHandler.postDelayed(this, WAKELOCK_RENEWAL_MS);
            }
        }, WAKELOCK_RENEWAL_MS);
    }

    // ==================== Inicialização ====================

    private void carregarDadosPaciente() {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            Log.w(TAG, "Sem usuário autenticado — encerrando serviço");
            stopSelf();
            return;
        }

        uidPaciente = auth.getUid();

        pacienteDataListener = FirebaseFirestore.getInstance()
                .collection("users").document(uidPaciente)
                .addSnapshotListener((doc, e) -> {
                    if (e != null) {
                        Log.w(TAG, "Erro no listener do paciente: " + e.getMessage());
                        if (!monitorIniciado) { monitorIniciado = true; iniciarMonitor(); }
                        return;
                    }
                    if (doc != null && doc.exists()) {
                        String nome = doc.getString("nome");
                        if (nome != null) nomePaciente = nome;

                        @SuppressWarnings("unchecked")
                        List<String> lista = (List<String>) doc.get("cuidadoresVinculados");
                        if (lista != null) {
                            cuidadoresVinculados = new ArrayList<>(lista);
                        } else {
                            String legado = doc.getString("cuidadorVinculado");
                            cuidadoresVinculados = new ArrayList<>();
                            if (legado != null && !legado.isEmpty()) {
                                cuidadoresVinculados.add(legado);
                            }
                        }
                        Log.d(TAG, "Paciente: " + nomePaciente
                                + " | Cuidadores: " + cuidadoresVinculados.size());
                    }
                    if (!monitorIniciado) { monitorIniciado = true; iniciarMonitor(); }
                });
    }

    private void iniciarMonitor() {
        if (monitor == null) monitor = new HeartRateMonitor(this, this);
        monitor.iniciar();
        if (uidPaciente != null) {
            FirebaseHelper.salvarStatusMonitoramento(uidPaciente, "ATIVO");
        }
        // Inicia watchdog do serviço para detectar falhas que o monitor não conseguiu resolver
        iniciarWatchdogServico();
        Log.d(TAG, "Monitor cardíaco iniciado");
    }

    private void pararServico() {
        serviceWatchdogHandler.removeCallbacksAndMessages(null);
        if (uidPaciente != null) {
            FirebaseHelper.salvarStatusMonitoramento(uidPaciente, "PARADO");
        }
        if (monitor != null) monitor.parar();
        stopForeground(true);
        stopSelf();
    }

    // ==================== Watchdog do serviço (Nível 2 e 3) ====================

    /**
     * Watchdog em nível de serviço — complementa o watchdog interno do HeartRateMonitor.
     *
     * Executa a cada SERVICE_WATCHDOG_INTERVAL_MS (2 min) usando Handler no main looper.
     * Confiável porque o WakeLock mantém a CPU ativa e o main looper sempre processando.
     *
     * Se não chegou nenhuma leitura de BPM em SERVICE_WATCHDOG_TIMEOUT_MS (5 min):
     *  - Nível 2: reinicia o HeartRateMonitor (até MAX_MONITOR_RESTARTS vezes)
     *  - Nível 3: reinicia o próprio serviço via stopSelf() + AlarmManager de segurança
     */
    private void iniciarWatchdogServico() {
        // Cancela ciclo anterior antes de iniciar novo (evita duplicatas após reinício)
        serviceWatchdogHandler.removeCallbacksAndMessages(null);

        serviceWatchdogHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long silencio = System.currentTimeMillis() - lastHeartRateReceivedTime;

                if (silencio > SERVICE_WATCHDOG_TIMEOUT_MS) {
                    Log.w(TAG, "Watchdog do serviço: "
                            + (silencio / 1000) + "s sem leitura de BPM → reiniciando monitor"
                            + " (tentativa " + (monitorRestartCount + 1)
                            + "/" + MAX_MONITOR_RESTARTS + ")");
                    reiniciarMonitor();
                }

                // Reagenda indefinidamente — só para em pararServico() / onDestroy()
                serviceWatchdogHandler.postDelayed(this, SERVICE_WATCHDOG_INTERVAL_MS);
            }
        }, SERVICE_WATCHDOG_INTERVAL_MS);

        Log.d(TAG, "Watchdog do serviço iniciado (verificação a cada "
                + SERVICE_WATCHDOG_INTERVAL_MS / 1000 + "s, timeout="
                + SERVICE_WATCHDOG_TIMEOUT_MS / 1000 + "s)");
    }

    /**
     * Reinicia o HeartRateMonitor completamente (destrói e recria).
     *
     * Chamado em dois cenários:
     *  a) onNecessarioReiniciar() — watchdog interno do monitor esgotado
     *  b) Watchdog do serviço — 5 min sem leituras detectado externamente
     *
     * Após MAX_MONITOR_RESTARTS tentativas frustradas, reinicia o próprio serviço.
     */
    private void reiniciarMonitor() {
        monitorRestartCount++;

        if (monitorRestartCount > MAX_MONITOR_RESTARTS) {
            Log.e(TAG, "Máximo de reinicializações do monitor atingido ("
                    + MAX_MONITOR_RESTARTS + ") → reiniciando o próprio serviço");
            agendarReinicioPorSeguranca();
            stopSelf(); // START_STICKY vai recriar o serviço
            return;
        }

        Log.w(TAG, "Reiniciando HeartRateMonitor (tentativa "
                + monitorRestartCount + "/" + MAX_MONITOR_RESTARTS + ")");

        // Para o monitor atual (cancela watchdog interno via bgHandler.removeCallbacks)
        if (monitor != null) {
            monitor.parar();
            monitor = null;
        }

        // Reseta o timestamp para evitar novo disparo imediato do watchdog do serviço
        lastHeartRateReceivedTime = System.currentTimeMillis();

        // Recria e inicia monitor com estado limpo
        monitor = new HeartRateMonitor(this, this);
        monitor.iniciar();

        notifManager.notify(NOTIF_ID, buildNotification("Reconectando sensor...", "--"));

        if (uidPaciente != null) {
            FirebaseHelper.salvarStatusMonitoramento(uidPaciente, "RECONECTANDO");
        }
        Log.i(TAG, "HeartRateMonitor recriado com sucesso");
    }

    /**
     * Agenda reinício do serviço via AlarmManager como segurança extra.
     * setExactAndAllowWhileIdle() dispara mesmo durante Doze.
     */
    private void agendarReinicioPorSeguranca() {
        PendingIntent pi = PendingIntent.getService(
                this, 99, new Intent(this, HeartRateService.class),
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5_000L, pi);
            Log.d(TAG, "Reinício de segurança agendado via AlarmManager em 5s");
        }
    }

    // ==================== Callbacks do HeartRateMonitor ====================

    @Override
    public void onHeartRate(int bpm) {
        // Atualiza timestamp e reseta contador de reinicializações — sensor funcionando
        lastHeartRateReceivedTime = System.currentTimeMillis();
        if (monitorRestartCount > 0) {
            Log.i(TAG, "Leitura recebida após reinicialização — resetando contador");
            monitorRestartCount = 0;
        }

        HeartRateMonitor.Listener act = getActivityListener();
        if (act != null) act.onHeartRate(bpm);

        long agora = System.currentTimeMillis();

        if (agora - lastNotifUpdate >= NOTIF_UPDATE_INTERVAL_MS) {
            lastNotifUpdate = agora;
            String st = (monitor != null && bpm >= monitor.getBpmMin()
                    && bpm <= monitor.getBpmMax()) ? "Normal" : "Fora do intervalo";
            notifManager.notify(NOTIF_ID, buildNotification(st, String.valueOf(bpm)));
        }

        if (uidPaciente != null && agora - lastFirestoreUpdate >= FIRESTORE_UPDATE_INTERVAL_MS) {
            lastFirestoreUpdate = agora;
            try { FirebaseHelper.atualizarBpm(uidPaciente, bpm); }
            catch (Exception ex) { Log.w(TAG, "Falha ao atualizar BPM: " + ex.getMessage()); }
        }
    }

    @Override
    public void onStatusChange(String status) {
        HeartRateMonitor.Listener act = getActivityListener();
        if (act != null) act.onStatusChange(status);
        notifManager.notify(NOTIF_ID, buildNotification(status, "--"));
    }

    @Override
    public void onAnomaly(int bpm, HeartRateMonitor.AnomalyType tipo) {
        HeartRateMonitor.Listener act = getActivityListener();
        if (act != null) act.onAnomaly(bpm, tipo);

        String tituloLocal = tipo == HeartRateMonitor.AnomalyType.HIGH
                ? "Freq. cardíaca ALTA" : "Freq. cardíaca BAIXA";
        notifManager.notify(NOTIF_ID + 1,
                buildAlertNotification(tituloLocal, nomePaciente + ": " + bpm + " bpm"));

        if (cuidadoresVinculados.isEmpty()) {
            Log.w(TAG, "Anomalia sem cuidadores vinculados — somente alerta local");
            return;
        }
        enviarAlertaParaCuidador(0, bpm, tipo.name());
    }

    @Override
    public void onCalibrationProgress(int collected, int total) {
        HeartRateMonitor.Listener act = getActivityListener();
        if (act != null) act.onCalibrationProgress(collected, total);
        notifManager.notify(NOTIF_ID,
                buildNotification("Calibrando " + collected + "/" + total, "--"));
    }

    @Override
    public void onCalibrationComplete(int baseline, int min, int max) {
        HeartRateMonitor.Listener act = getActivityListener();
        if (act != null) act.onCalibrationComplete(baseline, min, max);
        if (uidPaciente != null) {
            FirebaseHelper.salvarBaseline(uidPaciente, baseline, min, max,
                    new FirebaseHelper.Callback<Void>() {
                        @Override public void onResult(Void v) {
                            Log.d(TAG, "Baseline: " + baseline + " [" + min + "-" + max + "]");
                        }
                        @Override public void onError(Exception e) {
                            Log.e(TAG, "Falha ao salvar baseline: " + e.getMessage());
                        }
                    });
        }
        notifManager.notify(NOTIF_ID,
                buildNotification("Calibrado — baseline " + baseline + " bpm", "--"));
    }

    /**
     * Hardware TYPE_HEART_RATE ausente — encerra o serviço.
     * Não é chamado por silêncio temporário (tratado pelos watchdogs).
     */
    @Override
    public void onSensorIndisponivel() {
        Log.e(TAG, "Hardware cardíaco indisponível — encerrando serviço");
        if (uidPaciente != null) {
            FirebaseHelper.salvarStatusMonitoramento(uidPaciente, "SEM_SENSOR");
        }
        HeartRateMonitor.Listener act = getActivityListener();
        if (act != null) act.onSensorIndisponivel();
        stopForeground(true);
        stopSelf();
    }

    /**
     * Watchdog interno do HeartRateMonitor esgotou todos os níveis de recuperação.
     * Reinicia o monitor completamente (Nível 2 de recuperação).
     */
    @Override
    public void onNecessarioReiniciar() {
        Log.w(TAG, "Monitor solicitou reinicialização (watchdog interno esgotado)");
        HeartRateMonitor.Listener act = getActivityListener();
        if (act != null) act.onNecessarioReiniciar();
        reiniciarMonitor();
    }

    // ==================== Escalada de alertas ====================

    /**
     * Envia alerta ao cuidador e agenda escalada via AlarmManager se houver próximo.
     *
     * AlarmManager.setExactAndAllowWhileIdle() dispara mesmo durante Doze,
     * garantindo que o cuidador seguinte seja notificado após exatamente 5 min
     * independentemente do estado de energia do dispositivo.
     */
    private void enviarAlertaParaCuidador(int indice, int bpm, String tipo) {
        if (indice >= cuidadoresVinculados.size()) {
            Log.d(TAG, "Escalada encerrada (índice " + indice + " fora da lista)");
            return;
        }
        if (uidPaciente == null) return;

        String uidCuidador = cuidadoresVinculados.get(indice);
        if (uidCuidador == null || uidCuidador.isEmpty() || uidCuidador.equals(uidPaciente)) {
            Log.e(TAG, "UID inválido no índice " + indice + " — pulando");
            enviarAlertaParaCuidador(indice + 1, bpm, tipo);
            return;
        }

        int bpmMin = (monitor != null) ? monitor.getBpmMin() : -1;
        int bpmMax = (monitor != null) ? monitor.getBpmMax() : -1;

        FirebaseHelper.enviarAlerta(
                uidPaciente, nomePaciente, uidCuidador, bpm, tipo, indice, bpmMin, bpmMax,
                new FirebaseHelper.Callback<String>() {
                    @Override
                    public void onResult(String alertaId) {
                        Log.d(TAG, "Alerta[" + indice + "] enviado: " + alertaId);
                        if (indice + 1 < cuidadoresVinculados.size()) {
                            agendarEscalada(alertaId, indice + 1, bpm, tipo);
                        }
                    }
                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Falha ao enviar alerta[" + indice + "]: " + e.getMessage());
                        enviarAlertaParaCuidador(indice + 1, bpm, tipo);
                    }
                });
    }

    private void agendarEscalada(String alertaId, int proximoIndice, int bpm, String tipo) {
        Intent intent = new Intent(this, HeartRateService.class);
        intent.setAction(ACTION_ESCALAR);
        intent.putExtra(EXTRA_ALERTA_ID,      alertaId);
        intent.putExtra(EXTRA_PROXIMO_INDICE, proximoIndice);
        intent.putExtra(EXTRA_BPM,            bpm);
        intent.putExtra(EXTRA_TIPO,           tipo);

        int requestCode = (alertaId + ":" + proximoIndice).hashCode();
        PendingIntent pi = PendingIntent.getService(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ESCALADA_MS, pi);
            Log.d(TAG, "Escalada agendada para cuidador[" + proximoIndice
                    + "] em " + ESCALADA_MS / 60_000 + " min");
        }
    }

    private void verificarEEscalar(String alertaId, int proximoIndice, int bpm, String tipo) {
        FirebaseHelper.alertaFoiConfirmado(alertaId, new FirebaseHelper.Callback<Boolean>() {
            @Override
            public void onResult(Boolean confirmado) {
                if (Boolean.TRUE.equals(confirmado)) {
                    Log.d(TAG, "Alerta " + alertaId + " confirmado — escalada cancelada");
                } else {
                    Log.d(TAG, "Alerta " + alertaId + " não confirmado → escalando");
                    enviarAlertaParaCuidador(proximoIndice, bpm, tipo);
                }
            }
            @Override
            public void onError(Exception e) {
                Log.w(TAG, "Erro ao verificar confirmação — escalando por precaução");
                enviarAlertaParaCuidador(proximoIndice, bpm, tipo);
            }
        });
    }

    // ==================== Emergência manual ====================

    public void dispararEmergenciaManual(int bpm) {
        if (uidPaciente == null || cuidadoresVinculados.isEmpty()) return;

        int bpmMin = (monitor != null) ? monitor.getBpmMin() : -1;
        int bpmMax = (monitor != null) ? monitor.getBpmMax() : -1;

        for (int i = 0; i < cuidadoresVinculados.size(); i++) {
            String uid = cuidadoresVinculados.get(i);
            if (uid == null || uid.isEmpty() || uid.equals(uidPaciente)) continue;

            final String uidFinal = uid;
            final int    indiceFinal = i;

            FirebaseHelper.enviarAlerta(
                    uidPaciente, nomePaciente, uidFinal, bpm, "MANUAL", indiceFinal, bpmMin, bpmMax,
                    new FirebaseHelper.Callback<String>() {
                        @Override public void onResult(String id) {
                            Log.d(TAG, "Emergência → cuidador[" + indiceFinal + "]: " + uidFinal);
                        }
                        @Override public void onError(Exception e) {
                            Log.e(TAG, "Falha emergência → cuidador[" + indiceFinal + "]: "
                                    + e.getMessage());
                        }
                    });
        }
    }

    // ==================== Utilitários ====================

    @Nullable
    private HeartRateMonitor.Listener getActivityListener() {
        return activityListenerRef != null ? activityListenerRef.get() : null;
    }

    // ==================== Notificações ====================

    private void criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel canal = new NotificationChannel(
                    CHANNEL_ID, "EverNear — Monitoramento",
                    NotificationManager.IMPORTANCE_LOW);
            canal.setDescription("Monitoramento contínuo de frequência cardíaca");
            canal.setShowBadge(false);
            notifManager.createNotificationChannel(canal);

            NotificationChannel canalAlerta = new NotificationChannel(
                    CHANNEL_ID + "_alerta", "Alertas EverNear",
                    NotificationManager.IMPORTANCE_HIGH);
            canalAlerta.setDescription("Alertas de frequência fora do normal");
            canalAlerta.enableVibration(true);
            canalAlerta.setVibrationPattern(new long[]{0, 400, 200, 400});
            canalAlerta.setBypassDnd(true);
            canalAlerta.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notifManager.createNotificationChannel(canalAlerta);
        }
    }

    private Notification buildNotification(String status, String bpm) {
        Intent openApp = new Intent(this, PatientActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_heart_heartbeat)
                .setContentTitle("EverNear — " + bpm + " bpm")
                .setContentText(status)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private Notification buildAlertNotification(String titulo, String texto) {
        Intent openApp = new Intent(this, PatientActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID + "_alerta")
                .setSmallIcon(R.drawable.ic_heart_heartbeat)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build();
    }
}

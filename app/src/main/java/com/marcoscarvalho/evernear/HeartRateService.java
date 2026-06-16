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
 * Serviço de monitoramento contínuo de batimentos cardíacos — roda no SMARTWATCH do paciente.
 *
 * ┌─ Por que funciona com o app fechado? ──────────────────────────────────────┐
 * │  1. Foreground Service: o Android não encerra processos em primeiro plano  │
 * │     sem motivo crítico de memória.                                          │
 * │  2. WakeLock (PARTIAL_WAKE_LOCK): mantém a CPU ativa mesmo com tela        │
 * │     apagada, garantindo que o SensorManager continue entregando leituras.   │
 * │  3. START_STICKY: se o sistema encerrar o serviço por pressão de memória,  │
 * │     o Android o reinicia automaticamente.                                   │
 * │  4. onTaskRemoved + AlarmManager: se o usuário remover o app da lista de   │
 * │     recentes, agenda reinício em 5 segundos via AlarmManager.               │
 * │  5. BootReceiver: reinicia o serviço quando o relógio é ligado.            │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * Escalada de alertas:
 *  - Anomalia detectada → envia para cuidador[0]
 *  - Se não confirmado em 5 min → envia para cuidador[1]
 *  - Se não confirmado em 5 min → envia para cuidador[2]
 *  - Emergência manual → todos os cuidadores simultaneamente
 */
public class HeartRateService extends Service implements HeartRateMonitor.Listener {

    private static final String TAG = "HeartRateService";

    public static final String ACTION_CALIBRAR = "com.marcoscarvalho.evernear.ACTION_CALIBRAR";
    public static final String ACTION_PARAR    = "com.marcoscarvalho.evernear.ACTION_PARAR";

    private static final String CHANNEL_ID  = "evernear_monitor";
    private static final int    NOTIF_ID    = 1001;

    /** Tempo de espera antes de escalar para o próximo cuidador: 5 minutos. */
    private static final long ESCALADA_MS = 5 * 60 * 1000L;

    // Tag WakeLock — único por processo para evitar duplicação
    private static final String WAKELOCK_TAG = "EverNear:HeartRateWakeLock";

    private static HeartRateService instance;
    private static WeakReference<HeartRateMonitor.Listener> activityListenerRef;

    private HeartRateMonitor monitor;
    private NotificationManager notifManager;
    private PowerManager.WakeLock wakeLock;
    private final Handler escaladaHandler = new Handler(Looper.getMainLooper());

    // Dados do paciente (atualizados em tempo real via Firestore snapshot listener)
    private String uidPaciente;
    private String nomePaciente = "Paciente";
    private List<String> cuidadoresVinculados = new ArrayList<>();
    private ListenerRegistration pacienteDataListener;
    private boolean monitorIniciado = false;

    // Throttle: evita flood de notificações/Firestore
    private long lastNotifUpdate     = 0;
    private long lastFirestoreUpdate = 0;
    private static final long NOTIF_UPDATE_INTERVAL_MS     = 3_000L;
    private static final long FIRESTORE_UPDATE_INTERVAL_MS = 3_000L; // 3 s para testes

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
        instance = this;
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        criarCanalNotificacao();
        adquirirWakeLock();
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
        }

        startForeground(NOTIF_ID, buildNotification("Iniciando monitoramento...", "--"));
        carregarDadosPaciente();
        return START_STICKY; // reinicia automaticamente se o sistema encerrar
    }

    @Override
    public void onDestroy() {
        escaladaHandler.removeCallbacksAndMessages(null);
        if (pacienteDataListener != null) pacienteDataListener.remove();
        if (monitor != null) monitor.parar();
        liberarWakeLock();
        instance = null;
        super.onDestroy();
    }

    /**
     * Chamado quando o usuário remove o app da lista de recentes (swipe).
     *
     * Com android:stopWithTask="false" no Manifest, o serviço NÃO é encerrado
     * automaticamente — este método é chamado apenas para notificar o serviço do
     * fechamento do app. Por segurança extra, agenda um reinício em 10 segundos via
     * AlarmManager para o caso de o sistema encerrar o processo por pressão de memória.
     *
     * IMPORTANTE: usa setExactAndAllowWhileIdle() — o am.set() simples NÃO funciona
     * durante o modo Doze (Android 6+), que é ativado quando a tela apaga no relógio.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "App removido dos recentes — agendando reinício de segurança em 10s");

        PendingIntent reiniciar = PendingIntent.getService(
                this, 1,
                new Intent(this, HeartRateService.class),
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            // setExactAndAllowWhileIdle(): dispara mesmo durante Doze (tela apagada)
            // Requer SCHEDULE_EXACT_ALARM (declarada no Manifest)
            am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 10_000L,
                    reiniciar);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ==================== WakeLock ====================

    /**
     * PARTIAL_WAKE_LOCK: mantém apenas a CPU ativa (não a tela).
     * Essencial para que o SensorManager continue entregando leituras
     * quando a tela do relógio apaga (modo ambient/sleep).
     */
    private void adquirirWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
            wakeLock.setReferenceCounted(false);
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(); // sem timeout: liberado manualmente no onDestroy
                Log.d(TAG, "WakeLock adquirido — CPU permanecerá ativa em segundo plano");
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

    // ==================== Inicialização ====================

    private void carregarDadosPaciente() {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            Log.w(TAG, "Sem usuário autenticado — encerrando serviço");
            stopSelf();
            return;
        }

        uidPaciente = auth.getUid();

        // Snapshot listener: mantém cuidadoresVinculados sempre atualizado
        // mesmo se o vínculo for feito depois que o serviço já estava rodando.
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

                        // Lê array de cuidadores (novo schema)
                        @SuppressWarnings("unchecked")
                        List<String> lista = (List<String>) doc.get("cuidadoresVinculados");
                        if (lista != null) {
                            cuidadoresVinculados = new ArrayList<>(lista);
                        } else {
                            // Compatibilidade com schema antigo (campo único)
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
        Log.d(TAG, "Monitor cardíaco iniciado");
    }

    private void pararServico() {
        if (monitor != null) monitor.parar();
        stopForeground(true);
        stopSelf();
    }

    // ==================== Callbacks do HeartRateMonitor ====================

    @Override
    public void onHeartRate(int bpm) {
        HeartRateMonitor.Listener act = getActivityListener();
        if (act != null) act.onHeartRate(bpm);

        long agora = System.currentTimeMillis();

        // Atualiza notificação persistente (throttled — máx 1x a cada 3s)
        if (agora - lastNotifUpdate >= NOTIF_UPDATE_INTERVAL_MS) {
            lastNotifUpdate = agora;
            String st = (monitor != null && bpm >= monitor.getBpmMin()
                    && bpm <= monitor.getBpmMax()) ? "Normal" : "⚠ Fora do intervalo";
            notifManager.notify(NOTIF_ID, buildNotification(st, String.valueOf(bpm)));
        }

        // Persiste BPM no Firestore (throttled — máx 1x a cada 5s)
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

        // Notificação local no relógio (informa o próprio paciente)
        String tituloLocal = tipo == HeartRateMonitor.AnomalyType.HIGH
                ? "❤ Freq. cardíaca ALTA" : "💙 Freq. cardíaca BAIXA";
        notifManager.notify(NOTIF_ID + 1,
                buildAlertNotification(tituloLocal, nomePaciente + ": " + bpm + " bpm"));

        // Envia alerta ao cuidador via Firestore
        if (cuidadoresVinculados.isEmpty()) {
            Log.w(TAG, "Anomalia detectada mas sem cuidadores vinculados — somente alerta local");
            return;
        }

        // Inicia cadeia de escalada: começa pelo cuidador de prioridade 0
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
        if (uidPaciente != null) FirebaseHelper.salvarBaseline(uidPaciente, baseline, min, max);
        notifManager.notify(NOTIF_ID,
                buildNotification("Calibrado — baseline " + baseline + " bpm", "--"));
    }

    // ==================== Escalada de alertas ====================

    /**
     * Envia alerta para o cuidador no índice `indice` da lista de prioridade.
     * Após ESCALADA_MS (5 min), verifica se foi confirmado:
     *   - Não confirmado + próximo cuidador existe → envia ao próximo
     *   - Confirmado ou último cuidador → cadeia encerrada
     */
    private void enviarAlertaParaCuidador(int indice, int bpm, String tipo) {
        if (indice >= cuidadoresVinculados.size()) {
            Log.d(TAG, "Escalada encerrada (sem mais cuidadores no índice " + indice + ")");
            return;
        }
        if (uidPaciente == null) return;

        String uidCuidador = cuidadoresVinculados.get(indice);

        // Segurança: nunca envia alerta para o próprio paciente
        if (uidCuidador == null || uidCuidador.isEmpty() || uidCuidador.equals(uidPaciente)) {
            Log.e(TAG, "UID de cuidador inválido no índice " + indice + " — pulando");
            enviarAlertaParaCuidador(indice + 1, bpm, tipo);
            return;
        }

        Log.d(TAG, "Enviando alerta para cuidador[" + indice + "]: " + uidCuidador);

        FirebaseHelper.enviarAlerta(
                uidPaciente, nomePaciente, uidCuidador, bpm, tipo, indice,
                new FirebaseHelper.Callback<String>() {
                    @Override
                    public void onResult(String alertaId) {
                        Log.d(TAG, "Alerta [" + indice + "] enviado — id: " + alertaId);
                        // Agenda verificação em 5 min se há próximo cuidador
                        if (indice + 1 < cuidadoresVinculados.size()) {
                            escaladaHandler.postDelayed(
                                    () -> verificarEEscalar(alertaId, indice + 1, bpm, tipo),
                                    ESCALADA_MS);
                            Log.d(TAG, "Escalada agendada para cuidador[" + (indice + 1) + "] em 5 min");
                        }
                    }
                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Falha ao enviar alerta [" + indice + "]: " + e.getMessage());
                        // Falha de rede? Tenta o próximo imediatamente
                        enviarAlertaParaCuidador(indice + 1, bpm, tipo);
                    }
                });
    }

    /** Verifica se o alerta foi confirmado; se não, escala para o próximo cuidador. */
    private void verificarEEscalar(String alertaId, int proximoIndice, int bpm, String tipo) {
        FirebaseHelper.alertaFoiConfirmado(alertaId, new FirebaseHelper.Callback<Boolean>() {
            @Override
            public void onResult(Boolean confirmado) {
                if (Boolean.TRUE.equals(confirmado)) {
                    Log.d(TAG, "Alerta " + alertaId + " confirmado — escalada cancelada");
                } else {
                    Log.d(TAG, "Alerta " + alertaId + " não confirmado após 5 min → escalando");
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

    /**
     * Acionado pelo botão SOS da PatientActivity.
     * Envia alerta de EMERGÊNCIA para TODOS os cuidadores simultaneamente (sem escalada).
     */
    public void dispararEmergenciaManual(int bpm) {
        if (uidPaciente == null || cuidadoresVinculados.isEmpty()) return;

        for (String uidCuidador : cuidadoresVinculados) {
            if (uidCuidador == null || uidCuidador.isEmpty()
                    || uidCuidador.equals(uidPaciente)) continue;

            FirebaseHelper.enviarAlerta(uidPaciente, nomePaciente,
                    uidCuidador, bpm, "MANUAL", 0,
                    new FirebaseHelper.Callback<String>() {
                        @Override public void onResult(String id) {
                            Log.d(TAG, "Emergência enviada para: " + uidCuidador);
                        }
                        @Override public void onError(Exception e) {
                            Log.e(TAG, "Falha ao enviar emergência para " + uidCuidador, e);
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
            // Canal persistente (baixa prioridade — apenas para manter o serviço visível)
            NotificationChannel canal = new NotificationChannel(
                    CHANNEL_ID,
                    "EverNear — Monitoramento",
                    NotificationManager.IMPORTANCE_LOW);
            canal.setDescription("Monitoramento contínuo de frequência cardíaca");
            canal.setShowBadge(false);
            notifManager.createNotificationChannel(canal);

            // Canal de alertas (alta prioridade — vibra e aparece na tela do relógio)
            NotificationChannel canalAlerta = new NotificationChannel(
                    CHANNEL_ID + "_alerta",
                    "Alertas EverNear",
                    NotificationManager.IMPORTANCE_HIGH);
            canalAlerta.setDescription("Alertas de frequência cardíaca fora do normal");
            canalAlerta.enableVibration(true);
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
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build();
    }
}

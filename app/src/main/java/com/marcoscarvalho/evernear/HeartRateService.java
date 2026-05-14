package com.marcoscarvalho.evernear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
 * Foreground Service de monitoramento cardíaco — roda no dispositivo do PACIENTE.
 *
 * Responsabilidades:
 *  1. Ler frequência cardíaca via SensorManager / simulador
 *  2. Detectar anomalias e enviar alertas ao Firestore
 *  3. Escalada automática de alertas para múltiplos cuidadores:
 *       - Envia primeiro ao cuidador de prioridade 0
 *       - Se não confirmado em 5 minutos → envia ao cuidador 1 (se existir)
 *       - Se não confirmado em mais 5 minutos → envia ao cuidador 2 (se existir)
 *  4. Manter notificação persistente com BPM atual
 *
 * Dados do paciente são mantidos via snapshot listener (sempre atualizados).
 */
public class HeartRateService extends Service implements HeartRateMonitor.Listener {

    private static final String TAG = "HeartRateService";

    public static final String ACTION_CALIBRAR = "com.marcoscarvalho.evernear.ACTION_CALIBRAR";
    public static final String ACTION_PARAR    = "com.marcoscarvalho.evernear.ACTION_PARAR";

    private static final String CHANNEL_ID  = "evernear_monitor";
    private static final int    NOTIF_ID    = 1001;

    // Tempo de espera antes de escalar para o próximo cuidador (5 minutos)
    private static final long ESCALADA_MS = 5 * 60 * 1000L;

    private static HeartRateService instance;
    private static WeakReference<HeartRateMonitor.Listener> activityListenerRef;

    private HeartRateMonitor monitor;
    private NotificationManager notifManager;
    private final Handler escaladaHandler = new Handler(Looper.getMainLooper());

    // Dados do paciente — atualizados em tempo real via snapshot listener
    private String uidPaciente;
    private String nomePaciente = "Paciente";
    private List<String> cuidadoresVinculados = new ArrayList<>(); // ordem = prioridade de escalada
    private ListenerRegistration pacienteDataListener;
    private boolean monitorIniciado = false;

    // Throttle
    private long lastNotifUpdate    = 0;
    private long lastFirestoreUpdate = 0;
    private static final long NOTIF_UPDATE_INTERVAL_MS     = 3_000L;
    private static final long FIRESTORE_UPDATE_INTERVAL_MS = 5_000L;

    // ==================== API estática ====================

    public static HeartRateService getInstance() { return instance; }
    public HeartRateMonitor getMonitor() { return monitor; }

    public static void setActivityListener(@Nullable HeartRateMonitor.Listener listener) {
        activityListenerRef = listener != null ? new WeakReference<>(listener) : null;
    }

    /** Lista de UIDs dos cuidadores vinculados (em ordem de prioridade). */
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

        startForeground(NOTIF_ID, buildNotification("Iniciando...", "--"));
        carregarDadosPaciente();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        escaladaHandler.removeCallbacksAndMessages(null);
        if (pacienteDataListener != null) pacienteDataListener.remove();
        if (monitor != null) monitor.parar();
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ==================== Inicialização ====================

    private void carregarDadosPaciente() {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            Log.w(TAG, "Sem usuário autenticado — encerrando serviço");
            stopSelf();
            return;
        }

        uidPaciente = auth.getUid();

        // Snapshot listener: garante que cuidadoresVinculados fica sempre atualizado,
        // inclusive se o vínculo for feito DEPOIS que o serviço já estava rodando.
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

                        // Lê cuidadoresVinculados (novo schema: array ordenado por prioridade)
                        @SuppressWarnings("unchecked")
                        List<String> lista = (List<String>) doc.get("cuidadoresVinculados");
                        if (lista != null) {
                            cuidadoresVinculados = new ArrayList<>(lista);
                        } else {
                            // Compatibilidade com documentos antigos (campo único)
                            String legado = doc.getString("cuidadorVinculado");
                            cuidadoresVinculados = new ArrayList<>();
                            if (legado != null && !legado.isEmpty()) {
                                cuidadoresVinculados.add(legado);
                            }
                        }
                        Log.d(TAG, "Cuidadores vinculados: " + cuidadoresVinculados.size());
                    }
                    if (!monitorIniciado) { monitorIniciado = true; iniciarMonitor(); }
                });
    }

    private void iniciarMonitor() {
        if (monitor == null) monitor = new HeartRateMonitor(this, this);
        monitor.iniciar();
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

        if (agora - lastNotifUpdate >= NOTIF_UPDATE_INTERVAL_MS) {
            lastNotifUpdate = agora;
            String st = (monitor != null && bpm >= monitor.getBpmMin()
                    && bpm <= monitor.getBpmMax()) ? "Normal" : "⚠ Fora do intervalo";
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
        // Repassa para a Activity se estiver aberta
        HeartRateMonitor.Listener act = getActivityListener();
        if (act != null) act.onAnomaly(bpm, tipo);

        // Notificação local no próprio relógio (informa o paciente)
        String tituloLocal = tipo == HeartRateMonitor.AnomalyType.HIGH
                ? "❤ Freq. cardíaca ALTA" : "💙 Freq. cardíaca BAIXA";
        notifManager.notify(NOTIF_ID + 1,
                buildAlertNotification(tituloLocal, nomePaciente + ": " + bpm + " bpm"));

        // Sem cuidadores vinculados — alerta só local
        if (cuidadoresVinculados.isEmpty()) {
            Log.w(TAG, "Sem cuidadores vinculados — alerta local apenas");
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

    // ==================== Lógica de escalada de alertas ====================

    /**
     * Envia alerta para o cuidador na posição `indice` de cuidadoresVinculados.
     * Após ESCALADA_MS (5 min), verifica se foi confirmado:
     *   - Se não confirmado E existe próximo cuidador → envia para o próximo.
     *   - Se confirmado OU não há próximo → cadeia encerrada.
     */
    private void enviarAlertaParaCuidador(int indice, int bpm, String tipo) {
        if (indice >= cuidadoresVinculados.size()) {
            Log.d(TAG, "Escalada encerrada — sem mais cuidadores no índice " + indice);
            return;
        }
        if (uidPaciente == null) return;

        String uidCuidador = cuidadoresVinculados.get(indice);

        // Validação: garante que o UID do cuidador é diferente do UID do paciente
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
                        Log.d(TAG, "Alerta [" + indice + "] enviado: " + alertaId);

                        // Agenda verificação daqui a 5 min (só se houver próximo cuidador)
                        int proximoIndice = indice + 1;
                        if (proximoIndice < cuidadoresVinculados.size()) {
                            escaladaHandler.postDelayed(() ->
                                            verificarEEscalar(alertaId, proximoIndice, bpm, tipo),
                                    ESCALADA_MS);
                            Log.d(TAG, "Escalada agendada em 5 min para cuidador[" + proximoIndice + "]");
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Falha ao enviar alerta [" + indice + "]: " + e.getMessage());
                        // Tenta o próximo cuidador imediatamente se falhar
                        enviarAlertaParaCuidador(indice + 1, bpm, tipo);
                    }
                });
    }

    /**
     * Verifica no Firestore se o alerta anterior foi confirmado.
     * Se não foi confirmado, escala para o próximo cuidador.
     */
    private void verificarEEscalar(String alertaId, int proximoIndice, int bpm, String tipo) {
        FirebaseHelper.alertaFoiConfirmado(alertaId, new FirebaseHelper.Callback<Boolean>() {
            @Override
            public void onResult(Boolean confirmado) {
                if (Boolean.TRUE.equals(confirmado)) {
                    Log.d(TAG, "Alerta " + alertaId + " confirmado — escalada cancelada");
                } else {
                    Log.d(TAG, "Alerta " + alertaId + " NÃO confirmado após 5 min — escalando para cuidador["
                            + proximoIndice + "]");
                    enviarAlertaParaCuidador(proximoIndice, bpm, tipo);
                }
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "Erro ao verificar confirmação — escalando por precaução: " + e.getMessage());
                enviarAlertaParaCuidador(proximoIndice, bpm, tipo);
            }
        });
    }

    // ==================== Emergência manual ====================

    /**
     * Envia alerta de emergência para TODOS os cuidadores simultaneamente.
     * Chamado pela PatientActivity (botão SOS).
     */
    public void dispararEmergenciaManual(int bpm) {
        if (uidPaciente == null || cuidadoresVinculados.isEmpty()) return;

        for (String uidCuidador : cuidadoresVinculados) {
            if (uidCuidador == null || uidCuidador.isEmpty()
                    || uidCuidador.equals(uidPaciente)) continue;

            FirebaseHelper.enviarAlerta(uidPaciente, nomePaciente, uidCuidador,
                    bpm, "MANUAL", 0, new FirebaseHelper.Callback<String>() {
                        @Override
                        public void onResult(String id) {
                            Log.d(TAG, "Emergência manual enviada para: " + uidCuidador);
                        }
                        @Override
                        public void onError(Exception e) {
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
            NotificationChannel canal = new NotificationChannel(
                    CHANNEL_ID, "Monitoramento EverNear", NotificationManager.IMPORTANCE_LOW);
            canal.setDescription("Monitoramento contínuo de frequência cardíaca");
            canal.setShowBadge(false);
            notifManager.createNotificationChannel(canal);

            NotificationChannel canalAlerta = new NotificationChannel(
                    CHANNEL_ID + "_alerta", "Alertas EverNear", NotificationManager.IMPORTANCE_HIGH);
            canalAlerta.setDescription("Alertas de frequência cardíaca anormal");
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

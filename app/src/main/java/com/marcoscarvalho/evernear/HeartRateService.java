package com.marcoscarvalho.evernear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import java.lang.ref.WeakReference;

/**
 * Foreground Service — mantém o monitoramento de frequência cardíaca ativo
 * mesmo quando o app está fechado ou em segundo plano.
 *
 * Ciclo de vida:
 *  - Iniciado pela PatientActivity após permissão concedida
 *  - Reiniciado automaticamente no boot via BootReceiver
 *  - START_STICKY → Android reinicia se for encerrado pelo sistema
 *  - Exibe notificação persistente com BPM atual (obrigatório para serviço em 1º plano)
 *
 * Comunicação com PatientActivity (quando aberta):
 *  - PatientActivity registra um Listener via setActivityListener()
 *  - Quando a Activity fecha, o listener é removido (WeakReference evita vazamento)
 */
public class HeartRateService extends Service implements HeartRateMonitor.Listener {

    private static final String TAG = "HeartRateService";

    public static final String ACTION_CALIBRAR =
            "com.marcoscarvalho.evernear.ACTION_CALIBRAR";
    public static final String ACTION_PARAR =
            "com.marcoscarvalho.evernear.ACTION_PARAR";

    private static final String CHANNEL_ID = "evernear_monitor";
    private static final int NOTIF_ID = 1001;

    // Referência estática para a Activity obter o estado do serviço
    private static HeartRateService instance;

    // Listener da Activity (fraco — não impede GC se Activity for destruída)
    private static WeakReference<HeartRateMonitor.Listener> activityListenerRef;

    private HeartRateMonitor monitor;
    private NotificationManager notifManager;

    // Dados do paciente — mantidos em tempo real via snapshot listener
    private String uidPaciente;
    private String nomePaciente = "Paciente";
    private String uidCuidador;
    private com.google.firebase.firestore.ListenerRegistration pacienteDataListener;
    private boolean monitorIniciado = false;

    // Throttle da notificação (evita refresh excessivo)
    private long lastNotifUpdate = 0;
    private static final long NOTIF_UPDATE_INTERVAL_MS = 3_000L;

    // Throttle do Firestore
    private long lastFirestoreUpdate = 0;
    private static final long FIRESTORE_UPDATE_INTERVAL_MS = 5_000L;

    // ==================== API estática para PatientActivity ====================

    public static HeartRateService getInstance() {
        return instance;
    }

    public HeartRateMonitor getMonitor() {
        return monitor;
    }

    /**
     * Registra a Activity como receptor de atualizações enquanto está visível.
     * Passar null remove o listener (chamar em onPause).
     */
    public static void setActivityListener(@Nullable HeartRateMonitor.Listener listener) {
        activityListenerRef = listener != null ? new WeakReference<>(listener) : null;
    }

    // ==================== Ciclo de vida do Service ====================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        criarCanalNotificacao();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Trata ações enviadas pela Activity
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

        // Inicia como serviço em primeiro plano com notificação persistente
        startForeground(NOTIF_ID, buildNotification("Iniciando...", "--"));

        // Carrega dados do paciente e inicia o monitor
        carregarDadosPaciente();

        return START_STICKY; // Android reinicia se matar o processo
    }

    @Override
    public void onDestroy() {
        if (pacienteDataListener != null) pacienteDataListener.remove();
        if (monitor != null) monitor.parar();
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // não usa binding
    }

    // ==================== Inicialização ====================

    private void carregarDadosPaciente() {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            Log.w(TAG, "Nenhum usuário autenticado — serviço encerrado");
            stopSelf();
            return;
        }

        uidPaciente = auth.getUid();

        // Snapshot listener em vez de .get() — mantém uidCuidador sempre atualizado.
        // Isso resolve o caso em que o vínculo com o cuidador é feito DEPOIS que o
        // serviço já está rodando (o .get() guardaria null para sempre).
        pacienteDataListener = FirebaseFirestore.getInstance()
                .collection("users").document(uidPaciente)
                .addSnapshotListener((doc, e) -> {
                    if (e != null) {
                        Log.w(TAG, "Erro no listener do paciente: " + e.getMessage());
                        if (!monitorIniciado) {
                            monitorIniciado = true;
                            iniciarMonitor(); // inicia mesmo sem dados
                        }
                        return;
                    }
                    if (doc != null && doc.exists()) {
                        String nome = doc.getString("nome");
                        if (nome != null) nomePaciente = nome;
                        uidCuidador = doc.getString("cuidadorVinculado");
                        Log.d(TAG, "Dados do paciente atualizados — cuidador: " + uidCuidador);
                    }
                    // Inicia o monitor apenas na primeira vez
                    if (!monitorIniciado) {
                        monitorIniciado = true;
                        iniciarMonitor();
                    }
                });
    }

    private void iniciarMonitor() {
        if (monitor == null) {
            monitor = new HeartRateMonitor(this, this);
        }
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
        // Repassa para a Activity se estiver aberta
        HeartRateMonitor.Listener actListener = activityListenerRef != null
                ? activityListenerRef.get() : null;
        if (actListener != null) actListener.onHeartRate(bpm);

        long agora = System.currentTimeMillis();

        // Atualiza notificação (throttled)
        if (agora - lastNotifUpdate >= NOTIF_UPDATE_INTERVAL_MS) {
            lastNotifUpdate = agora;
            String statusTxt = (monitor != null && bpm >= monitor.getBpmMin()
                    && bpm <= monitor.getBpmMax()) ? "Normal" : "⚠ Fora do intervalo";
            notifManager.notify(NOTIF_ID, buildNotification(statusTxt, String.valueOf(bpm)));
        }

        // Atualiza Firestore (throttled)
        if (uidPaciente != null && agora - lastFirestoreUpdate >= FIRESTORE_UPDATE_INTERVAL_MS) {
            lastFirestoreUpdate = agora;
            try {
                FirebaseHelper.atualizarBpm(uidPaciente, bpm);
            } catch (Exception e) {
                Log.w(TAG, "Falha ao atualizar BPM: " + e.getMessage());
            }
        }
    }

    @Override
    public void onStatusChange(String status) {
        HeartRateMonitor.Listener actListener = activityListenerRef != null
                ? activityListenerRef.get() : null;
        if (actListener != null) actListener.onStatusChange(status);

        // Atualiza notificação com novo status
        notifManager.notify(NOTIF_ID, buildNotification(status, "--"));
    }

    @Override
    public void onAnomaly(int bpm, HeartRateMonitor.AnomalyType tipo) {
        HeartRateMonitor.Listener actListener = activityListenerRef != null
                ? activityListenerRef.get() : null;
        if (actListener != null) actListener.onAnomaly(bpm, tipo);

        // Notificação de alerta de alta prioridade (visível mesmo com app fechado)
        String titulo = tipo == HeartRateMonitor.AnomalyType.HIGH
                ? "❤ Freq. cardíaca ALTA" : "💙 Freq. cardíaca BAIXA";
        String texto = nomePaciente + ": " + bpm + " bpm";
        notifManager.notify(NOTIF_ID + 1, buildAlertNotification(titulo, texto));

        // Envia alerta ao Firebase
        if (uidPaciente == null || uidCuidador == null || uidCuidador.isEmpty()) {
            Log.w(TAG, "Sem cuidador vinculado — alerta local apenas");
            return;
        }
        FirebaseHelper.enviarAlerta(uidPaciente, nomePaciente, uidCuidador,
                bpm, tipo.name(), new FirebaseHelper.Callback<String>() {
                    @Override
                    public void onResult(String id) {
                        Log.d(TAG, "Alerta Firebase enviado: " + id);
                    }
                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Falha ao enviar alerta Firebase", e);
                    }
                });
    }

    @Override
    public void onCalibrationProgress(int collected, int total) {
        HeartRateMonitor.Listener actListener = activityListenerRef != null
                ? activityListenerRef.get() : null;
        if (actListener != null) actListener.onCalibrationProgress(collected, total);
        notifManager.notify(NOTIF_ID,
                buildNotification("Calibrando " + collected + "/" + total, "--"));
    }

    @Override
    public void onCalibrationComplete(int baseline, int min, int max) {
        HeartRateMonitor.Listener actListener = activityListenerRef != null
                ? activityListenerRef.get() : null;
        if (actListener != null) actListener.onCalibrationComplete(baseline, min, max);

        if (uidPaciente != null) {
            FirebaseHelper.salvarBaseline(uidPaciente, baseline, min, max);
        }
        notifManager.notify(NOTIF_ID,
                buildNotification("Calibrado — baseline " + baseline + " bpm", "--"));
    }

    // ==================== Notificações ====================

    private void criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel canal = new NotificationChannel(
                    CHANNEL_ID,
                    "Monitoramento EverNear",
                    NotificationManager.IMPORTANCE_LOW // baixa para não interromper; alertas usam HIGH
            );
            canal.setDescription("Monitoramento contínuo de frequência cardíaca");
            canal.setShowBadge(false);
            notifManager.createNotificationChannel(canal);

            // Canal separado para alertas de anomalia (alta prioridade)
            NotificationChannel canalAlerta = new NotificationChannel(
                    CHANNEL_ID + "_alerta",
                    "Alertas EverNear",
                    NotificationManager.IMPORTANCE_HIGH
            );
            canalAlerta.setDescription("Alertas de frequência cardíaca anormal");
            notifManager.createNotificationChannel(canalAlerta);
        }
    }

    private Notification buildNotification(String status, String bpm) {
        // Toque no ícone abre PatientActivity
        Intent openApp = new Intent(this, PatientActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_heart_heartbeat)
                .setContentTitle("EverNear — " + bpm + " bpm")
                .setContentText(status)
                .setContentIntent(pi)
                .setOngoing(true)      // não pode ser dispensada pelo usuário
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private Notification buildAlertNotification(String titulo, String texto) {
        Intent openApp = new Intent(this, PatientActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, openApp,
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

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
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashSet;
import java.util.Set;

/**
 * Serviço em primeiro plano no dispositivo do CUIDADOR.
 *
 * Mantém um Firestore snapshot listener ativo mesmo com o app fechado.
 * Quando um novo alerta chega (paciente com BPM anormal ou emergência manual),
 * exibe uma notificação de alta prioridade no celular do cuidador.
 *
 * Ciclo de vida:
 *  - Iniciado pela CaregiverActivity no onCreate()
 *  - Reiniciado pelo BootReceiver se o cuidador também reiniciar o celular
 *  - START_STICKY → Android reinicia se for encerrado pelo sistema
 */
public class CaregiverAlertService extends Service {

    private static final String TAG = "CaregiverAlertService";

    private static final String CHANNEL_ID_FG   = "evernear_cuidador_monitor";
    private static final String CHANNEL_ID_ALRT = "evernear_cuidador_alerta";
    private static final int    NOTIF_ID_FG     = 2001;
    private static final int    NOTIF_ID_ALERT  = 2002;

    private static CaregiverAlertService instance;

    private NotificationManager notifManager;
    private ListenerRegistration alertasListener;
    private String uidCuidador;

    // IDs de alertas já notificados (evita repetição se o listener re-entrar)
    private final Set<String> alertasNotificados = new HashSet<>();
    private long serviceStartTime;

    // ==================== API estática ====================

    public static boolean isRunning() {
        return instance != null;
    }

    // ==================== Ciclo de vida ====================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        serviceStartTime = System.currentTimeMillis();
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        criarCanais();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            Log.w(TAG, "Sem usuário autenticado — serviço não iniciado");
            stopSelf();
            return START_NOT_STICKY;
        }

        uidCuidador = auth.getUid();

        // Notificação persistente obrigatória (foreground service)
        startForeground(NOTIF_ID_FG, buildFgNotification());

        // Inicia listener de alertas no Firestore
        ouvirAlertas();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (alertasListener != null) alertasListener.remove();
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== Listener de alertas ====================

    private void ouvirAlertas() {
        if (uidCuidador == null) return;

        alertasListener = FirebaseFirestore.getInstance()
                .collection("alerts")
                .whereEqualTo("cuidadorId", uidCuidador)
                .whereEqualTo("acknowledged", false)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        Log.w(TAG, "Erro no listener de alertas: " + e.getMessage());
                        return;
                    }
                    if (snapshots == null) return;

                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() != DocumentChange.Type.ADDED) continue;

                        String alertaId = dc.getDocument().getId();
                        if (alertasNotificados.contains(alertaId)) continue;

                        // Ignora alertas criados antes do serviço iniciar (5s de margem)
                        com.google.firebase.Timestamp ts =
                                dc.getDocument().getTimestamp("timestamp");
                        if (ts != null && ts.toDate().getTime() < serviceStartTime - 5_000) {
                            alertasNotificados.add(alertaId); // marca para não repetir
                            continue;
                        }

                        alertasNotificados.add(alertaId);

                        String paciente = dc.getDocument().getString("pacienteNome");
                        Long bpm = dc.getDocument().getLong("bpm");
                        String tipo = dc.getDocument().getString("tipo");

                        exibirNotificacaoAlerta(alertaId, paciente,
                                bpm != null ? bpm.intValue() : 0, tipo);
                    }
                });
    }

    // ==================== Notificações ====================

    private void criarCanais() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal do foreground (baixa prioridade — só para manter o serviço vivo)
            NotificationChannel fg = new NotificationChannel(
                    CHANNEL_ID_FG,
                    "EverNear — Monitor Cuidador",
                    NotificationManager.IMPORTANCE_LOW);
            fg.setDescription("Serviço de recebimento de alertas do paciente");
            fg.setShowBadge(false);
            notifManager.createNotificationChannel(fg);

            // Canal de alertas (alta prioridade — faz barulho e aparece na tela)
            NotificationChannel alrt = new NotificationChannel(
                    CHANNEL_ID_ALRT,
                    "Alertas do Paciente",
                    NotificationManager.IMPORTANCE_HIGH);
            alrt.setDescription("Notificações de emergência e batimentos fora do normal");
            alrt.enableVibration(true);
            alrt.enableLights(true);
            notifManager.createNotificationChannel(alrt);
        }
    }

    private Notification buildFgNotification() {
        Intent openApp = new Intent(this, CaregiverActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID_FG)
                .setSmallIcon(R.drawable.ic_heart_heartbeat)
                .setContentTitle("EverNear")
                .setContentText("Monitorando alertas do paciente")
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void exibirNotificacaoAlerta(String alertaId, String paciente, int bpm, String tipo) {
        String titulo;
        String emoji;
        if ("MANUAL".equals(tipo)) {
            titulo = "EMERGÊNCIA acionada!";
            emoji = "🚨";
        } else if ("HIGH".equals(tipo)) {
            titulo = "Frequência cardíaca ALTA";
            emoji = "❤";
        } else {
            titulo = "Frequência cardíaca BAIXA";
            emoji = "💙";
        }

        String nomePac = (paciente != null && !paciente.isEmpty()) ? paciente : "Paciente";
        String texto = nomePac + " — " + bpm + " bpm";

        // Tocar na notificação abre CaregiverActivity
        Intent openApp = new Intent(this, CaregiverActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, alertaId.hashCode(), openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID_ALRT)
                .setSmallIcon(R.drawable.ic_heart_heartbeat)
                .setContentTitle(emoji + " " + titulo)
                .setContentText(texto)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(nomePac + "\nBPM: " + bpm + "\nToque para abrir o app"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build();

        // Usa ID único por alerta para não sobrescrever alertas anteriores
        notifManager.notify(NOTIF_ID_ALERT + alertaId.hashCode(), notif);

        Log.d(TAG, "Notificação de alerta exibida: " + titulo + " | " + texto);
    }
}

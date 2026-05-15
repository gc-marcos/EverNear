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
import android.os.IBinder;
import android.os.SystemClock;
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
 * Serviço em primeiro plano no dispositivo do CUIDADOR (celular/tablet).
 *
 * ┌─ Por que funciona com o app fechado? ──────────────────────────────────────┐
 * │  1. Foreground Service: mantido vivo pelo Android (não encerrado pelo GC). │
 * │  2. Firestore snapshot listener: recebe alertas do paciente em tempo real, │
 * │     mesmo sem a CaregiverActivity aberta.                                   │
 * │  3. Notificação de alta prioridade: aparece na barra de status, vibra e    │
 * │     exibe painel completo (heads-up) mesmo com outra app em foco.           │
 * │  4. Full-screen Intent: em emergências (tipo MANUAL), a notificação abre   │
 * │     diretamente na tela de bloqueio — comportamento de alarme.             │
 * │  5. START_STICKY + onTaskRemoved + AlarmManager: reinicia o serviço se     │
 * │     o sistema ou o usuário encerrar o app.                                  │
 * └────────────────────────────────────────────────────────────────────────────┘
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

    // Evita repetir notificação para o mesmo alerta (re-entradas do listener)
    private final Set<String> alertasNotificados = new HashSet<>();
    private long serviceStartTime;

    // ==================== API estática ====================

    public static boolean isRunning() { return instance != null; }

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

        // Notificação persistente obrigatória para foreground service
        startForeground(NOTIF_ID_FG, buildFgNotification());

        // Inicia listener do Firestore
        ouvirAlertas();

        Log.d(TAG, "CaregiverAlertService iniciado — ouvindo alertas para: " + uidCuidador);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (alertasListener != null) alertasListener.remove();
        instance = null;
        super.onDestroy();
    }

    /**
     * Reagenda o serviço se o usuário remover o app da lista de recentes.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "App removido da lista de recentes — agendando reinício em 5s");
        PendingIntent reiniciar = PendingIntent.getService(
                this, 2,
                new Intent(this, CaregiverAlertService.class),
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5_000L, reiniciar);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ==================== Listener de alertas do Firestore ====================

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

                        // Evita duplicata na mesma sessão do serviço
                        if (alertasNotificados.contains(alertaId)) continue;

                        // Ignora alertas criados antes do serviço iniciar (5s de margem)
                        com.google.firebase.Timestamp ts =
                                dc.getDocument().getTimestamp("timestamp");
                        if (ts != null && ts.toDate().getTime() < serviceStartTime - 5_000L) {
                            alertasNotificados.add(alertaId); // marca para não repetir
                            continue;
                        }

                        alertasNotificados.add(alertaId);

                        String paciente = dc.getDocument().getString("pacienteNome");
                        Long   bpm      = dc.getDocument().getLong("bpm");
                        String tipo     = dc.getDocument().getString("tipo");

                        exibirNotificacaoAlerta(alertaId, paciente,
                                bpm != null ? bpm.intValue() : 0, tipo);
                    }
                });
    }

    // ==================== Notificações ====================

    private void criarCanais() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal de foreground: baixa prioridade (só para manter o serviço ativo)
            NotificationChannel fg = new NotificationChannel(
                    CHANNEL_ID_FG,
                    "EverNear — Monitor Cuidador",
                    NotificationManager.IMPORTANCE_LOW);
            fg.setDescription("Serviço de recebimento de alertas do paciente");
            fg.setShowBadge(false);
            notifManager.createNotificationChannel(fg);

            // Canal de alertas: máxima prioridade (acorda tela, vibra, toca som)
            NotificationChannel alrt = new NotificationChannel(
                    CHANNEL_ID_ALRT,
                    "Alertas do Paciente",
                    NotificationManager.IMPORTANCE_HIGH);
            alrt.setDescription("Notificações de emergência e batimentos fora do normal");
            alrt.enableVibration(true);
            alrt.enableLights(true);
            alrt.setLightColor(0xFFFF0000); // LED vermelho se disponível
            alrt.setBypassDnd(true);        // ignora modo "Não perturbe" para emergências
            notifManager.createNotificationChannel(alrt);
        }
    }

    private Notification buildFgNotification() {
        Intent openApp = new Intent(this, CaregiverActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
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

    /**
     * Exibe notificação de alta prioridade quando um alerta do Firestore chega.
     *
     * Para emergências (tipo MANUAL):
     *  - Usa setFullScreenIntent() → aparece na tela de bloqueio como um alarme,
     *    mesmo que o celular esteja com tela apagada.
     *
     * Para anomalias de BPM (HIGH/LOW):
     *  - Heads-up notification com vibração e som.
     *  - BigTextStyle com instruções.
     */
    private void exibirNotificacaoAlerta(String alertaId, String paciente, int bpm, String tipo) {
        String titulo;
        String emoji;
        boolean isEmergencia = "MANUAL".equals(tipo);

        if (isEmergencia) {
            titulo = "EMERGÊNCIA acionada!";
            emoji  = "🚨";
        } else if ("HIGH".equals(tipo)) {
            titulo = "Frequência cardíaca ALTA";
            emoji  = "❤";
        } else {
            titulo = "Frequência cardíaca BAIXA";
            emoji  = "💙";
        }

        String nomePac = (paciente != null && !paciente.isEmpty()) ? paciente : "Paciente";
        String texto   = nomePac + " — " + bpm + " bpm";

        // Intent para abrir a CaregiverActivity ao tocar na notificação
        Intent openApp = new Intent(this, CaregiverActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piAbrir = PendingIntent.getActivity(
                this, alertaId.hashCode(), openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_ALRT)
                .setSmallIcon(R.drawable.ic_heart_heartbeat)
                .setContentTitle(emoji + " " + titulo)
                .setContentText(texto)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(nomePac
                                + "\nBPM: " + bpm
                                + "\n\nToque para abrir o app e confirmar o alerta."))
                .setContentIntent(piAbrir)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX) // MAX para emergências
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        // Full-screen Intent: para emergências, exibe sobre a tela de bloqueio
        // (comportamento de alarme — requer USE_FULL_SCREEN_INTENT no manifest)
        if (isEmergencia) {
            builder.setFullScreenIntent(piAbrir, true);
            builder.setOngoing(true); // não pode ser dispensada sem ação
        }

        // ID único por alerta: múltiplos alertas não se sobrepõem
        notifManager.notify(NOTIF_ID_ALERT + alertaId.hashCode(), builder.build());

        Log.d(TAG, "Notificação exibida: " + titulo + " | " + texto);
    }
}

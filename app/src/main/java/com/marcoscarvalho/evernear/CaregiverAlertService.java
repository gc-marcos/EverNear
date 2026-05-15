package com.marcoscarvalho.evernear;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
 * Mantém um Firestore snapshot listener ativo mesmo com o app completamente fechado.
 * Quando um alerta do paciente chega, exibe notificação de alta prioridade.
 *
 * ── Rastreamento de alertas já notificados ───────────────────────────────────
 * Os IDs dos alertas notificados são persistidos em SharedPreferences (disco),
 * não em memória. Isso garante que:
 *  1. Alertas criados enquanto o serviço estava morto NÃO são ignorados quando
 *     o serviço reinicia (o filtro por timestamp causava exatamente esse bug).
 *  2. Alertas antigos já confirmados não se repetem em reinicializações.
 *  3. Alertas gerados offline pelo relógio são entregues quando a rede volta.
 *
 * ── Reinício após encerramento ───────────────────────────────────────────────
 *  • START_STICKY: Android reinicia automaticamente
 *  • onTaskRemoved + setExactAndAllowWhileIdle: reinicia em 5 s mesmo em Doze
 *  • BootReceiver: reinicia quando o dispositivo é ligado
 */
public class CaregiverAlertService extends Service {

    private static final String TAG = "CaregiverAlertService";

    private static final String CHANNEL_ID_FG   = "evernear_cuidador_monitor";
    private static final String CHANNEL_ID_ALRT = "evernear_cuidador_alerta";
    private static final int    NOTIF_ID_FG     = 2001;
    private static final int    NOTIF_ID_ALERT  = 2002;

    // SharedPreferences: persiste IDs de alertas já notificados entre reinicializações
    private static final String PREFS_NAME       = "evernear_alertas_notificados";
    private static final String PREFS_KEY_IDS    = "ids_notificados";
    private static final int    MAX_IDS_SALVOS   = 200; // evita crescimento ilimitado

    private static CaregiverAlertService instance;

    private NotificationManager notifManager;
    private ListenerRegistration alertasListener;
    private String uidCuidador;
    private SharedPreferences prefs;

    // Conjunto de IDs já notificados — carregado do disco, persistido a cada novo alerta
    private Set<String> alertasNotificados;

    // ==================== API estática ====================

    public static boolean isRunning() { return instance != null; }

    // ==================== Ciclo de vida ====================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        criarCanais();

        // Carrega IDs de alertas já notificados do disco (persiste entre reinicializações)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> salvo = prefs.getStringSet(PREFS_KEY_IDS, new HashSet<>());
        alertasNotificados = new HashSet<>(salvo); // cópia mutável
        Log.d(TAG, "IDs já notificados carregados: " + alertasNotificados.size());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            Log.w(TAG, "Sem usuário autenticado — serviço encerrado");
            stopSelf();
            return START_NOT_STICKY;
        }

        uidCuidador = auth.getUid();
        startForeground(NOTIF_ID_FG, buildFgNotification());
        ouvirAlertas();

        Log.d(TAG, "Serviço iniciado — ouvindo alertas para cuidador: " + uidCuidador);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (alertasListener != null) alertasListener.remove();
        instance = null;
        super.onDestroy();
    }

    /**
     * Quando o usuário fecha o app da lista de recentes, agenda reinício
     * usando setExactAndAllowWhileIdle para funcionar mesmo em modo Doze.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "App removido da lista de recentes — agendando reinício em 5 s");

        Intent reiniciarIntent = new Intent(this, CaregiverAlertService.class);
        PendingIntent pi = PendingIntent.getService(
                this, 2, reiniciarIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            // setExactAndAllowWhileIdle: dispara mesmo durante Doze mode
            am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5_000L,
                    pi);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

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
                        // Só processa documentos novos (ADDED), não modificações/remoções
                        if (dc.getType() != DocumentChange.Type.ADDED) continue;

                        String alertaId = dc.getDocument().getId();

                        // Pula se já foi notificado nesta sessão OU em sessões anteriores
                        // (rastreado via SharedPreferences — persiste entre reinicializações)
                        if (alertasNotificados.contains(alertaId)) {
                            Log.d(TAG, "Alerta " + alertaId + " já notificado — ignorando");
                            continue;
                        }

                        // Marca como notificado ANTES de exibir (evita duplicata em re-entradas)
                        marcarComoNotificado(alertaId);

                        String paciente = dc.getDocument().getString("pacienteNome");
                        Long   bpm      = dc.getDocument().getLong("bpm");
                        String tipo     = dc.getDocument().getString("tipo");

                        exibirNotificacaoAlerta(alertaId, paciente,
                                bpm != null ? bpm.intValue() : 0, tipo);
                    }
                });
    }

    /**
     * Persiste o ID no conjunto em memória e em disco.
     * Limita o tamanho do conjunto para evitar crescimento ilimitado.
     */
    private void marcarComoNotificado(String alertaId) {
        alertasNotificados.add(alertaId);

        // Se ultrapassou o limite, descarta os mais antigos (limpa tudo e reinicia)
        if (alertasNotificados.size() > MAX_IDS_SALVOS) {
            alertasNotificados.clear();
            alertasNotificados.add(alertaId);
            Log.d(TAG, "Cache de IDs limpo (excedeu " + MAX_IDS_SALVOS + " entradas)");
        }

        prefs.edit().putStringSet(PREFS_KEY_IDS, alertasNotificados).apply();
    }

    // ==================== Notificações ====================

    private void criarCanais() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel fg = new NotificationChannel(
                    CHANNEL_ID_FG,
                    "EverNear — Monitor Cuidador",
                    NotificationManager.IMPORTANCE_LOW);
            fg.setDescription("Serviço de recebimento de alertas do paciente");
            fg.setShowBadge(false);
            notifManager.createNotificationChannel(fg);

            NotificationChannel alrt = new NotificationChannel(
                    CHANNEL_ID_ALRT,
                    "Alertas do Paciente",
                    NotificationManager.IMPORTANCE_HIGH);
            alrt.setDescription("Notificações de emergência e batimentos fora do normal");
            alrt.enableVibration(true);
            alrt.enableLights(true);
            alrt.setLightColor(0xFFFF0000);
            alrt.setBypassDnd(true);
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
                                + "\n\nToque para abrir o app e confirmar."))
                .setContentIntent(piAbrir)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        // Emergências: full-screen intent (aparece sobre a tela de bloqueio como alarme)
        if (isEmergencia) {
            builder.setFullScreenIntent(piAbrir, true);
            builder.setOngoing(true);
        }

        notifManager.notify(NOTIF_ID_ALERT + alertaId.hashCode(), builder.build());
        Log.d(TAG, "Notificação exibida: " + titulo + " | " + texto);
    }
}

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
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Serviço em primeiro plano no dispositivo do CUIDADOR (celular/tablet).
 *
 * Mantém um Firestore SnapshotListener ativo mesmo com o app completamente fechado.
 * Quando um alerta do paciente chega, exibe notificação de alta prioridade.
 *
 * ┌─ Estratégia de reinício ──────────────────────────────────────────────────┐
 * │  • START_STICKY: Android reinicia automaticamente após encerramento.        │
 * │  • onTaskRemoved + setExactAndAllowWhileIdle: reinicia em 5 s em Doze.    │
 * │  • BootReceiver: reinicia quando o dispositivo é ligado.                   │
 * │  • SharedPreferences: BootReceiver não depende de FirebaseAuth.            │
 * └───────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Cache de alertas notificados ────────────────────────────────────────────┐
 * │  IDs persistidos em SharedPreferences como lista ordenada ("|"-delimitada).│
 * │  Ao atingir MAX_IDS_SALVOS, os mais antigos são removidos (não limpa tudo).│
 * │  Isso garante que alertas recentes não se repitam e que alertas antigos    │
 * │  não ocupem espaço indefinidamente.                                        │
 * └───────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Reconexão automática do Firestore ───────────────────────────────────────┐
 * │  Erros no SnapshotListener são detectados, o listener inválido é removido  │
 * │  e um novo é criado após RECONEXAO_DELAY_MS (15 s).                        │
 * └───────────────────────────────────────────────────────────────────────────┘
 */
public class CaregiverAlertService extends Service {

    private static final String TAG = "CaregiverAlertService";

    // ── Canais de notificação ─────────────────────────────────────────────────
    private static final String CHANNEL_ID_FG   = "evernear_cuidador_monitor";
    private static final String CHANNEL_ID_ALRT = "evernear_cuidador_alerta";
    private static final int    NOTIF_ID_FG     = 2001;
    private static final int    NOTIF_ID_ALERT  = 2002;

    // ── SharedPreferences ─────────────────────────────────────────────────────
    private static final String PREFS_NAME              = "evernear_alertas_notificados";
    /** Lista de IDs já notificados, em ordem de inserção, separados por "|". */
    private static final String PREFS_KEY_IDS           = "ids_notificados_list";
    /** UID do cuidador salvo para uso pelo BootReceiver sem depender de FirebaseAuth. */
    private static final String PREFS_KEY_UID_CUIDADOR  = "uid_cuidador";
    /** Indica se o serviço estava ativo antes de ser encerrado. */
    private static final String PREFS_KEY_SERVICO_ATIVO = "servico_ativo";

    // ── Cache ─────────────────────────────────────────────────────────────────
    /** Máximo de IDs mantidos no cache. */
    private static final int MAX_IDS_SALVOS  = 200;
    /** Após trim, mantém apenas os N IDs mais recentes. */
    private static final int CACHE_TRIM_KEEP = 100;

    // ── Reconexão ─────────────────────────────────────────────────────────────
    /** Tempo de espera antes de tentar reconectar o SnapshotListener após erro. */
    private static final long RECONEXAO_DELAY_MS = 15_000L;

    // ── Detecção de relógio morto ──────────────────────────────────────────────
    /**
     * Intervalo de verificação da presença do relógio.
     * A cada 5 min o CaregiverAlertService verifica se o paciente enviou BPM
     * recentemente. Se não enviou, solicita acordar o relógio via Firestore+FCM.
     */
    private static final long VERIFICACAO_RELOGIO_INTERVAL_MS = 5 * 60_000L;
    /**
     * Tempo máximo sem leitura de BPM antes de considerar o relógio morto.
     * 6 min: maior que o intervalo de verificação (5 min) para evitar falsos positivos,
     * mas menor que o tempo em que uma anomalia poderia passar sem detecção.
     */
    private static final long RELOGIO_MORTO_THRESHOLD_MS = 6 * 60_000L;

    // ── Estado estático ───────────────────────────────────────────────────────
    /** Referência estática para verificação rápida de disponibilidade. */
    private static CaregiverAlertService instance;

    // ── Estado da instância ───────────────────────────────────────────────────
    private NotificationManager   notifManager;
    private ListenerRegistration  alertasListener;
    private String                uidCuidador;
    private SharedPreferences     prefs;
    /** Cache em memória de IDs já notificados — mantém ordem de inserção. */
    private final LinkedHashSet<String> alertasNotificados = new LinkedHashSet<>();
    /** Impede múltiplas tentativas de reconexão simultâneas. */
    private boolean reconectando = false;
    private final Handler reconexaoHandler  = new Handler(Looper.getMainLooper());

    // ── Detecção de relógio morto — estado ────────────────────────────────────
    /**
     * Lista de UIDs de pacientes vinculados a este cuidador.
     * Preenchida pelo listener do documento do cuidador no Firestore.
     * Usada pela verificação periódica de presença do relógio.
     */
    private List<String>        pacientesVinculados  = new ArrayList<>();
    /** Listener no documento do cuidador para manter pacientesVinculados atualizado. */
    private ListenerRegistration cuidadorDadosListener;
    /** Handler para agendar a verificação periódica de presença do relógio. */
    private final Handler verificacaoHandler = new Handler(Looper.getMainLooper());

    // ==================== API estática ====================

    /**
     * Verifica se o serviço está ativo checando tanto a instância estática
     * quanto a flag persistida em SharedPreferences.
     *
     * Usar SharedPreferences (além da instância) permite detectar corretamente
     * o estado após BootReceiver — em que a instância ainda não existiu nesta
     * sessão do processo mas o serviço havia sido registrado como ativo.
     *
     * @param context qualquer Context válido
     */
    public static boolean isRunning(Context context) {
        if (instance != null) return true;
        SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return p.getBoolean(PREFS_KEY_SERVICO_ATIVO, false);
    }

    /**
     * Retorna o UID do cuidador salvo em SharedPreferences.
     * Usado pelo BootReceiver para iniciar o serviço sem depender de FirebaseAuth.
     */
    public static String getUidSalvo(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return p.getString(PREFS_KEY_UID_CUIDADOR, null);
    }

    // ==================== Ciclo de vida ====================

    @Override
    public void onCreate() {
        super.onCreate();
        instance     = this;
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        prefs        = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        criarCanais();
        carregarCacheAlertas();
        Log.d(TAG, "Serviço criado");
    }

    /**
     * Tenta determinar o UID do cuidador em dois passos:
     *  1. FirebaseAuth.getCurrentUser() — disponível na maioria dos casos
     *  2. SharedPreferences — fallback para BootReceiver (processo recém-iniciado,
     *     token Firebase pode ainda não estar carregado)
     *
     * Garante que apenas um SnapshotListener exista por vez ao chamar ouvirAlertas().
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand — determinando UID do cuidador");

        // 1ª tentativa: FirebaseAuth
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            uidCuidador = FirebaseAuth.getInstance().getUid();
        }

        // 2ª tentativa: SharedPreferences (suporte ao BootReceiver)
        if (uidCuidador == null || uidCuidador.isEmpty()) {
            uidCuidador = prefs.getString(PREFS_KEY_UID_CUIDADOR, null);
        }

        if (uidCuidador == null || uidCuidador.isEmpty()) {
            Log.w(TAG, "UID do cuidador não disponível — serviço encerrado");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Persiste UID e estado para suportar BootReceiver e isRunning(context)
        persistirEstado(true);

        startForeground(NOTIF_ID_FG, buildFgNotification());
        ouvirAlertas();               // garante listener único
        monitorarPacientesVinculados(); // detecta relógio morto → acorda via FCM

        Log.i(TAG, "Serviço ativo — ouvindo alertas para cuidador: " + uidCuidador);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Serviço destruído");
        reconexaoHandler.removeCallbacksAndMessages(null);
        verificacaoHandler.removeCallbacksAndMessages(null);
        removerListener();
        if (cuidadorDadosListener != null) {
            cuidadorDadosListener.remove();
            cuidadorDadosListener = null;
        }
        persistirEstado(false);
        instance = null;
        super.onDestroy();
    }

    /**
     * Reinício de segurança após remoção da lista de recentes.
     * setExactAndAllowWhileIdle garante disparo mesmo em Doze mode.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "App removido da lista de recentes — agendando reinício em 5 s");

        PendingIntent pi = PendingIntent.getService(
                this, 2, new Intent(this, CaregiverAlertService.class),
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
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

    // ==================== Detecção de relógio morto ====================

    /**
     * Monitora os pacientes vinculados a este cuidador e detecta quando o relógio
     * está morto (sem enviar BPM por mais de RELOGIO_MORTO_THRESHOLD_MS).
     *
     * Por que o cuidador faz isso e não um Cloud Scheduler?
     * O cuidador já conhece o UID do paciente (via pacientesVinculados, que foi
     * populado pelo codigoVinculo na vinculação). Um Scheduler acordaria TODOS os
     * relógios de 5 em 5 min — desperdício de bateria. O cuidador só solicita
     * acordar quando detectou especificamente que aquele relógio está sem resposta.
     *
     * Fluxo:
     *  1. Snapshot listener em users/{uidCuidador} mantém pacientesVinculados atualizado.
     *  2. A cada VERIFICACAO_RELOGIO_INTERVAL_MS (5 min), lê ultimoBpmTimestamp de cada
     *     paciente com uma única chamada .get() (não snapshot — evita listeners extras).
     *  3. Se o timestamp for mais antigo que RELOGIO_MORTO_THRESHOLD_MS (6 min):
     *     chama FirebaseHelper.solicitarWakeUpPaciente() que escreve "solicitarWakeUp"
     *     no documento do paciente.
     *  4. A Cloud Function "acordarPaciente" detecta o campo e envia FCM ao relógio.
     */
    private void monitorarPacientesVinculados() {
        if (uidCuidador == null) return;

        // Remove listener anterior para garantir unicidade
        if (cuidadorDadosListener != null) {
            cuidadorDadosListener.remove();
        }

        Log.d(TAG, "Iniciando monitor de pacientes vinculados para cuidador: " + uidCuidador);

        cuidadorDadosListener = FirebaseFirestore.getInstance()
                .collection("users").document(uidCuidador)
                .addSnapshotListener((doc, e) -> {
                    if (e != null) {
                        Log.w(TAG, "Erro ao ouvir dados do cuidador: " + e.getMessage());
                        return;
                    }
                    if (doc == null || !doc.exists()) return;

                    @SuppressWarnings("unchecked")
                    List<String> lista = (List<String>) doc.get("pacientesVinculados");
                    pacientesVinculados = (lista != null) ? new ArrayList<>(lista) : new ArrayList<>();

                    Log.d(TAG, "Pacientes vinculados atualizados: " + pacientesVinculados.size());

                    // Agenda (ou reag.) a verificação periódica toda vez que a lista muda
                    verificacaoHandler.removeCallbacksAndMessages(null);
                    agendarVerificacaoPresenca();
                });
    }

    /**
     * Agenda a verificação periódica de presença do relógio.
     * Executa imediatamente uma primeira verificação e reagenda a cada
     * VERIFICACAO_RELOGIO_INTERVAL_MS (5 min) enquanto o serviço estiver ativo.
     */
    private void agendarVerificacaoPresenca() {
        verificacaoHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                verificarPresencaRelogio();
                verificacaoHandler.postDelayed(this, VERIFICACAO_RELOGIO_INTERVAL_MS);
            }
        }, VERIFICACAO_RELOGIO_INTERVAL_MS);

        Log.d(TAG, "Verificação de presença agendada a cada "
                + VERIFICACAO_RELOGIO_INTERVAL_MS / 60_000 + " min");
    }

    /**
     * Para cada paciente vinculado, lê seu ultimoBpmTimestamp e verifica se o relógio
     * está enviando sinais. Se estiver morto, solicita wake-up via Firestore.
     *
     * Usa .get() (leitura única) em vez de snapshot listener para cada paciente,
     * evitando multiplicar conexões abertas quando o cuidador tem vários pacientes.
     */
    private void verificarPresencaRelogio() {
        if (pacientesVinculados.isEmpty()) {
            Log.d(TAG, "Verificação de presença: sem pacientes vinculados");
            return;
        }

        Log.d(TAG, "Verificando presença do relógio para "
                + pacientesVinculados.size() + " paciente(s)");

        for (String uidPaciente : pacientesVinculados) {
            if (uidPaciente == null || uidPaciente.isEmpty()) continue;

            FirebaseFirestore.getInstance()
                    .collection("users").document(uidPaciente)
                    .get()
                    .addOnSuccessListener(doc -> {
                        if (doc == null || !doc.exists()) return;

                        String nome      = doc.getString("nome");
                        String nomeLog   = (nome != null) ? nome : uidPaciente;
                        Date   timestamp = doc.getDate("ultimoBpmTimestamp");

                        if (timestamp == null) {
                            // Relógio nunca enviou BPM — pode ser primeiro uso; não acorda ainda
                            Log.d(TAG, "Paciente " + nomeLog + ": sem ultimoBpmTimestamp — ignorando");
                            return;
                        }

                        long silencio = System.currentTimeMillis() - timestamp.getTime();

                        if (silencio > RELOGIO_MORTO_THRESHOLD_MS) {
                            Log.w(TAG, "Relógio morto detectado — paciente=" + nomeLog
                                    + " silêncio=" + (silencio / 1000) + "s"
                                    + " → solicitando wake-up via Firestore+FCM");
                            FirebaseHelper.solicitarWakeUpPaciente(uidPaciente);
                        } else {
                            Log.d(TAG, "Relógio ativo — paciente=" + nomeLog
                                    + " último BPM há " + (silencio / 1000) + "s");
                        }
                    })
                    .addOnFailureListener(e ->
                            Log.w(TAG, "Falha ao ler dados do paciente "
                                    + uidPaciente + ": " + e.getMessage()));
        }
    }

    // ==================== Listener de alertas ====================

    /**
     * Registra o SnapshotListener para a coleção de alertas do cuidador.
     *
     * Garante listener único: remove qualquer listener anterior antes de criar um novo.
     * Isso previne a duplicação de listeners após reinicializações por START_STICKY.
     */
    private void ouvirAlertas() {
        if (uidCuidador == null) {
            Log.w(TAG, "ouvirAlertas: uidCuidador nulo — abortando");
            return;
        }

        // Garante que não existe listener duplicado
        removerListener();
        reconectando = false;

        Log.d(TAG, "Registrando SnapshotListener para cuidador: " + uidCuidador);

        alertasListener = FirebaseFirestore.getInstance()
                .collection("alerts")
                .whereEqualTo("cuidadorId", uidCuidador)
                .whereEqualTo("acknowledged", false)
                .addSnapshotListener((snapshots, erro) -> {
                    if (erro != null) {
                        Log.e(TAG, "Erro no SnapshotListener: " + erro.getMessage());
                        // Remove listener inválido e agenda reconexão
                        removerListener();
                        agendarReconexao();
                        return;
                    }
                    if (snapshots == null) {
                        Log.w(TAG, "SnapshotListener: snapshot nulo recebido");
                        return;
                    }

                    Log.d(TAG, "Snapshot recebido: "
                            + snapshots.getDocumentChanges().size() + " mudança(s)");

                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        // Processa apenas documentos novos — não modificações ou remoções
                        if (dc.getType() != DocumentChange.Type.ADDED) continue;

                        String alertaId = dc.getDocument().getId();
                        if (alertasNotificados.contains(alertaId)) {
                            Log.d(TAG, "Alerta " + alertaId + " já notificado — ignorando");
                            continue;
                        }

                        // Marca ANTES de processar: evita notificação duplicada em re-entradas
                        marcarComoNotificado(alertaId);
                        processarAlerta(dc);
                    }
                });
    }

    /**
     * Remove o listener ativo de forma segura (sem lançar exceções).
     */
    private void removerListener() {
        if (alertasListener != null) {
            alertasListener.remove();
            alertasListener = null;
            Log.d(TAG, "SnapshotListener removido");
        }
    }

    /**
     * Agenda uma reconexão ao Firestore após RECONEXAO_DELAY_MS (15 s).
     * A flag {@code reconectando} previne múltiplas tentativas simultâneas.
     */
    private void agendarReconexao() {
        if (reconectando) {
            Log.d(TAG, "Reconexão já agendada — aguardando");
            return;
        }
        reconectando = true;
        Log.w(TAG, "Reconexão agendada em " + (RECONEXAO_DELAY_MS / 1000) + " s");

        reconexaoHandler.postDelayed(() -> {
            if (uidCuidador != null) {
                Log.i(TAG, "Tentando reconectar SnapshotListener...");
                ouvirAlertas();
            } else {
                reconectando = false;
            }
        }, RECONEXAO_DELAY_MS);
    }

    // ==================== Processamento de alertas ====================

    /**
     * Extrai os campos do documento de alerta e exibe a notificação.
     *
     * Valida todos os campos antes de usá-los para evitar NullPointerException.
     * Se bpmMin/bpmMax não estiverem no documento, busca no perfil do paciente.
     *
     * @param dc mudança de documento recebida do SnapshotListener
     */
    private void processarAlerta(DocumentChange dc) {
        String alertaId     = dc.getDocument().getId();
        String pacienteId   = dc.getDocument().getString("pacienteId");
        String pacienteNome = dc.getDocument().getString("pacienteNome");
        String tipo         = dc.getDocument().getString("tipo");
        Long   bpmLong      = dc.getDocument().getLong("bpm");
        Long   bpmMinLong   = dc.getDocument().getLong("bpmMin");
        Long   bpmMaxLong   = dc.getDocument().getLong("bpmMax");

        // Valores seguros com fallback para evitar NPE
        int    bpm         = bpmLong    != null ? bpmLong.intValue()    : 0;
        String nomeSeguro  = (pacienteNome != null && !pacienteNome.isEmpty())
                ? pacienteNome : "Paciente";
        String tipoSeguro  = (tipo != null && !tipo.isEmpty()) ? tipo : "UNKNOWN";

        Log.i(TAG, "Novo alerta — id=" + alertaId
                + " paciente=" + nomeSeguro
                + " bpm=" + bpm
                + " tipo=" + tipoSeguro);

        if (bpmMinLong != null && bpmMaxLong != null) {
            // Limites disponíveis no documento — exibe notificação imediatamente
            exibirNotificacaoAlerta(alertaId, pacienteId, nomeSeguro,
                    bpm, tipoSeguro, bpmMinLong.intValue(), bpmMaxLong.intValue());
        } else {
            // Tenta buscar os limites no perfil do paciente
            buscarLimitesENotificar(alertaId, pacienteId, nomeSeguro, bpm, tipoSeguro);
        }
    }

    /**
     * Busca os limites de BPM do perfil do paciente no Firestore.
     * Se não encontrar (ou ocorrer erro), exibe a notificação sem os limites.
     */
    private void buscarLimitesENotificar(String alertaId, String pacienteId,
                                         String pacienteNome, int bpm, String tipo) {
        if (pacienteId == null || pacienteId.isEmpty()) {
            Log.w(TAG, "pacienteId nulo — exibindo notificação sem limites");
            exibirNotificacaoAlerta(alertaId, pacienteId, pacienteNome, bpm, tipo, -1, -1);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users").document(pacienteId)
                .get()
                .addOnSuccessListener(doc -> {
                    int bpmMin = -1;
                    int bpmMax = -1;
                    if (doc.exists()) {
                        Long minL = doc.getLong("bpmMin");
                        Long maxL = doc.getLong("bpmMax");
                        if (minL != null) bpmMin = minL.intValue();
                        if (maxL != null) bpmMax = maxL.intValue();
                    }
                    exibirNotificacaoAlerta(alertaId, pacienteId, pacienteNome, bpm, tipo, bpmMin, bpmMax);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Falha ao buscar limites do paciente: " + e.getMessage());
                    exibirNotificacaoAlerta(alertaId, pacienteId, pacienteNome, bpm, tipo, -1, -1);
                });
    }

    // ==================== Cache de alertas ====================

    /**
     * Carrega do disco o conjunto ordenado de IDs já notificados.
     * Usa String delimitada por "|" para preservar a ordem de inserção,
     * o que permite remover os mais antigos ao fazer trim do cache.
     */
    private void carregarCacheAlertas() {
        String raw = prefs.getString(PREFS_KEY_IDS, "");
        if (!raw.isEmpty()) {
            for (String id : raw.split("\\|")) {
                if (!id.isEmpty()) alertasNotificados.add(id);
            }
        }
        Log.d(TAG, "Cache de alertas carregado: " + alertasNotificados.size() + " IDs");
    }

    /**
     * Registra um ID como notificado no cache em memória e em disco.
     *
     * Estratégia de trim: ao ultrapassar MAX_IDS_SALVOS (200), remove os
     * (size - CACHE_TRIM_KEEP) IDs mais antigos — os inseridos primeiro no
     * LinkedHashSet — mantendo apenas os CACHE_TRIM_KEEP (100) mais recentes.
     * Isso evita renotificar alertas recentes e descarta os históricos desnecessários.
     */
    private void marcarComoNotificado(String alertaId) {
        alertasNotificados.add(alertaId);

        if (alertasNotificados.size() > MAX_IDS_SALVOS) {
            int remover = alertasNotificados.size() - CACHE_TRIM_KEEP;
            Iterator<String> it = alertasNotificados.iterator();
            for (int i = 0; i < remover && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
            Log.d(TAG, "Cache trimmed: removidos " + remover
                    + " IDs antigos — restam " + alertasNotificados.size());
        }

        persistirCache();
    }

    /** Persiste o cache de IDs em disco de forma assíncrona. */
    private void persistirCache() {
        StringBuilder sb = new StringBuilder();
        for (String id : alertasNotificados) {
            if (sb.length() > 0) sb.append('|');
            sb.append(id);
        }
        prefs.edit().putString(PREFS_KEY_IDS, sb.toString()).apply();
    }

    // ==================== Estado persistido ====================

    /**
     * Persiste o estado do serviço e o UID do cuidador em SharedPreferences.
     *
     * Isso permite que:
     *  • {@link #isRunning(Context)} retorne o estado correto entre sessões do processo.
     *  • O BootReceiver inicie o serviço sem depender de FirebaseAuth.getCurrentUser().
     */
    private void persistirEstado(boolean ativo) {
        SharedPreferences.Editor ed = prefs.edit()
                .putBoolean(PREFS_KEY_SERVICO_ATIVO, ativo);
        if (ativo && uidCuidador != null) {
            ed.putString(PREFS_KEY_UID_CUIDADOR, uidCuidador);
        }
        ed.apply();
        Log.d(TAG, "Estado persistido: ativo=" + ativo + " uid=" + uidCuidador);
    }

    // ==================== Notificações ====================

    private void criarCanais() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal do foreground service: silencioso
            NotificationChannel fg = new NotificationChannel(
                    CHANNEL_ID_FG,
                    "EverNear — Monitor Cuidador",
                    NotificationManager.IMPORTANCE_LOW);
            fg.setDescription("Serviço de recebimento de alertas do paciente");
            fg.setShowBadge(false);
            notifManager.createNotificationChannel(fg);

            // Canal de alertas: máxima prioridade, som de alarme, vibração, LED
            Uri somAlarme = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (somAlarme == null) {
                somAlarme = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }

            AudioAttributes audioAttr = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            NotificationChannel alrt = new NotificationChannel(
                    CHANNEL_ID_ALRT,
                    "Alertas do Paciente",
                    NotificationManager.IMPORTANCE_HIGH);
            alrt.setDescription("Notificações de emergência e batimentos fora do normal");
            alrt.enableVibration(true);
            alrt.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500});
            alrt.enableLights(true);
            alrt.setLightColor(0xFFFF0000);
            alrt.setBypassDnd(true);
            alrt.setSound(somAlarme, audioAttr);
            alrt.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
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
     * Monta e exibe a notificação de alerta para o cuidador.
     *
     * Conteúdo exibido (BigTextStyle):
     *   Paciente: [nome]
     *   BPM Atual: [bpm]
     *   Tipo: [HIGH | LOW | MANUAL]
     *   Limite mínimo: [bpmMin]
     *   Limite máximo: [bpmMax]
     *
     * @param alertaId    ID do documento no Firestore
     * @param pacienteId  UID do paciente (passado no Intent para deep-link na CaregiverActivity)
     * @param pacienteNome Nome do paciente (já validado pelo chamador)
     * @param bpm         BPM no momento da anomalia
     * @param tipo        "HIGH", "LOW" ou "MANUAL"
     * @param bpmMin      Limite mínimo configurado; -1 se desconhecido
     * @param bpmMax      Limite máximo configurado; -1 se desconhecido
     */
    private void exibirNotificacaoAlerta(String alertaId, String pacienteId,
                                         String pacienteNome, int bpm,
                                         String tipo, int bpmMin, int bpmMax) {
        boolean isEmergencia = "MANUAL".equals(tipo);
        boolean isHigh       = "HIGH".equals(tipo);

        String titulo;
        String emoji;
        if (isEmergencia) {
            titulo = "EMERGÊNCIA acionada!";
            emoji  = "🚨";
        } else if (isHigh) {
            titulo = "Frequência cardíaca ALTA";
            emoji  = "❤";
        } else {
            titulo = "Frequência cardíaca BAIXA";
            emoji  = "💙";
        }

        // Linha resumida (aparece sem expandir a notificação)
        String resumo = pacienteNome + " — " + bpm + " bpm";

        // Corpo expandido com todos os detalhes clínicos
        String minStr = bpmMin > 0 ? String.valueOf(bpmMin) : "N/A";
        String maxStr = bpmMax > 0 ? String.valueOf(bpmMax) : "N/A";
        String detalhes = "Paciente: " + pacienteNome + "\n"
                + "BPM Atual: " + bpm + "\n"
                + "Tipo: " + tipo + "\n"
                + "Limite mínimo: " + minStr + "\n"
                + "Limite máximo: " + maxStr + "\n\n"
                + "Toque para abrir o app e confirmar.";

        // Intent com deep-link: abre CaregiverActivity diretamente na ficha do paciente
        Intent openApp = new Intent(this, CaregiverActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (pacienteId != null && !pacienteId.isEmpty()) {
            openApp.putExtra("pacienteId", pacienteId);
        }
        openApp.putExtra("alertaId", alertaId);

        PendingIntent piAbrir = PendingIntent.getActivity(
                this, alertaId.hashCode(), openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Uri somAlarme = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (somAlarme == null) {
            somAlarme = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_ALRT)
                .setSmallIcon(R.drawable.ic_heart_heartbeat)
                .setContentTitle(emoji + " " + titulo)
                .setContentText(resumo)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(detalhes))
                .setContentIntent(piAbrir)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(somAlarme)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_LIGHTS)
                // Full-screen intent: abre sobre a tela de bloqueio
                // isHighPriority=true para emergências (abre sem interação do usuário)
                .setFullScreenIntent(piAbrir, isEmergencia);

        if (isEmergencia) {
            builder.setOngoing(true); // não pode ser dispensada sem abrir o app
        }

        notifManager.notify(NOTIF_ID_ALERT + alertaId.hashCode(), builder.build());
        Log.i(TAG, "Notificação exibida: " + titulo
                + " | paciente=" + pacienteNome
                + " | bpm=" + bpm
                + " | min=" + minStr + " max=" + maxStr);
    }
}

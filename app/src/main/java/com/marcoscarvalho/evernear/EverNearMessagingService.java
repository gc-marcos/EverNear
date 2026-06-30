package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Receptor de mensagens FCM (Firebase Cloud Messaging) instalado no RELÓGIO (paciente).
 *
 * ┌─ Responsabilidade ─────────────────────────────────────────────────────────┐
 * │  1. Receber mensagens WAKE_UP enviadas pela Cloud Function "acordarPaciente"│
 * │     e iniciar o HeartRateService quando o processo estiver morto.           │
 * │  2. Salvar o token FCM no Firestore quando o SDK o renovar (onNewToken()),  │
 * │     garantindo que a Cloud Function sempre encontre o endereço atualizado.  │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Fluxo completo ────────────────────────────────────────────────────────────┐
 * │  CaregiverAlertService detecta relógio morto (BPM > 6 min atrás)            │
 * │    → escreve users/{uid}.solicitarWakeUp = serverTimestamp()                │
 * │    → Cloud Function "acordarPaciente" (trigger Firestore) acorda            │
 * │    → lê fcmToken do documento do paciente                                   │
 * │    → FCM data-only, priority:"high", data:{tipo:"WAKE_UP"} ao relógio       │
 * │    → onMessageReceived() é chamado mesmo com processo morto                 │
 * │    → startForegroundService(HeartRateService)                               │
 * │    → HeartRateService lê BPM e salva no Firestore                          │
 * │    → Se anomalia: alerta disparado ao cuidador (lógica existente)           │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Por que FCM data-only + priority:"high"? ─────────────────────────────────┐
 * │  Mensagens data-only (sem campo "notification") chegam SEMPRE a             │
 * │  onMessageReceived(), mesmo com app morto.                                  │
 * │                                                                             │
 * │  Mensagens com "notification" quando o app está morto são interceptadas     │
 * │  pelo SDK e exibidas como notificação do sistema — onMessageReceived()      │
 * │  NÃO é chamado. Por isso usamos data-only.                                  │
 * │                                                                             │
 * │  priority:"high" acorda o CPU mesmo durante Doze Mode e concede a          │
 * │  "background start exemption" do Android 12+ que permite chamar            │
 * │  startForegroundService() de um receptor de background.                    │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Por que NÃO há verificação de autenticação aqui? ─────────────────────────┐
 * │  Quando o UNISOC (ou outro OEM agressivo) mata o processo e o FCM acorda,  │
 * │  o Firebase Auth precisa de alguns milissegundos para restaurar o estado   │
 * │  persistido do disco. getCurrentUser() é síncrono e retorna null nessa    │
 * │  janela transitória — causando aborto prematuro mesmo com usuário logado.  │
 * │                                                                             │
 * │  Solução: não verificar auth aqui. O HeartRateService usa                  │
 * │  addAuthStateListener() (dispara imediatamente se já carregado, ou assim   │
 * │  que restaurado) para aguardar o estado correto sem race condition.         │
 * │                                                                             │
 * │  Segurança: tokens FCM são privados e específicos por dispositivo. Receber  │
 * │  WAKE_UP pressupõe que a Cloud Function teve o token do relógio, o que     │
 * │  implica que o paciente fez login ao menos uma vez.                         │
 * └────────────────────────────────────────────────────────────────────────────┘
 */
public class EverNearMessagingService extends FirebaseMessagingService {

    private static final String TAG = "EverNearFCM";

    /** Valor do campo "tipo" na mensagem FCM que aciona o acordar do serviço. */
    private static final String TIPO_WAKE_UP = "WAKE_UP";

    // ==================== Recepção de mensagem ====================

    /**
     * Chamado pelo FCM SDK quando chega uma mensagem data-only com o app em background/morto.
     *
     * Para mensagem tipo {@value #TIPO_WAKE_UP}:
     *  1. Verifica se HeartRateService já está ativo (campo estático) —
     *     se sim, o sensor já monitora e nenhuma ação é necessária.
     *  2. Se não estiver ativo: chama startForegroundService().
     *     A janela de execução concedida pelo FCM de alta prioridade torna
     *     isso possível mesmo em background no Android 12+.
     *  3. HeartRateService cuida internamente da autenticação via
     *     addAuthStateListener() — sem race condition.
     *
     * NÃO verifica autenticação aqui. Ver Javadoc da classe para o motivo.
     */
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        String tipo = message.getData().get("tipo");
        Log.d(TAG, "FCM recebido — tipo=" + tipo + " de=" + message.getFrom());

        if (!TIPO_WAKE_UP.equals(tipo)) {
            Log.w(TAG, "Tipo de mensagem FCM desconhecido ignorado: " + tipo);
            return;
        }

        // Se o serviço já está rodando, o sensor já está monitorando — nada a fazer.
        // Quando o processo foi morto, getInstance() retorna null (novo processo),
        // então este guard só filtra chamadas redundantes enquanto o app está vivo.
        if (HeartRateService.getInstance() != null) {
            Log.d(TAG, "WAKE_UP: HeartRateService já está ativo — nenhuma ação necessária");
            return;
        }

        Log.i(TAG, "WAKE_UP recebido — iniciando HeartRateService");
        try {
            startForegroundService(new Intent(this, HeartRateService.class));
            Log.d(TAG, "WAKE_UP: startForegroundService() enviado com sucesso");
        } catch (Exception e) {
            // Caso raro: janela FCM de alta prioridade expirou ou OEM bloqueou o start.
            // HeartRateService será reiniciado pelo AlarmManager watchdog externo.
            Log.e(TAG, "WAKE_UP: falha ao iniciar HeartRateService: " + e.getMessage());
        }
    }

    // ==================== Renovação de token ====================

    /**
     * Chamado automaticamente pelo FCM SDK quando o token de registro é renovado.
     *
     * O FCM renova o token em diversas situações:
     *  - Reinstalação do aplicativo
     *  - Limpeza de dados do app
     *  - Rotação de segurança periódica do FCM
     *  - Restauração de backup em novo dispositivo
     *
     * onNewToken() é o único ponto confiável para manter o token sempre atualizado
     * no Firestore. Sem ele, a Cloud Function "acordarPaciente" enviaria a mensagem
     * para um token inválido e o relógio nunca acordaria.
     *
     * Se o usuário não estiver autenticado no momento da renovação (ex: token renovado
     * antes do primeiro login), o HeartRateService chama
     * {@link FirebaseHelper#sincronizarFcmToken} ao iniciar, garantindo consistência.
     *
     * @param token novo token FCM — válido até a próxima renovação automática
     */
    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "Token FCM renovado — tentando salvar no Firestore");

        // Usamos getCurrentUser() aqui porque onNewToken() não é chamado após kill
        // do processo — é chamado quando o app está rodando normalmente. Sem race condition.
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.w(TAG, "Token FCM renovado antes do login — será salvo quando o "
                    + "HeartRateService iniciar (via sincronizarFcmToken)");
            return;
        }

        String uid = FirebaseAuth.getInstance().getUid();
        Log.d(TAG, "Salvando novo token FCM para uid=" + uid);
        FirebaseHelper.salvarFcmToken(uid, token);
    }
}

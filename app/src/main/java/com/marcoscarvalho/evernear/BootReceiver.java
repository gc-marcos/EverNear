package com.marcoscarvalho.evernear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Service;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * Reinicia os serviços de segundo plano automaticamente após o dispositivo ser reiniciado.
 *
 * Fluxo:
 *  1. Verifica se o boot é válido (ACTION_BOOT_COMPLETED ou QUICKBOOT_POWERON)
 *  2. Verifica se há usuário autenticado
 *  3. Tenta ler o tipo do usuário no Firestore
 *     - Sucesso: salva no cache e inicia o serviço correto
 *     - Falha (sem rede): usa cache local (SharedPreferences) como fallback
 *
 * Usa goAsync() para evitar que o Android mate o processo antes
 * do callback assíncrono do Firestore retornar.
 *
 * Requer no AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 * Compatível com Android até API 28 (Android 9 "Pie").
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    // Chaves do cache local
    private static final String PREFS_NAME    = "evernear_prefs";
    private static final String KEY_USER_TIPO = "user_tipo";

    // Os valores aceitos para o campo "tipo" e os helpers de verificação estão centralizados
    // em FirebaseHelper.Tipos, FirebaseHelper.isPaciente() e FirebaseHelper.isCuidador().

    @Override
    public void onReceive(Context context, Intent intent) {

        // 1. Valida a ação — ignora broadcasts irrelevantes
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        Log.d(TAG, "Boot detectado — verificando autenticação");

        // 2. Verifica autenticação. Após um reboot, o FirebaseAuth pode ainda
        // não ter restaurado o usuário do disco. Nesse caso, o cache local é
        // a única fonte disponível para retomar o serviço sem rede.
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            Log.w(TAG, "Auth ainda não restaurada — tentando cache local");
            iniciarServicoPorCache(context);
            return;
        }

        String uid = auth.getUid();
        Log.d(TAG, "Usuário autenticado: " + uid + " — consultando Firestore");

        /*
         * 3. goAsync() → mantém o BroadcastReceiver "vivo" até result.finish()
         *    Sem isso, o Android pode matar o processo antes do Firestore responder,
         *    especialmente em Android 8+ com restrições de background.
         *
         *    ATENÇÃO: goAsync() retorna null quando onReceive() é chamado
         *    manualmente (ex.: testes instrumentados). Em produção nunca é null.
         */
        final PendingResult result = goAsync();

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    try {
                        if (doc == null || !doc.exists()) {
                            Log.w(TAG, "Documento do usuário não encontrado — usando cache local");
                            iniciarServicoPorCache(context);
                            return;
                        }

                        String tipo = doc.getString("tipo");
                        Log.d(TAG, "Tipo do usuário no Firestore: " + tipo);

                        // Salva no cache para uso futuro sem rede
                        salvarTipoNoCache(context, tipo);

                        // Inicia o serviço correto com base no tipo
                        iniciarServicoPorTipo(context, tipo);

                    } finally {
                        finalizar(result); // libera o processo sempre, mesmo em erro
                    }
                })
                .addOnFailureListener(e -> {
                    try {
                        Log.w(TAG, "Sem acesso ao Firestore no boot: " + e.getMessage()
                                + " — tentando cache local");
                        iniciarServicoPorCache(context);
                    } finally {
                        finalizar(result);
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Métodos auxiliares
    // -------------------------------------------------------------------------

    /**
     * Finaliza o broadcast assíncrono de forma segura.
     * Ignora {@code null} (chamada manual de onReceive em testes).
     */
    private static void finalizar(@Nullable PendingResult result) {
        if (result != null) {
            result.finish();
        }
    }

    /**
     * Inicia o serviço correto com base no tipo do usuário.
     * Checagem explícita para evitar iniciar serviço errado com valores inesperados.
     */
    private void iniciarServicoPorTipo(Context context, String tipo) {
        if (FirebaseHelper.isPaciente(tipo)) {
            Log.d(TAG, "Iniciando HeartRateService (paciente)");
            iniciarServicoEmForeground(context, HeartRateService.class);

        } else if (FirebaseHelper.isCuidador(tipo)) {
            Log.d(TAG, "Iniciando CaregiverAlertService (cuidador)");
            iniciarServicoEmForeground(context, CaregiverAlertService.class);

        } else {
            // Tipo nulo, vazio ou desconhecido — não inicia nada para evitar crashes
            Log.w(TAG, "Tipo de usuário desconhecido ou nulo: [" + tipo + "] — nenhum serviço iniciado");
        }
    }

    /** Ponto substituível nos testes para validar o roteamento sem iniciar serviços reais. */
    void iniciarServicoEmForeground(Context context, Class<? extends Service> serviceClass) {
        ContextCompat.startForegroundService(context, new Intent(context, serviceClass));
    }

    /**
     * Fallback: usa o tipo salvo localmente quando o Firestore não está disponível.
     * Evita iniciar serviços errados ou desnecessários em dispositivos sem o sensor adequado.
     */
    private void iniciarServicoPorCache(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String tipoCache = prefs.getString(KEY_USER_TIPO, null);

        if (tipoCache == null) {
            Log.w(TAG, "Cache vazio e Firestore indisponível — nenhum serviço iniciado");
            return;
        }

        Log.d(TAG, "Usando tipo do cache: " + tipoCache);
        iniciarServicoPorTipo(context, tipoCache);
    }

    /**
     * Salva o tipo do usuário localmente para uso offline futuro.
     * Chamado sempre que o Firestore retorna com sucesso.
     */
    private void salvarTipoNoCache(Context context, String tipo) {
        if (tipo == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_USER_TIPO, tipo).apply();
        Log.d(TAG, "Tipo salvo no cache: " + tipo);
    }

    // -------------------------------------------------------------------------
    // Método utilitário público — chame no login para popular o cache cedo
    // -------------------------------------------------------------------------

    /**
     * Salva o tipo do usuário no cache logo após o login.
     * Isso garante que o fallback offline já funcione desde o primeiro boot
     * após a instalação do app.
     *
     * Uso (na Activity/Fragment de login, após autenticação bem-sucedida):
     *   BootReceiver.salvarTipoAposLogin(context, "paciente");
     */
    public static void salvarTipoAposLogin(Context context, String tipo) {
        if (tipo == null || context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_USER_TIPO, tipo).apply();
        Log.d(TAG, "Cache populado no login: " + tipo);
    }

    /**
     * Limpa o cache ao fazer logout.
     * Evita que o próximo usuário herde o tipo do usuário anterior.
     *
     * Uso (no logout):
     *   BootReceiver.limparCacheAoLogout(context);
     */
    public static void limparCacheAoLogout(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_USER_TIPO).apply();
        Log.d(TAG, "Cache limpo no logout");
    }
}
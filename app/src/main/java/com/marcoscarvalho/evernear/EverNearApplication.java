package com.marcoscarvalho.evernear;

import android.app.Application;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;

/**
 * Classe Application — executada antes de qualquer Activity ou Service.
 *
 * Responsabilidades:
 *  1. Configurar o Firestore com persistência offline e cache dimensionado por tipo de dispositivo.
 *  2. Manter o estado global de conectividade atualizado via NetworkCallback.
 *  3. Expor {@link #isOnline()} para uso em toda a aplicação — elimina múltiplas instâncias
 *     redundantes de ConnectivityManager nas Activities.
 *
 * ┌─ Cache por tipo de dispositivo ────────────────────────────────────────────┐
 * │  CACHE_SIZE_UNLIMITED é proibido em Wear OS: armazenamento típico é 4-8 GB  │
 * │  (1-2 GB utilizável) e o Firestore SDK não coleta o cache automaticamente.   │
 * │  Smartwatch (paciente): 10 MB — doc próprio + alertas enviados              │
 * │  Celular/tablet (cuidador): 50 MB — docs dos 3 pacientes + alertas         │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Conectividade global ──────────────────────────────────────────────────────┐
 * │  NetworkCallback registrado uma vez no processo — mantém onlineAtual        │
 * │  sincronizado. Qualquer componente consulta isOnline() sem custo.            │
 * │  Compatível com API 28 (NetworkCallback disponível desde API 21).            │
 * └────────────────────────────────────────────────────────────────────────────┘
 */
public class EverNearApplication extends Application {

    private static final String TAG = "EverNearApp";

    // ── Limites de cache Firestore ────────────────────────────────────────────
    /** Cache para smartwatch (paciente): apenas doc próprio + alertas recentes. */
    private static final long CACHE_WATCH = 10L * 1024 * 1024;  // 10 MB
    /** Cache para celular/tablet (cuidador): docs dos pacientes + alertas. */
    private static final long CACHE_PHONE = 50L * 1024 * 1024;  // 50 MB

    // ── Estado global de conectividade ────────────────────────────────────────
    /**
     * Verdadeiro se o dispositivo tem acesso à internet no momento.
     * Atualizado automaticamente pelo NetworkCallback — nenhuma consulta ativa necessária.
     * Inicializado como {@code true} (otimista) até a primeira callback do sistema.
     */
    private static volatile boolean onlineAtual = true;

    // ==================== Ciclo de vida ====================

    @Override
    public void onCreate() {
        super.onCreate();
        configurarFirestore();
        monitorarConectividade();
    }

    // ==================== Firestore ====================

    /**
     * Configura a persistência offline e o tamanho do cache de acordo com o
     * tipo de dispositivo (smartwatch vs celular/tablet).
     *
     * Usar CACHE_SIZE_UNLIMITED em Wear OS é um risco crítico de armazenamento:
     * BPM updates a cada 3 s + alertas históricos enchem o disco indefinidamente,
     * causando encerramento de processos pelo Android e perda do HeartRateService.
     */
    private void configurarFirestore() {
        try {
            boolean isWatch = getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_WATCH);
            long cacheSizeBytes = isWatch ? CACHE_WATCH : CACHE_PHONE;

            FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .setCacheSizeBytes(cacheSizeBytes)
                    .build();

            FirebaseFirestore.getInstance().setFirestoreSettings(settings);

            Log.i(TAG, "Firestore configurado"
                    + " | dispositivo=" + (isWatch ? "smartwatch" : "celular/tablet")
                    + " | cache=" + (cacheSizeBytes / 1024 / 1024) + " MB"
                    + " | persistência=habilitada");
        } catch (Exception e) {
            // Falha crítica: o app continua, mas alertas enviados sem rede podem ser perdidos
            Log.e(TAG, "Falha ao configurar Firestore — persistência offline pode estar "
                    + "desativada. Alertas offline podem ser perdidos.", e);
        }
    }

    // ==================== Conectividade ====================

    /**
     * Registra um {@link ConnectivityManager.NetworkCallback} para manter
     * {@link #onlineAtual} sempre atualizado sem polling ativo.
     *
     * A callback lida corretamente com múltiplas redes simultâneas (ex.: Wi-Fi + dados):
     * {@code onLost} só marca offline se não houver nenhuma outra rede ativa.
     *
     * Compatível com API 21+. ✅ API 28.
     */
    private void monitorarConectividade() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager indisponível — isOnline() retornará true por padrão");
            return;
        }

        // Lê o estado atual antes de receber a primeira callback
        onlineAtual = temInternet(cm);
        Log.d(TAG, "Conectividade inicial: " + (onlineAtual ? "online" : "offline"));

        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                onlineAtual = true;
                Log.d(TAG, "Conectividade: online");
            }

            @Override
            public void onLost(Network network) {
                // Verifica se ainda há outra rede ativa (ex.: perdeu Wi-Fi mas tem dados móveis)
                onlineAtual = temInternet(cm);
                Log.d(TAG, "Rede perdida. Conectividade: "
                        + (onlineAtual ? "online (outra rede ativa)" : "offline"));
            }
        });
    }

    /**
     * Verifica se há ao menos uma rede com capacidade de internet.
     * Usa {@link NetworkCapabilities} (API 23+), compatível com API 28+.
     */
    private boolean temInternet(ConnectivityManager cm) {
        Network rede = cm.getActiveNetwork();
        if (rede == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(rede);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ==================== API pública ====================

    /**
     * Retorna se o dispositivo tem acesso à internet no momento.
     *
     * Atualizado automaticamente via {@link ConnectivityManager.NetworkCallback}
     * registrado em {@link #monitorarConectividade()}. Custo: leitura de um campo
     * {@code volatile boolean} — O(1), sem alocação, sem IO.
     *
     * Uso recomendado em Activities e Services:
     * <pre>
     *     if (!EverNearApplication.isOnline()) {
     *         Toast.makeText(this, "Sem conexão com a internet.", Toast.LENGTH_SHORT).show();
     *         return;
     *     }
     * </pre>
     *
     * Substitui as implementações redundantes de {@code isConectado()} dispersas
     * em DashboardCuidadorActivity e DashboardPacienteActivity.
     */
    public static boolean isOnline() {
        return onlineAtual;
    }
}

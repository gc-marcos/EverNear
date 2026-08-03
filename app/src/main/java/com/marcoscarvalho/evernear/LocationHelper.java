package com.marcoscarvalho.evernear;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Centraliza toda a lógica de geolocalização do EverNear.
 *
 * ┌─ Responsabilidades ────────────────────────────────────────────────────────┐
 * │  1. Verificação de permissão e GPS disponível.                             │
 * │  2. Localização única (one-shot) disparada por evento de emergência —      │
 * │     sem manter o GPS ativo continuamente, preservando bateria.             │
 * │  3. Configuração e remoção de Geofence de zona segura definida pelo        │
 * │     cuidador no Firestore.                                                 │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Compatibilidade ───────────────────────────────────────────────────────────┐
 * │  API 28 (Wear OS máximo). Usa:                                              │
 * │   - FusedLocationProviderClient (Play Services, não ligado ao nível de API) │
 * │   - LocationRequest.Builder (play-services-location ≥ 21, sem deprecation)  │
 * │   - LocationManager.isProviderEnabled() (API 1+)                            │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * Esta classe é utilitária (apenas métodos estáticos) — não instanciar.
 */
public final class LocationHelper {

    private static final String TAG = "LocationHelper";

    /**
     * Timeout para cada request de localização paralelo.
     * 20 s cobre GPS a frio em indoor (cold start típico: 10–15 s).
     */
    private static final long LOCATION_TIMEOUT_MS = 20_000L;

    /**
     * Intervalo de entrega dos updates de localização (mínimo 1 s entre callbacks).
     */
    private static final long LOCATION_INTERVAL_MS = 2_000L;

    /**
     * ID do Geofence — único por paciente, substituído a cada atualização de zona segura.
     */
    public static final String GEOFENCE_ID = "EVERNEAR_ZONA_SEGURA";

    /**
     * Raio mínimo em metros recomendado pelo Google para Geofence.
     * Valores menores causam falsos positivos frequentes.
     */
    public static final float GEOFENCE_RAIO_MINIMO_M = 100f;

    private LocationHelper() { /* utilitária — não instanciar */ }

    // ==================== Verificações de disponibilidade ====================

    /**
     * Verifica se {@code ACCESS_FINE_LOCATION} foi concedida pelo usuário.
     */
    public static boolean temPermissao(Context context) {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Verifica se pelo menos um provedor de localização (GPS ou rede) está habilitado.
     *
     * Usa {@link LocationManager} — disponível desde API 1, não requer permissão de
     * localização apenas para checar o estado do provedor.
     */
    public static boolean gpsHabilitado(Context context) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    // ==================== Localização via callback (para incluir no alerta) ====================

    /**
     * Obtém a última localização conhecida e entrega via callback.
     *
     * Usa apenas o cache do FusedLocationProviderClient (getLastLocation).
     * Não dispara um novo request de GPS — é zero-latência quando o HeartRateService
     * já está monitorando ativamente (o cache é atualizado pelo LocationCallback contínuo).
     *
     * O callback recebe {@code null} quando:
     *  - Permissão de localização negada
     *  - Cache vazio (dispositivo nunca obteve um fix desde o boot)
     *  - Falha interna do FusedLocation
     *
     * Em todos esses casos o alerta é enviado mesmo assim, porém sem coordenadas.
     *
     * @param context  contexto Android (preferencialmente o serviço ou activity)
     * @param callback recebe o objeto {@link Location} ou null
     */
    public static void obterUltimaLocalizacaoRapida(Context context,
                                                    FirebaseHelper.Callback<Location> callback) {
        if (callback == null) return;
        if (!temPermissao(context)) {
            Log.w(TAG, "obterUltimaLocalizacaoRapida: ACCESS_FINE_LOCATION negada");
            callback.onResult(null);
            return;
        }
        try {
            LocationServices.getFusedLocationProviderClient(context)
                    .getLastLocation()
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            Log.d(TAG, "Localização rápida (cache): "
                                    + loc.getLatitude() + ", " + loc.getLongitude()
                                    + " ±" + loc.getAccuracy() + " m");
                        } else {
                            Log.d(TAG, "Cache de localização vazio — alerta enviado sem coordenadas");
                        }
                        callback.onResult(loc); // null é tratado pelo chamador
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "getLastLocation() falhou: " + e.getMessage());
                        callback.onResult(null);
                    });
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException em obterUltimaLocalizacaoRapida: " + e.getMessage());
            callback.onResult(null);
        }
    }

    // ==================== Localização única (emergência) ====================

    /**
     * Obtém a localização atual e persiste no Firestore do paciente.
     *
     * ┌─ Estratégia ───────────────────────────────────────────────────────────┐
     * │  1. getLastLocation() — usa cache do FusedLocation (0 ms, sem GPS).   │
     * │  2. Se nulo: dispara DOIS requests em paralelo e o primeiro que         │
     * │     retornar um fix não-nulo ganha (AtomicBoolean evita dupla escrita). │
     * │     • BALANCED_POWER (rede/Wi-Fi/BT) — rápido, funciona indoor.       │
     * │     • HIGH_ACCURACY (GPS) — preciso, funciona outdoor.                 │
     * │  Ambos têm timeout de LOCATION_TIMEOUT_MS (20 s).                      │
     * └────────────────────────────────────────────────────────────────────────┘
     *
     * Fire-and-forget: o alerta é enviado antes desta chamada retornar.
     * A verificação de GPS habilitado é omitida intencionalmente — o
     * FusedLocationProviderClient lida com isso internamente e o NETWORK
     * provider pode entregar localização mesmo sem GPS ativo.
     */
    public static void obterESalvarLocalizacao(Context context, String uidPaciente) {
        if (uidPaciente == null || uidPaciente.isEmpty()) return;
        if (!temPermissao(context)) {
            Log.w(TAG, "obterESalvarLocalizacao: ACCESS_FINE_LOCATION negada"
                    + " — conceda a permissão em Configurações → Apps → EverNear → Permissões");
            return;
        }

        FusedLocationProviderClient client =
                LocationServices.getFusedLocationProviderClient(context);

        try {
            client.getLastLocation()
                    .addOnSuccessListener(cached -> {
                        if (cached != null) {
                            Log.d(TAG, "Localização (cache): "
                                    + cached.getLatitude() + ", " + cached.getLongitude()
                                    + " ±" + cached.getAccuracy() + " m");
                            FirebaseHelper.salvarLocalizacaoEmergencia(uidPaciente, cached, null);
                        } else {
                            Log.d(TAG, "Cache vazio — disparando requests paralelos (rede + GPS)");
                            dispararRequestsParalelos(client, uidPaciente);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "getLastLocation() falhou (" + e.getMessage()
                                + ") — disparando requests paralelos");
                        dispararRequestsParalelos(client, uidPaciente);
                    });
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException em getLastLocation: " + e.getMessage());
        }
    }

    /**
     * Dispara DOIS requests de localização em paralelo: rede/Wi-Fi e GPS.
     *
     * O primeiro que entregar um fix não-nulo salva no Firestore.
     * O {@link AtomicBoolean} garante que apenas uma escrita ocorra mesmo
     * que ambos respondam quase simultaneamente.
     *
     * Por que paralelo e não sequencial?
     * - Sequential: stage 2 só inicia se stage 1 retornar callback com null.
     *   Mas FusedLocation não chama o callback com null em timeout — simplesmente
     *   não chama. Então stage 2 nunca iniciaria por timeout de stage 1.
     * - Parallel: ambos correm desde o início; o mais rápido ganha.
     *   Rede/Wi-Fi geralmente responde em 2–5 s; GPS pode levar 10–20 s.
     */
    private static void dispararRequestsParalelos(FusedLocationProviderClient client,
                                                  String uidPaciente) {
        // Flag compartilhada: só a primeira resposta não-nula salva no Firestore.
        // AtomicBoolean porque callbacks de providers distintos podem chegar
        // em threads diferentes no FusedLocation interno.
        final AtomicBoolean salvo = new AtomicBoolean(false);

        iniciarRequest(client, uidPaciente,
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, "rede/Wi-Fi", salvo);

        iniciarRequest(client, uidPaciente,
                Priority.PRIORITY_HIGH_ACCURACY, "GPS", salvo);
    }

    /**
     * Cria e registra um único LocationRequest.
     *
     * ┌─ Por que HandlerThread em vez de Looper.getMainLooper()? ─────────────┐
     * │  No Wear OS, quando a tela apaga, o Android pode throttle a entrega   │
     * │  de mensagens ao main looper para economizar energia. Usar um          │
     * │  HandlerThread dedicado com prioridade FOREGROUND garante que os       │
     * │  callbacks de localização continuem sendo entregues mesmo com a tela  │
     * │  apagada, essencial para localização de emergência.                    │
     * └────────────────────────────────────────────────────────────────────────┘
     *
     * @param priority  {@link Priority#PRIORITY_BALANCED_POWER_ACCURACY} ou
     *                  {@link Priority#PRIORITY_HIGH_ACCURACY}
     * @param nome      rótulo para log (ex.: "rede/Wi-Fi", "GPS")
     * @param salvo     flag compartilhada entre requests paralelos
     */
    private static void iniciarRequest(FusedLocationProviderClient client,
                                       String uidPaciente,
                                       int priority,
                                       String nome,
                                       AtomicBoolean salvo) {
        LocationRequest request = new LocationRequest.Builder(priority, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(1_000L)
                .setMaxUpdates(1)
                .setDurationMillis(LOCATION_TIMEOUT_MS)
                .build();

        // HandlerThread dedicado para receber callbacks de localização.
        // Sobrevive ao throttling do main looper quando a tela apaga.
        HandlerThread locationThread = new HandlerThread(
                "EverNear-LocationThread-" + nome,
                android.os.Process.THREAD_PRIORITY_FOREGROUND);
        locationThread.start();
        Looper locationLooper = locationThread.getLooper();

        LocationCallback callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) {
                    Log.w(TAG, nome + ": onLocationResult com loc=null — ignorando");
                    pararThread(locationThread);
                    return;
                }
                // compareAndSet(false, true): só o primeiro thread que chegar aqui salva
                if (!salvo.compareAndSet(false, true)) {
                    Log.d(TAG, nome + ": outro provider já salvou — descartando");
                    try { client.removeLocationUpdates(this); } catch (Exception ignored) {}
                    pararThread(locationThread);
                    return;
                }
                Log.d(TAG, nome + ": " + loc.getLatitude() + ", " + loc.getLongitude()
                        + " ±" + loc.getAccuracy() + " m — salvando no Firestore");
                FirebaseHelper.salvarLocalizacaoEmergencia(uidPaciente, loc, null);
                try { client.removeLocationUpdates(this); } catch (Exception ignored) {}
                pararThread(locationThread);
            }
        };

        try {
            client.requestLocationUpdates(request, callback, locationLooper);
            Log.d(TAG, "Request " + nome + " iniciado (timeout=" + LOCATION_TIMEOUT_MS / 1000
                    + "s, looper=LocationThread)");
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException ao iniciar request " + nome + ": " + e.getMessage());
            pararThread(locationThread);
        }
    }

    /** Para o HandlerThread de localização de forma segura após uso. */
    private static void pararThread(HandlerThread thread) {
        try { thread.quitSafely(); } catch (Exception ignored) {}
    }

    // ==================== Geofence (zona segura) ====================

    /**
     * Registra (ou substitui) a zona segura do paciente no sistema de Geofence.
     *
     * Remove o geofence anterior antes de adicionar o novo para garantir que
     * parâmetros atualizados pelo cuidador entrem em vigor imediatamente.
     *
     * {@code GEOFENCE_TRANSITION_EXIT}: dispara quando o dispositivo SAI do raio.
     * {@code setNotificationResponsiveness(60_000)}: aguarda 60 s antes de disparar —
     *   reduz falsos positivos por oscilações momentâneas do GPS.
     *
     * @param context        contexto do serviço
     * @param latitude       latitude do centro da zona
     * @param longitude      longitude do centro da zona
     * @param raioMetros     raio em metros (aplicado mínimo de {@link #GEOFENCE_RAIO_MINIMO_M})
     * @param pendingIntent  PendingIntent de {@link GeofenceReceiver} — deve ser
     *                       {@code FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE}
     */
    public static void registrarGeofence(Context context,
                                         double latitude,
                                         double longitude,
                                         float raioMetros,
                                         PendingIntent pendingIntent) {
        if (!temPermissao(context)) {
            Log.w(TAG, "registrarGeofence: permissão negada — geofence não registrado");
            return;
        }

        float raio = Math.max(raioMetros, GEOFENCE_RAIO_MINIMO_M);

        Geofence geofence = new Geofence.Builder()
                .setRequestId(GEOFENCE_ID)
                .setCircularRegion(latitude, longitude, raio)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                // 60 s de janela: elimina falsos positivos por sinal instável
                .setNotificationResponsiveness(60_000)
                .build();

        GeofencingRequest request = new GeofencingRequest.Builder()
                // INITIAL_TRIGGER_EXIT: não dispara se o dispositivo JÁ está fora ao registrar
                .setInitialTrigger(0)
                .addGeofence(geofence)
                .build();

        GeofencingClient client = LocationServices.getGeofencingClient(context);

        // Remove o geofence anterior para aplicar o novo raio/centro sem duplicatas
        client.removeGeofences(Collections.singletonList(GEOFENCE_ID))
                .addOnCompleteListener(removeTask -> {
                    try {
                        client.addGeofences(request, pendingIntent)
                                .addOnSuccessListener(v ->
                                        Log.i(TAG, "Geofence registrado: ("
                                                + latitude + ", " + longitude
                                                + ") raio=" + raio + " m"))
                                .addOnFailureListener(e ->
                                        Log.e(TAG, "Falha ao registrar geofence: "
                                                + e.getMessage()));
                    } catch (SecurityException e) {
                        Log.e(TAG, "SecurityException ao registrar geofence: " + e.getMessage());
                    }
                });
    }

    /**
     * Remove o geofence ativo, se houver.
     *
     * Chamado quando:
     *  - O cuidador remove a zona segura (campos ausentes no snapshot).
     *  - O {@link HeartRateService} encerra.
     */
    public static void removerGeofence(Context context) {
        LocationServices.getGeofencingClient(context)
                .removeGeofences(Collections.singletonList(GEOFENCE_ID))
                .addOnSuccessListener(v -> Log.d(TAG, "Geofence removido"))
                .addOnFailureListener(e ->
                        Log.w(TAG, "Falha ao remover geofence (pode não existir): "
                                + e.getMessage()));
    }
}

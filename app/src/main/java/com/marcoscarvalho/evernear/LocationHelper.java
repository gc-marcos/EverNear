package com.marcoscarvalho.evernear;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
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
     * Timeout para aguardar localização nova quando {@code getLastLocation()} retorna null.
     * 8 s é suficiente para o GPS obter fix indoor em condições normais.
     */
    private static final long LOCATION_TIMEOUT_MS = 8_000L;

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

    // ==================== Localização única (emergência) ====================

    /**
     * Obtém a localização atual uma única vez e persiste no Firestore do paciente.
     *
     * ┌─ Estratégia de obtenção ───────────────────────────────────────────────┐
     * │  1. {@code getLastLocation()} — rápido, sem liga o GPS se já houver    │
     * │     um fix recente em cache.                                            │
     * │  2. Se nulo, solicita leitura nova via {@code requestLocationUpdates()} │
     * │     com timeout de 8 s e {@code setMaxUpdates(1)} — cancela sozinho    │
     * │     após a primeira leitura.                                            │
     * └────────────────────────────────────────────────────────────────────────┘
     *
     * Deve ser chamado em PARALELO com o envio do alerta — não bloqueia o alerta.
     * Falhas são logadas silenciosamente; o alerta é enviado independentemente.
     *
     * @param context     contexto do serviço (não deve ser Activity já destruída)
     * @param uidPaciente UID do documento {@code users/{uid}} a ser atualizado
     */
    public static void obterESalvarLocalizacao(Context context, String uidPaciente) {
        if (uidPaciente == null || uidPaciente.isEmpty()) return;
        if (!temPermissao(context)) {
            Log.w(TAG, "obterESalvarLocalizacao: permissão negada — localização omitida do alerta");
            return;
        }
        if (!gpsHabilitado(context)) {
            Log.w(TAG, "obterESalvarLocalizacao: GPS desabilitado — localização omitida do alerta");
            return;
        }

        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(context);

        try {
            client.getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            Log.d(TAG, "Localização (cache): "
                                    + location.getLatitude() + ", " + location.getLongitude()
                                    + " ±" + location.getAccuracy() + " m");
                            FirebaseHelper.salvarLocalizacaoEmergencia(uidPaciente, location, null);
                        } else {
                            Log.d(TAG, "getLastLocation() nulo — solicitando leitura nova");
                            solicitarLocalizacaoNova(context, client, uidPaciente);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "getLastLocation() falhou: " + e.getMessage()
                                + " — solicitando leitura nova");
                        solicitarLocalizacaoNova(context, client, uidPaciente);
                    });
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException em getLastLocation: " + e.getMessage());
        }
    }

    /**
     * Solicita uma leitura nova de localização quando o cache está vazio.
     *
     * {@code setMaxUpdates(1)} garante cancelamento automático após a primeira leitura;
     * {@code setDurationMillis(LOCATION_TIMEOUT_MS)} cancela por timeout se não houver sinal.
     */
    private static void solicitarLocalizacaoNova(Context context,
                                                   FusedLocationProviderClient client,
                                                   String uidPaciente) {
        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
                .setMinUpdateIntervalMillis(1_000L)
                .setMaxUpdates(1)
                .setDurationMillis(LOCATION_TIMEOUT_MS)
                .build();

        // Wrapper de flag: evita processar resultado duplicado se o callback disparar
        // duas vezes antes de removeLocationUpdates() completar.
        final boolean[] entregue = {false};

        LocationCallback callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                if (entregue[0]) return;
                entregue[0] = true;

                Location location = result.getLastLocation();
                if (location != null) {
                    Log.d(TAG, "Localização (nova): "
                            + location.getLatitude() + ", " + location.getLongitude()
                            + " ±" + location.getAccuracy() + " m");
                    FirebaseHelper.salvarLocalizacaoEmergencia(uidPaciente, location, null);
                } else {
                    Log.w(TAG, "Leitura nova retornou null — sem sinal de GPS");
                }
                // Remove updates imediatamente; setMaxUpdates(1) já faz isso,
                // mas removeLocationUpdates é redundância de segurança.
                try { client.removeLocationUpdates(this); } catch (Exception ignored) {}
            }
        };

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException em requestLocationUpdates: " + e.getMessage());
        }
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

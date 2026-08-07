package com.marcoscarvalho.evernear.debug;

import android.location.Location;

/**
 * Utilitários de geolocalização para o Modo Debug.
 *
 * <p><b>Propósito:</b> Fornecer objetos {@link Location} simulados e cálculos
 * de distância para que {@link DebugLocationActivity} possa testar toda a
 * lógica de geofencing em dispositivos físicos sem mover o relógio.</p>
 *
 * <p><b>Integração com o sistema:</b>
 * <pre>
 *   DebugLocationActivity
 *     → criarLocalizacaoSimulada(lat, lng)
 *     → HeartRateService (ACTION_DEBUG_GEOFENCE_EXIT + Location extra)
 *     → tratarSaidaGeofenceComLocalizacao(location)   ← mesma cadeia de produção
 *     → FirebaseHelper.enviarAlerta()                 ← cuidador recebe notificação
 * </pre>
 * </p>
 *
 * <p><b>IMPORTANTE:</b> Esta classe deve ser usada somente quando
 * {@code BuildConfig.DEBUG == true}. Não contém lógica de negócio — apenas
 * fábricas e cálculos auxiliares.</p>
 */
public final class DebugGeofenceHelper {

    /** Nome do provider usado em objetos Location de debug — aparece nos logs. */
    public static final String PROVIDER_DEBUG = "DEBUG";

    /** Precisão simulada em metros (equivalente a GPS com bom sinal). */
    public static final float ACCURACY_SIMULADA = 5.0f;

    /**
     * Bearing padrão para os pontos "fora do raio" (Norte = 0°).
     * Escolhido por ser previsível e fácil de visualizar no mapa.
     */
    public static final float BEARING_NORTE = 0f;

    private DebugGeofenceHelper() { /* utilitário estático — não instanciar */ }

    // ──────────────────────────────────────────────────────────────────────────
    // Fábrica de Location
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Cria um objeto {@link Location} simulado pronto para ser injetado na
     * mesma rotina usada pelo GPS real ({@code HeartRateService.tratarSaidaGeofenceComLocalizacao}).
     *
     * O objeto criado é estruturalmente idêntico ao retornado pelo
     * {@code FusedLocationProviderClient} — diferencia-se apenas pelo provider "DEBUG".
     *
     * @param latitude  graus decimais (-90 a +90)
     * @param longitude graus decimais (-180 a +180)
     * @return Location simulado, pronto para uso
     */
    public static Location criarLocalizacaoSimulada(double latitude, double longitude) {
        Location loc = new Location(PROVIDER_DEBUG);
        loc.setLatitude(latitude);
        loc.setLongitude(longitude);
        loc.setAccuracy(ACCURACY_SIMULADA);
        loc.setTime(System.currentTimeMillis());
        return loc;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cálculos geográficos
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Calcula a distância em metros entre dois pontos geográficos.
     *
     * Usa {@link Location#distanceBetween} (cálculo geodésico baseado no
     * esferoide WGS-84 — preciso para distâncias de metros a centenas de km).
     *
     * @return distância em metros (≥ 0)
     */
    public static float calcularDistanciaMetros(double lat1, double lng1,
                                                double lat2, double lng2) {
        float[] resultado = new float[1];
        Location.distanceBetween(lat1, lng1, lat2, lng2, resultado);
        return resultado[0];
    }

    /**
     * Calcula as coordenadas de um ponto situado exatamente {@code distanciaMetros}
     * metros de distância do centro, na direção indicada pelo {@code bearingGraus}.
     *
     * <p>Uso típico:
     * <ul>
     *   <li>Simular posição "Fora 50 m": {@code calcularPontoADistancia(lat, lng, 50f, BEARING_NORTE)}</li>
     *   <li>Simular posição "Fora 1 km": {@code calcularPontoADistancia(lat, lng, 1000f, BEARING_NORTE)}</li>
     * </ul>
     * </p>
     *
     * Fórmula: ponto destino com haversine (esferoide WGS-84 simplificado).
     * Erro máximo: &lt; 0,01 % para distâncias &lt; 10 km.
     *
     * @param centerLat       latitude do centro da zona segura
     * @param centerLng       longitude do centro da zona segura
     * @param distanciaMetros distância desejada em metros (ex.: 50, 100, 300, 1000)
     * @param bearingGraus    direção em graus (0 = Norte, 90 = Leste, 180 = Sul, 270 = Oeste)
     * @return {@code double[]{latitude, longitude}} do ponto calculado
     */
    public static double[] calcularPontoADistancia(double centerLat, double centerLng,
                                                   float distanciaMetros,
                                                   float bearingGraus) {
        final double R = 6_371_000.0; // raio médio da Terra em metros (WGS-84)
        double d       = distanciaMetros / R;
        double bearing = Math.toRadians(bearingGraus);
        double lat1    = Math.toRadians(centerLat);
        double lng1    = Math.toRadians(centerLng);

        double lat2 = Math.asin(
                Math.sin(lat1) * Math.cos(d)
                        + Math.cos(lat1) * Math.sin(d) * Math.cos(bearing));

        double lng2 = lng1 + Math.atan2(
                Math.sin(bearing) * Math.sin(d) * Math.cos(lat1),
                Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));

        return new double[]{ Math.toDegrees(lat2), Math.toDegrees(lng2) };
    }

    /**
     * Retorna coordenadas levemente dentro do raio seguro (deslocamento de 10% do raio).
     * Usado pelo botão "Dentro da Área Segura" para garantir que a posição simulada
     * seja inequivocamente dentro do círculo, independentemente do raio configurado.
     *
     * @param centerLat  latitude do centro
     * @param centerLng  longitude do centro
     * @param raioMetros raio da zona segura em metros
     * @return {@code double[]{latitude, longitude}} a 10% do raio do centro
     */
    public static double[] calcularPontoDentroDoRaio(double centerLat, double centerLng,
                                                     float raioMetros) {
        // 10% do raio garante estar bem dentro, mesmo para raios pequenos (100 m)
        return calcularPontoADistancia(centerLat, centerLng, raioMetros * 0.10f, BEARING_NORTE);
    }
}


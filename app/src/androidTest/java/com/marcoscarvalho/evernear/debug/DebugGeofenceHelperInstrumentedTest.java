package com.marcoscarvalho.evernear.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Testa a lógica matemática usada para decidir "dentro" ou "fora" da zona
 * segura — a mesma lógica usada em produção pelo GeofenceReceiver.
 *
 * Cobre os Cenários 1 e 2 do protocolo de testes (dentro / fora da área).
 *
 * Roda em androidTest porque Location.distanceBetween() é implementado
 * nativamente pelo Android — não existe fora de um dispositivo/emulador real.
 */
@RunWith(AndroidJUnit4.class)
public class DebugGeofenceHelperInstrumentedTest {

    // Ponto de referência: Praça da Sé, São Paulo (qualquer coordenada serve)
    private static final double CENTER_LAT = -23.5505;
    private static final double CENTER_LNG = -46.6333;

    // Margem de erro aceitável do cálculo geodésico, em metros
    private static final float TOLERANCIA_METROS = 1.0f;

    @Test
    public void distanciaEntreMesmoPontoDeveSerZero() {
        float distancia = DebugGeofenceHelper.calcularDistanciaMetros(
                CENTER_LAT, CENTER_LNG, CENTER_LAT, CENTER_LNG);
        assertEquals(0f, distancia, TOLERANCIA_METROS);
    }

    @Test
    public void pontoCalculadoA100mDeveDistarAproximadamente100m() {
        double[] ponto = DebugGeofenceHelper.calcularPontoADistancia(
                CENTER_LAT, CENTER_LNG, 100f, DebugGeofenceHelper.BEARING_NORTE);

        float distanciaMedida = DebugGeofenceHelper.calcularDistanciaMetros(
                CENTER_LAT, CENTER_LNG, ponto[0], ponto[1]);

        assertEquals(100f, distanciaMedida, TOLERANCIA_METROS);
    }

    @Test
    public void pontoDentroDoRaioDeveEstarRealmenteDentro() {
        float raio = 150f; // metros
        double[] ponto = DebugGeofenceHelper.calcularPontoDentroDoRaio(CENTER_LAT, CENTER_LNG, raio);

        float distancia = DebugGeofenceHelper.calcularDistanciaMetros(
                CENTER_LAT, CENTER_LNG, ponto[0], ponto[1]);

        assertTrue("Ponto deveria estar dentro do raio de " + raio
                        + "m, mas está a " + distancia + "m",
                distancia < raio);
    }

    /** Reproduz o Cenário 2 do protocolo: botão "Fora 300 m". */
    @Test
    public void simulacaoForaDe300mDeveSerDetectadaComoForaDoRaioConfigurado() {
        float raioZonaSegura = 200f; // zona configurada pelo cuidador, por exemplo

        double[] pontoFora = DebugGeofenceHelper.calcularPontoADistancia(
                CENTER_LAT, CENTER_LNG, 300f, DebugGeofenceHelper.BEARING_NORTE);

        float distancia = DebugGeofenceHelper.calcularDistanciaMetros(
                CENTER_LAT, CENTER_LNG, pontoFora[0], pontoFora[1]);

        assertTrue("Ponto a 300 m deveria estar fora de um raio de 200 m",
                distancia > raioZonaSegura);
    }

    /** Reproduz o Cenário 1 do protocolo: botão "Dentro da Área Segura". */
    @Test
    public void simulacaoDentroDaAreaNaoDeveSerDetectadaComoForaDoRaio() {
        float raioZonaSegura = 200f;

        double[] pontoDentro = DebugGeofenceHelper.calcularPontoDentroDoRaio(
                CENTER_LAT, CENTER_LNG, raioZonaSegura);

        float distancia = DebugGeofenceHelper.calcularDistanciaMetros(
                CENTER_LAT, CENTER_LNG, pontoDentro[0], pontoDentro[1]);

        assertTrue("Ponto a 10% do raio deveria estar DENTRO da zona segura",
                distancia <= raioZonaSegura);
    }
}

package com.marcoscarvalho.evernear;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Date;

/**
 * Cobre o cenário 9 sem esperar o intervalo real de seis minutos.
 * Testa a mesma regra usada pelo CaregiverAlertService ao decidir se deve
 * solicitar o wake-up do relógio.
 */
@RunWith(AndroidJUnit4.class)
public class CaregiverAlertPresenceInstrumentedTest {

    @Test
    public void ausenciaProlongadaDeDadosDeveSerDetectada() {
        long agora = 1_000_000L;
        Date ultimoBpm = new Date(
                agora - CaregiverAlertService.RELOGIO_MORTO_THRESHOLD_MS - 1);

        assertTrue(CaregiverAlertService.isRelogioSemDados(ultimoBpm, agora));
    }

    @Test
    public void leituraDentroDoLimiteNaoDeveSerConsideradaAusencia() {
        long agora = 1_000_000L;
        Date ultimoBpm = new Date(
                agora - CaregiverAlertService.RELOGIO_MORTO_THRESHOLD_MS);

        assertFalse(CaregiverAlertService.isRelogioSemDados(ultimoBpm, agora));
    }

    @Test
    public void pacienteSemNenhumaLeituraAindaNaoDeveGerarWakeUp() {
        assertFalse(CaregiverAlertService.isRelogioSemDados(null, 1_000_000L));
    }

    @Test
    public void timestampFuturoNaoDeveSerConsideradoSilencio() {
        assertFalse(CaregiverAlertService.isRelogioSemDados(
                new Date(2_000_000L), 1_000_000L));
    }
}
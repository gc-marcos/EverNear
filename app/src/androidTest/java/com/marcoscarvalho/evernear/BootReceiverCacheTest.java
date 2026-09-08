package com.marcoscarvalho.evernear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Valida a parte do BootReceiver que NÃO depende de rede: o cache local
 * (SharedPreferences) usado para decidir qual serviço iniciar quando o
 * Firestore está inacessível logo após o boot.
 *
 * Cobre a parte offline do Cenário 7 do protocolo (reinicialização sem rede).
 *
 * Não testamos aqui o caminho "com Firestore disponível" porque ele depende
 * de rede real ou do Firebase Emulator (ver HeartRateServiceGeofenceExitInstrumentedTest
 * para o padrão de como fazer isso).
 */
@RunWith(AndroidJUnit4.class)
public class BootReceiverCacheTest {

    private static final String PREFS_NAME    = "evernear_prefs";
    private static final String KEY_USER_TIPO = "user_tipo";

    private Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    @After
    public void limparPrefsAposCadaTeste() {
        BootReceiver.limparCacheAoLogout(context());
    }

    @Test
    public void salvarTipoAposLoginDevePersistirNoSharedPreferences() {
        BootReceiver.salvarTipoAposLogin(context(), "paciente");

        SharedPreferences prefs = context().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        assertEquals("paciente", prefs.getString(KEY_USER_TIPO, null));
    }

    @Test
    public void limparCacheAoLogoutDeveRemoverValorSalvo() {
        BootReceiver.salvarTipoAposLogin(context(), "cuidador");
        BootReceiver.limparCacheAoLogout(context());

        SharedPreferences prefs = context().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        assertNull(prefs.getString(KEY_USER_TIPO, null));
    }

    @Test
    public void salvarTipoNuloNaoDeveGravarNada() {
        BootReceiver.limparCacheAoLogout(context()); // garante estado limpo antes do teste
        BootReceiver.salvarTipoAposLogin(context(), null);

        SharedPreferences prefs = context().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        assertNull(prefs.getString(KEY_USER_TIPO, null));
    }
}

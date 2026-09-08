package com.marcoscarvalho.evernear;

import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.location.Location;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ServiceTestRule;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * Teste de ponta a ponta: "saída de zona segura → alerta gravado no Firestore".
 * Cobre os Cenários 2, 3 e 4 do protocolo em uma única execução automatizada.
 *
 * ⚠️ PRÉ-REQUISITOS OBRIGATÓRIOS — leia o guia de uso antes de rodar:
 *  1. Firebase Local Emulator Suite rodando na sua máquina
 *     (comando: firebase emulators:start).
 *  2. Um usuário de teste "paciente" e um "cuidador" vinculado, já semeados
 *     no emulador (Auth + Firestore) — ver seção "Preparando o Emulator"
 *     no guia de uso.
 *  3. O emulador do Android/dispositivo precisa alcançar sua máquina:
 *     10.0.2.2 é o endereço padrão do host quando se usa o Emulador do
 *     Android Studio. Em dispositivo físico, troque pelo IP da sua máquina
 *     na mesma rede Wi-Fi.
 *
 * 🚫 NUNCA rode este teste apontando para o projeto Firebase de PRODUÇÃO.
 * Ele grava documentos reais em "alerts" e pode dar a falsa impressão de
 * que um alerta de emergência de verdade foi disparado.
 */
@RunWith(AndroidJUnit4.class)
public class HeartRateServiceGeofenceExitInstrumentedTest {

    // Ajuste para o IP da sua máquina se estiver usando dispositivo físico
    private static final String EMULATOR_HOST = "10.0.2.2";

    // Credenciais do usuário de teste semeado no Auth Emulator
    private static final String EMAIL_PACIENTE_TESTE = "paciente.teste@evernear.dev";
    private static final String SENHA_PACIENTE_TESTE = "senha123";

    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();

    @Before
    public void apontarParaOEmulador() {
        // Idempotente por padrão do SDK — chamar mais de uma vez lança erro,
        // então isso só deve rodar uma vez por processo de teste.
        FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, 9099);
        FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, 8080);
    }

    @Test
    public void saidaDeZonaSimuladaDeveGerarAlertaNoFirestore() throws Exception {
        Tasks.await(
                FirebaseAuth.getInstance().signInWithEmailAndPassword(
                        EMAIL_PACIENTE_TESTE, SENHA_PACIENTE_TESTE),
                10, TimeUnit.SECONDS);

        Location localizacaoSimulada = new Location("DEBUG");
        localizacaoSimulada.setLatitude(-23.5505);
        localizacaoSimulada.setLongitude(-46.6333);

        Intent intent = new Intent(
                ApplicationProvider.getApplicationContext(), HeartRateService.class);
        intent.setAction(HeartRateService.ACTION_DEBUG_GEOFENCE_EXIT);
        intent.putExtra(HeartRateService.EXTRA_DEBUG_LOCATION, localizacaoSimulada);

        serviceRule.startService(intent);

        // Dá tempo para o serviço processar e gravar no Firestore Emulator.
        // Se o teste falhar por timing em máquinas mais lentas, aumente aqui.
        Thread.sleep(4000);

        QuerySnapshot alertas = Tasks.await(
                FirebaseFirestore.getInstance().collection("alerts").get(),
                10, TimeUnit.SECONDS);

        assertTrue("Esperava ao menos um alerta gerado após a saída de zona simulada",
                alertas.size() > 0);
    }
}

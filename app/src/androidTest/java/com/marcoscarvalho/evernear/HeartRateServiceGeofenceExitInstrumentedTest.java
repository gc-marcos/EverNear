package com.marcoscarvalho.evernear;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.location.Location;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;
import androidx.test.uiautomator.UiDevice;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Teste de ponta a ponta: "saída de zona segura → perda de conexão → reconexão → alerta gravado no Firestore".
 * Cobre os Cenários 2, 3 e 4 do protocolo em uma única execução automatizada.
 */
@RunWith(AndroidJUnit4.class)
public class HeartRateServiceGeofenceExitInstrumentedTest {

    private static final String EMULATOR_HOST = "192.168.17.198";
    private static final String EMAIL_PACIENTE_TESTE = "margarete@gmail.com";
    private static final String SENHA_PACIENTE_TESTE = "123456";

    private static boolean isEmulatorInitialized = false;
    private UiDevice device;

    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();

    @Before
    public void setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        // Garantia de inicialização única do emulador por processo
        if (!isEmulatorInitialized) {
            try {
                FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, 9099);
                FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, 8080);
                isEmulatorInitialized = true;
            } catch (IllegalStateException e) {
                isEmulatorInitialized = true;
            }
        }
    }

    @Test
    public void saidaDeZonaComPerdaERestauracaoDeConexaoDeveGravarAlerta() throws Exception {
        // 1. Autenticação Síncrona no Auth Emulator
        try {
            Tasks.await(
                    FirebaseAuth.getInstance().signInWithEmailAndPassword(
                            EMAIL_PACIENTE_TESTE, SENHA_PACIENTE_TESTE),
                    30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Assert.fail("A Task de autenticação estourou o tempo de resposta (Timeout).");
        } catch (ExecutionException e) {
            Assert.fail("A autenticação falhou durante a execução: " + e.getCause().getMessage());
        }

        Context appContext = ApplicationProvider.getApplicationContext();

        // 2. Aquecimento: cria o serviço ANTES de qualquer evento de geofence,
        // para que onCreate() dispare carregarDadosPaciente() e o listener
        // assíncrono do Firestore tenha tempo de popular uidPaciente e
        // cuidadoresVinculados. Sem isso, o evento de geofence chega antes
        // dos dados existirem e é descartado silenciosamente
        // (confirmado em log: listener responde ~1.5s após onCreate()).
        Intent warmupIntent = new Intent(appContext, HeartRateService.class);
        appContext.startService(warmupIntent);
        Thread.sleep(3000); // aguarda addSnapshotListener responder

        // 3. Simulação de Perda de Conexão via ADB (Desativa Wi-Fi e Dados Móveis)
        device.executeShellCommand("svc wifi disable");
        device.executeShellCommand("svc data disable");
        Thread.sleep(3000); // Aguarda a alteração do estado da rede no sistema

        // 4. Preparação da localização simulada (Zona Externa)
        Location localizacaoSimulada = new Location("DEBUG");
        localizacaoSimulada.setLatitude(-23.65376);
        localizacaoSimulada.setLongitude(-46.45246);

        Intent intent = new Intent(appContext, HeartRateService.class);
        intent.setAction(HeartRateService.ACTION_DEBUG_GEOFENCE_EXIT);
        intent.putExtra(HeartRateService.EXTRA_DEBUG_LOCATION, localizacaoSimulada);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Disparo do evento de geofence, só depois dos dados já carregados
        appContext.startService(intent);

        // 5. Aguarda um período offline simulando a tentativa de envio sem rede
        Thread.sleep(3000);

        // 6. Restauração da Conexão via ADB
        device.executeShellCommand("svc wifi enable");
        device.executeShellCommand("svc data enable");
        Thread.sleep(3000); // Aguarda o restabelecimento do canal de comunicação com o Firestore

        // 7. Polling para verificar a persistência do alerta após o reestabelecimento da rede
        boolean documentoEncontrado = false;
        int tentativas = 0;
        int maxTentativas = 15;

        while (!documentoEncontrado && tentativas < maxTentativas) {
            Thread.sleep(3000);
            try {
                QuerySnapshot alertas = Tasks.await(
                        FirebaseFirestore.getInstance().collection("alerts").get(),
                        5, TimeUnit.SECONDS);

                if (alertas != null && !alertas.isEmpty()) {
                    documentoEncontrado = true;
                }
            } catch (Exception e) {
                // Erro esperado caso o Firestore ainda esteja reestabelecendo o socket de conexão
            }
            tentativas++;
        }

        // 8. Validação final
        assertTrue("Esperava ao menos um alerta no Firestore após a reconexão da rede", documentoEncontrado);
    }

    @After
    public void tearDown() {
        try {
            // Garante a reativação da rede em caso de falhas para não afetar outros testes
            if (device != null) {
                device.executeShellCommand("svc wifi enable");
                device.executeShellCommand("svc data enable");
            }

            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            context.stopService(new Intent(context, HeartRateService.class));
            FirebaseAuth.getInstance().signOut();
        } catch (Exception e) {
            // Ignora falhas na limpeza do estado
        }
    }
}
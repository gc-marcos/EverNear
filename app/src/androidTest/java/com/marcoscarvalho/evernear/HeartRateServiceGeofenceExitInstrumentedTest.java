package com.marcoscarvalho.evernear;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.location.Location;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Teste de ponta a ponta: "saída de zona segura → perda de conexão → reconexão → alerta gravado no Firestore".
 * Cobre os Cenários 2, 3 e 4 do protocolo em uma única execução automatizada.
 */
@RunWith(AndroidJUnit4.class)
public class HeartRateServiceGeofenceExitInstrumentedTest {

    private FirebaseFirestore firestore;
    private String pacienteId;
    private String cuidadorId;

    @Before
    public void setUp() throws Exception {
        FirebaseEmulatorTestSupport.configure();
        firestore = FirebaseFirestore.getInstance();
        Tasks.await(firestore.enableNetwork(),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
        FirebaseUser paciente = FirebaseEmulatorTestSupport.createTestUser();
        pacienteId = paciente.getUid();
        cuidadorId = FirebaseEmulatorTestSupport.id("cuidador");

        Map<String, Object> pacienteData = new HashMap<>();
        pacienteData.put("nome", "Paciente instrumentado");
        pacienteData.put("tipo", "paciente");
        pacienteData.put("cuidadoresVinculados", Arrays.asList(cuidadorId));
        Tasks.await(firestore.collection("users").document(pacienteId).set(pacienteData),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
    }

    @Test
    public void saidaDeZonaComPerdaERestauracaoDeConexaoDeveGravarAlerta() throws Exception {
        Context appContext = ApplicationProvider.getApplicationContext();

        // O serviço é aquecido com o documento do paciente já no cache local.
        FirebaseEmulatorTestSupport.iniciarHeartRateService(appContext);
        Thread.sleep(2000);

        // Simula perda de conexão no próprio SDK (determinístico, sem depender de rede).
        Tasks.await(firestore.disableNetwork(),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);

        Location localizacaoSimulada = new Location("DEBUG");
        localizacaoSimulada.setLatitude(-23.65376);
        localizacaoSimulada.setLongitude(-46.45246);
        localizacaoSimulada.setAccuracy(10f);

        Intent intent = new Intent(appContext, HeartRateService.class);
        intent.setAction(HeartRateService.ACTION_DEBUG_GEOFENCE_EXIT);
        intent.putExtra(HeartRateService.EXTRA_DEBUG_LOCATION, localizacaoSimulada);

        // startForegroundService exige startForeground() no onStartCommand (já corrigido no serviço).
        ContextCompat.startForegroundService(appContext, intent);

        // Aguarda a escrita ser aceita pela fila local e restaura a conexão.
        Thread.sleep(1000);
        Tasks.await(firestore.enableNetwork(),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);

        QuerySnapshot alertas = null;
        for (int tentativas = 0; tentativas < 20; tentativas++) {
            Thread.sleep(500);
            alertas = Tasks.await(
                    firestore.collection("alerts")
                            .whereEqualTo(FirebaseHelper.Fields.PACIENTE_ID, pacienteId)
                            .whereEqualTo(FirebaseHelper.Fields.TIPO_ALERTA, "SAIDA_ZONA")
                            .get(),
                    FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
            if (alertas != null && !alertas.isEmpty()) break;
        }

        assertTrue("Esperava ao menos um alerta no Firestore após a reconexão da rede",
                alertas != null && !alertas.isEmpty());
        assertNotNull(alertas.getDocuments().get(0)
                .getDouble(FirebaseHelper.Fields.LATITUDE));
        assertNotNull(alertas.getDocuments().get(0)
                .getDouble(FirebaseHelper.Fields.LONGITUDE));
    }

    @After
    public void tearDown() throws Exception {
        if (firestore != null) {
            try {
                Tasks.await(firestore.enableNetwork(),
                        FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (Exception ignored) { }
        }
        Context context = ApplicationProvider.getApplicationContext();
        FirebaseEmulatorTestSupport.pararHeartRateService(context);
        FirebaseAuth.getInstance().signOut();
    }
}

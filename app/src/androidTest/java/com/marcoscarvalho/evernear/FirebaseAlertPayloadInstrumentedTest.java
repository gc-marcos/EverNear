package com.marcoscarvalho.evernear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cobre isoladamente os cenários 3 e 4:
 * geração do alerta e localização associada ao evento.
 */
@RunWith(AndroidJUnit4.class)
public class FirebaseAlertPayloadInstrumentedTest {

    private FirebaseFirestore firestore;
    private String pacienteId;

    @Before
    public void setUp() throws Exception {
        FirebaseEmulatorTestSupport.configure();
        firestore = FirebaseFirestore.getInstance();
        Tasks.await(firestore.enableNetwork(),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
        FirebaseUser user = FirebaseEmulatorTestSupport.createTestUser();
        pacienteId = user.getUid();
    }

    @Test
    public void alertaDeveSerGeradoComTipoBpmCuidadorEPaciente() throws Exception {
        String cuidadorId = FirebaseEmulatorTestSupport.id("cuidador");
        String alertaId = enviarAlerta(pacienteId, cuidadorId, null);

        DocumentSnapshot alerta = Tasks.await(
                firestore.collection("alerts").document(alertaId).get(),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);

        assertTrue("O documento do alerta deveria existir", alerta.exists());
        assertEquals(pacienteId, alerta.getString(FirebaseHelper.Fields.PACIENTE_ID));
        assertEquals(cuidadorId, alerta.getString(FirebaseHelper.Fields.CUIDADOR_ID));
        assertEquals("HIGH", alerta.getString(FirebaseHelper.Fields.TIPO_ALERTA));
        assertEquals(Long.valueOf(145), alerta.getLong(FirebaseHelper.Fields.BPM));
        assertFalse(Boolean.TRUE.equals(
                alerta.getBoolean(FirebaseHelper.Fields.ACKNOWLEDGED)));
    }

    @Test
    public void alertaDeveConterLocalizacaoDoEvento() throws Exception {
        String cuidadorId = FirebaseEmulatorTestSupport.id("cuidador");
        Location location = new Location("instrumented-test");
        location.setLatitude(-23.550520);
        location.setLongitude(-46.633308);
        location.setAccuracy(8.5f);

        String alertaId = enviarAlerta(pacienteId, cuidadorId, location);
        DocumentSnapshot alerta = Tasks.await(
                firestore.collection("alerts").document(alertaId).get(),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);

        assertNotNull(alerta.get(FirebaseHelper.Fields.LATITUDE));
        assertNotNull(alerta.get(FirebaseHelper.Fields.LONGITUDE));
        assertNotNull(alerta.get(FirebaseHelper.Fields.ACCURACY));
        assertEquals(location.getLatitude(),
                alerta.getDouble(FirebaseHelper.Fields.LATITUDE), 0.000001);
        assertEquals(location.getLongitude(),
                alerta.getDouble(FirebaseHelper.Fields.LONGITUDE), 0.000001);
        assertEquals((double) location.getAccuracy(),
                alerta.getDouble(FirebaseHelper.Fields.ACCURACY), 0.000001);
    }

    @Test
    public void localizacaoDoEventoDeveSerPersistidaNoPerfilDoPaciente() throws Exception {
        Location location = new Location("instrumented-test");
        location.setLatitude(-23.561000);
        location.setLongitude(-46.656000);
        location.setAccuracy(12f);

        Tasks.await(firestore.collection("users").document(pacienteId).set(
                        java.util.Collections.singletonMap("tipo", "paciente")),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
        FirebaseHelper.salvarLocalizacaoEmergencia(pacienteId, location, null);

        DocumentSnapshot paciente = aguardarCampoDeLocalizacao(pacienteId);
        assertEquals(location.getLatitude(),
                paciente.getDouble(FirebaseHelper.Fields.LATITUDE), 0.000001);
        assertEquals(location.getLongitude(),
                paciente.getDouble(FirebaseHelper.Fields.LONGITUDE), 0.000001);
        assertNotNull(paciente.get(FirebaseHelper.Fields.LOCATION_TIMESTAMP));
    }

    private String enviarAlerta(String pacienteId, String cuidadorId,
                                Location location) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        FirebaseHelper.enviarAlerta(
                pacienteId, "Paciente de teste", cuidadorId, 145, "HIGH",
                0, 50, 120, location,
                new FirebaseHelper.Callback<String>() {
                    @Override public void onResult(String value) {
                        id.set(value);
                        latch.countDown();
                    }
                    @Override public void onError(Exception e) {
                        error.set(e);
                        latch.countDown();
                    }
                });

        assertTrue("O Firestore não respondeu em "
                        + FirebaseEmulatorTestSupport.firestoreEndpoint()
                        + "; confira host, Wi-Fi/firewall e se o Emulator está ativo",
                latch.await(FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(
                    "O Firestore rejeitou a gravação do alerta via "
                            + FirebaseEmulatorTestSupport.firestoreEndpoint(),
                    error.get());
        }
        assertNotNull("O Firestore deveria retornar o ID do alerta", id.get());
        return id.get();
    }

    private DocumentSnapshot aguardarCampoDeLocalizacao(String pacienteId) throws Exception {
        for (int tentativa = 0; tentativa < 10; tentativa++) {
            DocumentSnapshot snapshot = Tasks.await(
                    firestore.collection("users").document(pacienteId).get(),
                    FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
            if (snapshot.get(FirebaseHelper.Fields.LATITUDE) != null) return snapshot;
            Thread.sleep(100);
        }
        return Tasks.await(
                firestore.collection("users").document(pacienteId).get(),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
    }

    @After
    public void tearDown() throws Exception {
        FirebaseAuth.getInstance().signOut();
        if (firestore != null) {
            Tasks.await(firestore.enableNetwork(),
                    FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
        }
    }
}

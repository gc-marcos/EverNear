package com.marcoscarvalho.evernear;

import static org.junit.Assert.assertNotNull;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cobre os cenários 5 e 6 no nível do contrato do serviço:
 * a execução continua protegida quando a tela é desligada e quando a
 * aplicação é removida dos recentes.
 *
 * O Android entrega essas condições ao serviço; o teste verifica o efeito
 * observável e durável, que é a existência do alarme de reinício.
 */
@RunWith(AndroidJUnit4.class)
public class HeartRateServiceLifecycleInstrumentedTest {

    private static final int WATCHDOG_REQUEST_CODE = 100;
    private static final int TASK_REMOVED_REQUEST_CODE = 1;

    private Context context;
    private AlarmManager alarmManager;
    private FirebaseFirestore firestore;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        cancelarWatchdog();
        cancelarReinicioDosRecentes();

        FirebaseEmulatorTestSupport.configure();
        firestore = FirebaseFirestore.getInstance();
        Tasks.await(firestore.enableNetwork(),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
        FirebaseUser user = FirebaseEmulatorTestSupport.createTestUser();
        Map<String, Object> profile = new HashMap<>();
        profile.put("tipo", "paciente");
        profile.put("cuidadoresVinculados", Collections.emptyList());
        Tasks.await(firestore.collection("users").document(user.getUid()).set(
                        profile),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
    }

    @Test
    public void telaDesligadaDeveReagendarWatchdogDoServico() throws Exception {
        UiDevice device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());
        try {
            FirebaseEmulatorTestSupport.iniciarHeartRateService(context);
            cancelarWatchdog();

            // ACTION_SCREEN_OFF é um broadcast protegido. Simula a ação real
            // do usuário pela API de automação, em vez de enviá-lo pelo app.
            device.sleep();
            long limite = android.os.SystemClock.elapsedRealtime() + 5_000L;
            while (obterWatchdog(false) == null
                    && android.os.SystemClock.elapsedRealtime() < limite) {
                Thread.sleep(100);
            }

            assertNotNull("Ao apagar a tela, o receiver deve reagendar o watchdog",
                    obterWatchdog(false));
        } finally {
            // Deixa o aparelho desbloqueado mesmo quando a asserção falhar.
            if (!device.isScreenOn()) {
                device.wakeUp();
            }
        }
    }

    @Test
    public void appRemovidoDosRecentesDeveAgendarReinicio() throws Exception {
        FirebaseEmulatorTestSupport.iniciarHeartRateService(context);
        HeartRateService service = HeartRateService.getInstance();
        assertNotNull("O HeartRateService deveria estar criado", service);

        service.onTaskRemoved(new Intent(context, HeartRateService.class));

        assertNotNull("Remover o app dos recentes deve agendar reinício",
                obterReinicioDosRecentes(false));
    }

    @After
    public void tearDown() throws Exception {
        cancelarWatchdog();
        cancelarReinicioDosRecentes();
        FirebaseEmulatorTestSupport.pararHeartRateService(context);
        FirebaseAuth.getInstance().signOut();
    }

    private PendingIntent obterWatchdog(boolean criar) {
        int flags = PendingIntent.FLAG_IMMUTABLE
                | (criar ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE);
        return PendingIntent.getForegroundService(
                context, WATCHDOG_REQUEST_CODE,
                new Intent(context, HeartRateService.class), flags);
    }

    private PendingIntent obterReinicioDosRecentes(boolean criar) {
        int flags = PendingIntent.FLAG_IMMUTABLE
                | (criar ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE);
        return PendingIntent.getService(
                context, TASK_REMOVED_REQUEST_CODE,
                new Intent(context, HeartRateService.class), flags);
    }

    private void cancelarWatchdog() {
        PendingIntent pendingIntent = obterWatchdog(false);
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }
    }

    private void cancelarReinicioDosRecentes() {
        PendingIntent pendingIntent = obterReinicioDosRecentes(false);
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }
    }
}

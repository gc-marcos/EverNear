package com.marcoscarvalho.evernear;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Completa a cobertura do cenário 7 no caminho com Firestore disponível:
 * após o boot, o tipo remoto é salvo no cache antes de iniciar o serviço.
 */
@RunWith(AndroidJUnit4.class)
public class BootReceiverFirestoreInstrumentedTest {

    private static final String PREFS_NAME = "evernear_prefs";
    private static final String KEY_USER_TIPO = "user_tipo";

    private Context context;
    private FirebaseFirestore firestore;

    @Before
    public void setUp() throws Exception {
        FirebaseEmulatorTestSupport.configure();
        context = ApplicationProvider.getApplicationContext();
        firestore = FirebaseFirestore.getInstance();
        Tasks.await(firestore.enableNetwork(),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
        FirebaseUser user = FirebaseEmulatorTestSupport.createTestUser();

        Tasks.await(firestore.collection("users").document(user.getUid()).set(
                        Collections.singletonMap("tipo", "cuidador")),
                FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
        BootReceiver.limparCacheAoLogout(context);
    }

    @Test
    public void bootComFirestoreDisponivelDeveAtualizarCacheRemoto() throws Exception {
        new BootReceiver().onReceive(context,
                new Intent(Intent.ACTION_BOOT_COMPLETED));

        SharedPreferences prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
        for (int tentativa = 0; tentativa < 20; tentativa++) {
            if ("cuidador".equals(prefs.getString(KEY_USER_TIPO, null))) break;
            Thread.sleep(250);
        }

        // Respiro para o serviço iniciado pelo receiver chegar ao startForeground()
        // antes de o @After pará-lo (evita a corrida do Android 8/9).
        Thread.sleep(1500);

        assertEquals("cuidador", prefs.getString(KEY_USER_TIPO, null));
    }

    @After
    public void tearDown() throws Exception {
        if (context != null) {
            BootReceiver.limparCacheAoLogout(context);
            context.stopService(new Intent(context, CaregiverAlertService.class));
            FirebaseEmulatorTestSupport.pararHeartRateService(context);
        }
        FirebaseAuth.getInstance().signOut();
        if (firestore != null) {
            Tasks.await(firestore.enableNetwork(),
                    FirebaseEmulatorTestSupport.timeoutSeconds(), TimeUnit.SECONDS);
        }
    }
}
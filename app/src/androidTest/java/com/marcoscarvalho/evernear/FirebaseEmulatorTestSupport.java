package com.marcoscarvalho.evernear;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.android.gms.tasks.Tasks;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Infraestrutura compartilhada pelos testes instrumentados que usam os
 * emuladores locais do Firebase.
 *
 * O host pode ser informado como argumento do instrumentation. Em aparelho
 * físico, use o IP da máquina na mesma rede Wi-Fi; 10.0.2.2 só funciona no
 * Android Emulator oficial. Auth e Firestore precisam aceitar conexões na
 * rede local (firebase.json usa host 0.0.0.0).
 *
 * Exemplo de argumento do instrumentation:
 * -e firebaseEmulatorHost 10.0.2.2
 * Em aparelho físico, troque pelo IP LAN da máquina que executa os emuladores.
 * Exemplo:
 * adb shell am instrument -w -e firebaseEmulatorHost 192.168.1.10
 *   com.marcoscarvalho.evernear.test/androidx.test.runner.AndroidJUnitRunner
 */
final class FirebaseEmulatorTestSupport {

    private static final String TAG = "FirebaseEmulatorTests";
    private static final int AUTH_PORT = 9099;
    private static final int FIRESTORE_PORT = 8080;
    private static final long TIMEOUT_SECONDS = 30L;
    private static boolean configured;
    private static String configuredHost;

    private FirebaseEmulatorTestSupport() {}

    static synchronized void configure() {
        if (configured) return;

        String host = InstrumentationRegistry.getArguments()
                .getString("firebaseEmulatorHost", "10.0.2.2");
        configuredHost = host;
        try {
            FirebaseAuth.getInstance().useEmulator(host, AUTH_PORT);
            FirebaseFirestore.getInstance().useEmulator(host, FIRESTORE_PORT);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Não foi possível configurar os SDKs Firebase para usar os emuladores em "
                            + host + ":" + AUTH_PORT + " (Auth) e "
                            + host + ":" + FIRESTORE_PORT + " (Firestore). "
                            + "Confirme que configure() é chamado antes de qualquer acesso "
                            + "ao Firebase; o teste não deve continuar contra produção.",
                    e);
        }
        configured = true;
        Log.i(TAG, "Usando Firebase Emulator Auth=" + host + ":" + AUTH_PORT
                + ", Firestore=" + host + ":" + FIRESTORE_PORT);
    }

    static FirebaseUser createTestUser() throws Exception {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String email = "instrumented-" + suffix + "@evernear.test";
        String password = "test-password-123";

        try {
            FirebaseUser user = Tasks.await(
                    auth.createUserWithEmailAndPassword(email, password),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS).getUser();
            if (user == null) {
                throw new AssertionError("Firebase Auth retornou usuário nulo ao criar conta de teste.");
            }
            return user;
        } catch (TimeoutException e) {
            throw new AssertionError(
                    "Timeout ao conectar ao Firebase Auth Emulator em "
                            + authEndpoint() + ". Em aparelho físico, informe "
                            + "-e firebaseEmulatorHost <IP-LAN-DO-COMPUTADOR> e confirme "
                            + "que o aparelho alcança a porta " + AUTH_PORT + ".",
                    e);
        } catch (Exception e) {
            throw new AssertionError(
                    "Não foi possível criar usuário de teste no Firebase Auth Emulator em "
                            + authEndpoint() + ". Verifique o host, a porta e os logs do emulator.",
                    e);
        }
    }

    static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    static long timeoutSeconds() {
        return TIMEOUT_SECONDS;
    }

    static String firestoreEndpoint() {
        return (configuredHost != null ? configuredHost : "10.0.2.2")
                + ":" + FIRESTORE_PORT;
    }

    private static String authEndpoint() {
        return (configuredHost != null ? configuredHost : "10.0.2.2")
                + ":" + AUTH_PORT;
    }

    /** Inicia o HeartRateService como o app real faz e espera ele subir. */
    static void iniciarHeartRateService(Context ctx) throws Exception {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, HeartRateService.class));
        long limite = SystemClock.elapsedRealtime() + 5000;
        while (HeartRateService.getInstance() == null
                && SystemClock.elapsedRealtime() < limite) {
            Thread.sleep(100);
        }
        if (HeartRateService.getInstance() == null) {
            throw new AssertionError("HeartRateService não subiu em 5s");
        }
        // getInstance() é preenchido no início de onCreate(); aguarda o método
        // registrar receivers, alarme e notificação antes de disparar o cenário.
        Thread.sleep(500);
    }

    /** Para o serviço e espera ele encerrar, para não vazar para o próximo teste. */
    static void pararHeartRateService(Context ctx) throws Exception {
        ctx.stopService(new Intent(ctx, HeartRateService.class));
        long limite = SystemClock.elapsedRealtime() + 5000;
        while (HeartRateService.getInstance() != null
                && SystemClock.elapsedRealtime() < limite) {
            Thread.sleep(100);
        }
    }

    /** Espera o HeartRateService existir e já ter chamado startForeground(). */
    static void aguardarHeartRateServiceSubir() throws Exception {
        long limite = SystemClock.elapsedRealtime() + 10_000;
        while (HeartRateService.getInstance() == null
                && SystemClock.elapsedRealtime() < limite) {
            Thread.sleep(100);
        }
        if (HeartRateService.getInstance() == null) {
            throw new AssertionError("HeartRateService não subiu em 10s");
        }
        // O onCreate chama startForeground() no final, então damos um respiro
        // para o onStartCommand também terminar antes de qualquer stopService.
        Thread.sleep(500);
    }
}
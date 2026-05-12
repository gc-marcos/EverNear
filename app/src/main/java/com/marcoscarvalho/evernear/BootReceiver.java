package com.marcoscarvalho.evernear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;

/**
 * Reinicia o HeartRateService automaticamente após o dispositivo ser reiniciado.
 *
 * Requer permissão RECEIVE_BOOT_COMPLETED no manifest.
 * Só inicia o serviço se o usuário estiver autenticado (evita iniciar para usuários não logados).
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !"android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
            return;
        }

        // Só inicia o monitor se o usuário já estiver logado
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.d(TAG, "Boot detectado mas sem usuário autenticado — serviço não iniciado");
            return;
        }

        Log.d(TAG, "Boot detectado — reiniciando HeartRateService");
        Intent serviceIntent = new Intent(context, HeartRateService.class);
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}

package com.marcoscarvalho.evernear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * Reinicia os serviços de segundo plano automaticamente após o dispositivo ser reiniciado.
 *
 * - Paciente (smartwatch): reinicia HeartRateService
 * - Cuidador (celular): reinicia CaregiverAlertService
 *
 * O tipo de usuário é lido do Firestore para determinar qual serviço iniciar.
 * Requer permissão RECEIVE_BOOT_COMPLETED no manifest.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        // Só inicia se o usuário já estiver logado
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.d(TAG, "Boot detectado mas sem usuário autenticado — nenhum serviço iniciado");
            return;
        }

        String uid = FirebaseAuth.getInstance().getUid();
        Log.d(TAG, "Boot detectado — verificando tipo do usuário para reiniciar serviço");

        // Lê o tipo do usuário no Firestore para decidir qual serviço iniciar
        FirebaseFirestore.getInstance().collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (doc == null || !doc.exists()) {
                        Log.w(TAG, "Documento do usuário não encontrado no Firestore");
                        return;
                    }
                    String tipo = doc.getString("tipo");
                    if ("paciente".equals(tipo) || "patient".equals(tipo)) {
                        Log.d(TAG, "Boot: reiniciando HeartRateService (paciente)");
                        ContextCompat.startForegroundService(context,
                                new Intent(context, HeartRateService.class));
                    } else {
                        Log.d(TAG, "Boot: reiniciando CaregiverAlertService (cuidador)");
                        ContextCompat.startForegroundService(context,
                                new Intent(context, CaregiverAlertService.class));
                    }
                })
                .addOnFailureListener(e -> {
                    // Sem Firestore (sem rede após boot), tenta iniciar ambos como fallback
                    Log.w(TAG, "Sem acesso ao Firestore no boot — iniciando ambos os serviços");
                    ContextCompat.startForegroundService(context,
                            new Intent(context, HeartRateService.class));
                    ContextCompat.startForegroundService(context,
                            new Intent(context, CaregiverAlertService.class));
                });
    }
}

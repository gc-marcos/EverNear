package com.marcoscarvalho.evernear;

import android.app.Application;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;

/**
 * Classe Application — executada antes de qualquer Activity ou Service.
 *
 * Habilita persistência offline do Firestore:
 *  - Alertas criados sem rede ficam em cache local e são enviados
 *    automaticamente quando a conectividade for restaurada.
 *  - Fundamental para smartwatches que perdem conexão Bluetooth momentaneamente.
 */
public class EverNearApplication extends Application {

    private static final String TAG = "EverNearApp";

    @Override
    public void onCreate() {
        super.onCreate();
        configurarFirestore();
    }

    private void configurarFirestore() {
        try {
            FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build();

            FirebaseFirestore.getInstance().setFirestoreSettings(settings);
            Log.d(TAG, "Firestore: persistência offline habilitada");
        } catch (Exception e) {
            Log.w(TAG, "Erro ao configurar Firestore: " + e.getMessage());
        }
    }
}

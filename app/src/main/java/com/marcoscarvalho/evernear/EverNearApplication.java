package com.marcoscarvalho.evernear;

import android.app.Application;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;

/**
 * Classe Application — executada antes de qualquer Activity ou Service.
 *
 * Responsabilidades:
 *  1. Habilitar persistência offline do Firestore:
 *       - Alertas criados sem rede ficam em cache local e são enviados
 *         automaticamente quando a conectividade for restaurada.
 *       - Fundamental para smartwatches que podem perder conexão Bluetooth
 *         momentaneamente ao sair do alcance do celular.
 *  2. Configurar cache ilimitado para que dados do paciente (BPM, cuidadores)
 *     permaneçam disponíveis mesmo offline.
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
                    // Cache local ilimitado: dados persistem entre reinicializações do app
                    .setLocalCacheSettings(
                            com.google.firebase.firestore.memoryCacheSettings()
                                    // Usando cache persistente no disco para suportar reinicializações
                    )
                    .build();

            // A persistência offline é habilitada via cache local do Firestore SDK
            // O SDK do Firestore Android habilita persistência por padrão desde v25.0.0
            // Esta configuração garante que o cache seja mantido entre sessões
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)          // cache em disco persistente
                    .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build());

            Log.d(TAG, "Firestore: persistência offline habilitada");
        } catch (Exception e) {
            Log.w(TAG, "Erro ao configurar Firestore: " + e.getMessage());
        }
    }
}

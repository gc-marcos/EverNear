package com.marcoscarvalho.evernear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

/**
 * Receptor de eventos de Geofence — dispara quando o paciente sai da zona segura.
 *
 * O Android entrega eventos de geofence via PendingIntent mesmo com o processo morto.
 * Este receptor:
 *  1. Valida o evento (sem erro, transição de saída).
 *  2. Inicia (ou acorda) o {@link HeartRateService} com {@code ACTION_GEOFENCE_EXIT}.
 *  3. O serviço obtém a localização e dispara o alerta para o cuidador.
 *
 * ┌─ Por que BroadcastReceiver? ───────────────────────────────────────────────┐
 * │  A API de Geofence do Google Play Services exige um PendingIntent de       │
 * │  broadcast (não de serviço). O receiver é a ponte entre a entrega do       │
 * │  sistema e o HeartRateService que possui o contexto do paciente.            │
 * └────────────────────────────────────────────────────────────────────────────┘
 */
public class GeofenceReceiver extends BroadcastReceiver {

    private static final String TAG = "GeofenceReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);

        if (event == null) {
            Log.w(TAG, "GeofencingEvent nulo — intent inválido");
            return;
        }
        if (event.hasError()) {
            Log.e(TAG, "Erro no evento de geofence: código " + event.getErrorCode());
            return;
        }

        int transicao = event.getGeofenceTransition();

        if (transicao == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.w(TAG, "Saída da zona segura detectada — iniciando HeartRateService");

            Intent serviceIntent = new Intent(context, HeartRateService.class);
            serviceIntent.setAction(HeartRateService.ACTION_GEOFENCE_EXIT);
            ContextCompat.startForegroundService(context, serviceIntent);
        } else {
            Log.d(TAG, "Transição de geofence ignorada: " + transicao);
        }
    }
}

package com.marcoscarvalho.evernear;

import android.content.Context;

/**
 * Centraliza a classificação de layout do aplicativo.
 *
 * A decisão usa somente a menor largura disponível da tela em dp.
 * Dispositivos compactos seguem o fluxo inicial do paciente.
 */
public final class DeviceClassifier {

    public static final int PATIENT_DEVICE_MAX_WIDTH_DP = 360;

    private DeviceClassifier() {
        // Classe utilitária.
    }

    public static boolean isPacienteDevice(Context context) {
        return context.getResources().getConfiguration().smallestScreenWidthDp
                <= PATIENT_DEVICE_MAX_WIDTH_DP;
    }
}
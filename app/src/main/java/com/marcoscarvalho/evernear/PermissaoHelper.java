package com.marcoscarvalho.evernear;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

/**
 * Centraliza a solicitação de permissões críticas para o funcionamento do EverNear.
 *
 * Uso:
 *   PermissaoHelper.solicitarIsencaoBateria(this);
 *
 * Por que isso é necessário?
 *   Android restringe processos em segundo plano para economizar bateria.
 *   Sem a isenção, fabricantes como Samsung, Huawei e Xiaomi matam o
 *   CaregiverAlertService agressivamente mesmo sendo um foreground service.
 *   Com a isenção, o serviço sobrevive mesmo com o app fechado e o celular
 *   em repouso, garantindo recebimento de alertas.
 *
 * A solicitação é exibida apenas uma vez (controlado por SharedPreferences).
 */
public class PermissaoHelper {

    private static final String TAG       = "PermissaoHelper";
    private static final String PREFS     = "evernear_permissoes";
    private static final String KEY_BATERIA = "bateria_solicitada";

    /**
     * Exibe diálogo pedindo isenção de otimização de bateria.
     * Mostra apenas uma vez por instalação do app.
     * Seguro de chamar múltiplas vezes — verifica o flag interno antes de exibir.
     */
    public static void solicitarIsencaoBateria(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return; // API 23+

        // Verifica se o app já está isento
        PowerManager pm = (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
            Log.d(TAG, "Isenção de bateria já concedida");
            return;
        }

        // Verifica se o diálogo já foi exibido nesta instalação
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_BATERIA, false)) {
            Log.d(TAG, "Isenção de bateria já solicitada anteriormente");
            return;
        }

        // Marca como já solicitado antes de exibir (evita duplicata se a Activity recriar)
        prefs.edit().putBoolean(KEY_BATERIA, true).apply();

        new AlertDialog.Builder(activity)
                .setTitle("⚡ Ativar alertas em segundo plano")
                .setMessage(
                        "Para receber alertas do paciente mesmo com o app fechado, "
                        + "o EverNear precisa ficar ativo em segundo plano.\n\n"
                        + "Na próxima tela, selecione:\n"
                        + "\"Não otimizar\" → Concluído\n\n"
                        + "Isso garante que você seja notificado em emergências, "
                        + "mesmo com o celular em repouso.")
                .setCancelable(false)
                .setPositiveButton("Configurar agora", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        // Fallback: abre lista geral de apps com otimização de bateria
                        Log.w(TAG, "Fallback para tela geral de otimização");
                        try {
                            activity.startActivity(
                                    new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Exception ex) {
                            Log.e(TAG, "Não foi possível abrir configurações de bateria");
                        }
                    }
                })
                .setNegativeButton("Agora não", (dialog, which) -> {
                    // Permite pular, mas não reseta o flag — não será perguntado novamente
                    Log.d(TAG, "Usuário adiou a isenção de bateria");
                })
                .show();
    }
}

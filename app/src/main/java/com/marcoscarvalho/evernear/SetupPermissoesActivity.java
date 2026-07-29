package com.marcoscarvalho.evernear;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Tela de configuração inicial de permissões.
 *
 * Exibida UMA VEZ, logo após o primeiro login/cadastro.
 * Percorre todas as permissões necessárias para o papel do usuário e, a cada
 * etapa, ABRE DIRETAMENTE o diálogo do sistema (sem telas intermediárias).
 *
 * ── Paciente (relógio) ────────────────────────────────────────────────────
 *   1. POST_NOTIFICATIONS    – exibir alertas locais na tela do relógio
 *   2. BODY_SENSORS          – ler frequência cardíaca em primeiro plano
 *   3. BODY_SENSORS_BACKGROUND – ler sensor com tela apagada (API 33+)
 *   4. Isenção de bateria    – mantém o serviço ativo em segundo plano
 *
 * ── Cuidador (celular / tablet) ───────────────────────────────────────────
 *   1. POST_NOTIFICATIONS    – receber alertas de emergência
 *   2. Isenção de bateria    – recebe alertas mesmo com app fechado
 *
 * Cada permissão já concedida é pulada automaticamente.
 * O usuário pode pular qualquer passo — o app funciona parcialmente sem
 * algumas permissões, mas exibe avisos nas telas correspondentes.
 */
public class SetupPermissoesActivity extends AppCompatActivity {

    private static final int REQ_NOTIFICACOES             = 3001;
    private static final int REQ_BODY_SENSORS             = 3002;
    private static final int REQ_BODY_SENSORS_BACKGROUND  = 3003;
    private static final int REQ_ACTIVITY_RECOGNITION     = 3004;
    private static final int REQ_BATERIA                  = 3005;
    private static final int REQ_LOCALIZACAO              = 3006;
    private static final int REQ_LOCALIZACAO_BACKGROUND   = 3008;
    private static final int REQ_GPS                      = 3007;

    // ── Tipos de passo ─────────────────────────────────────────────────────
    private enum TipoPasso {
        PERMISSAO_RUNTIME,   // requestPermissions() → diálogo padrão do sistema
        BATERIA_OTIMIZACAO,  // startActivityForResult() → tela de bateria do sistema
        GPS_VERIFICACAO      // startActivity(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }

    // ── Descrição de cada passo ────────────────────────────────────────────
    private static class Passo {
        final TipoPasso tipo;
        final String    permissao;  // null para BATERIA_OTIMIZACAO
        final int       requestCode;
        final String    icone;
        final String    titulo;
        final String    descricao;

        Passo(TipoPasso tipo, String permissao, int requestCode,
              String icone, String titulo, String descricao) {
            this.tipo        = tipo;
            this.permissao   = permissao;
            this.requestCode = requestCode;
            this.icone       = icone;
            this.titulo      = titulo;
            this.descricao   = descricao;
        }
    }

    private List<Passo> passos;
    private int         passoAtual = 0;
    private String      userType;

    private TextView    tvPasso, tvIcone, tvNome, tvDescricao;
    private ProgressBar progressBar;
    private Button      btnPermitir, btnPular;

    // ==================== Ciclo de vida ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_permissoes);

        userType = getIntent().getStringExtra("userType");

        tvPasso      = findViewById(R.id.tv_passo);
        tvIcone      = findViewById(R.id.tv_icone);
        tvNome       = findViewById(R.id.tv_nome_permissao);
        tvDescricao  = findViewById(R.id.tv_descricao);
        progressBar  = findViewById(R.id.progress_bar);
        btnPermitir  = findViewById(R.id.btn_permitir);
        btnPular     = findViewById(R.id.btn_pular);

        btnPermitir.setOnClickListener(v -> executarPassoAtual());
        btnPular.setOnClickListener(v -> proximoPasso());

        construirListaPassos();
        avancarParaProximoPasso(); // pula passos já concedidos e exibe o primeiro pendente
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Quando o usuário volta das configurações do sistema, verifica se o passo
        // atual foi concluído (bateria isenta, GPS ativado) e avança automaticamente.
        if (passos != null && passoAtual < passos.size()) {
            Passo p = passos.get(passoAtual);
            if (p.tipo == TipoPasso.BATERIA_OTIMIZACAO && estaIsentoOtimizacaoBateria()) {
                proximoPasso();
            } else if (p.tipo == TipoPasso.GPS_VERIFICACAO
                    && LocationHelper.gpsHabilitado(this)) {
                proximoPasso();
            }
        }
    }

    // ==================== Construção dos passos ============================

    private void construirListaPassos() {
        passos = new ArrayList<>();
        boolean ehPaciente = FirebaseHelper.isPaciente(userType);

        // ── Passo 1: Notificações (ambos os papéis, API 33+) ─────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            passos.add(new Passo(
                    TipoPasso.PERMISSAO_RUNTIME,
                    Manifest.permission.POST_NOTIFICATIONS,
                    REQ_NOTIFICACOES,
                    "🔔",
                    "Notificações",
                    ehPaciente
                            ? "Exibe alertas de frequência cardíaca anormal diretamente na tela do relógio."
                            : "Envia alertas de emergência do paciente mesmo com o celular em repouso."
            ));
        }

        if (ehPaciente) {
            // ── Passo 2: Sensor cardíaco (foreground) ────────────────────────
            passos.add(new Passo(
                    TipoPasso.PERMISSAO_RUNTIME,
                    Manifest.permission.BODY_SENSORS,
                    REQ_BODY_SENSORS,
                    "🫀",
                    "Sensor de frequência cardíaca",
                    "Lê os batimentos cardíacos em tempo real para detectar anomalias e enviar alertas."
            ));

            // ── Passo 3: Sensor cardíaco em segundo plano (API 33+) ──────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                passos.add(new Passo(
                        TipoPasso.PERMISSAO_RUNTIME,
                        Manifest.permission.BODY_SENSORS_BACKGROUND,
                        REQ_BODY_SENSORS_BACKGROUND,
                        "😴",
                        "Sensor com tela apagada",
                        "Mantém o monitoramento dos batimentos mesmo quando a tela do relógio está apagada ou você está usando outro app."
                ));
            }

            // ── Passo 4: Reconhecimento de atividade (API 29+) ───────────────
            // Necessário para usar TYPE_STEP_COUNTER no HeartRateMonitor,
            // que detecta exercício físico e evita falsos alertas de BPM alto.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                passos.add(new Passo(
                        TipoPasso.PERMISSAO_RUNTIME,
                        Manifest.permission.ACTIVITY_RECOGNITION,
                        REQ_ACTIVITY_RECOGNITION,
                        "🏃",
                        "Detecção de atividade física",
                        "Identifica quando você está caminhando ou correndo para evitar alertas desnecessários durante exercícios."
                ));
            }
        }

        if (ehPaciente) {
            // ── Passo: permissão de localização (precisão fina) ─────────────
            // ACCESS_FINE_LOCATION é runtime permission desde API 23.
            // Necessária para FusedLocationProviderClient e para a API de Geofence.
            passos.add(new Passo(
                    TipoPasso.PERMISSAO_RUNTIME,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    REQ_LOCALIZACAO,
                    "📍",
                    "Localização de emergência",
                    "Permite ao EverNear obter sua posição apenas em alertas de emergência "
                            + "(batimentos anormais ou saída da área segura). "
                            + "A localização não é enviada continuamente."
            ));

            // ── Passo: localização em segundo plano (API 29+) ─────────────────
            // ACCESS_BACKGROUND_LOCATION é obrigatório no Android 10+ para que a
            // API de Geofence dispare eventos quando o app está em segundo plano.
            // Sem esta permissão, o GeofenceReceiver nunca é chamado com app fechado.
            // Deve ser solicitada DEPOIS de ACCESS_FINE_LOCATION já estar concedida.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                passos.add(new Passo(
                        TipoPasso.PERMISSAO_RUNTIME,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        REQ_LOCALIZACAO_BACKGROUND,
                        "🗺️",
                        "Localização em segundo plano",
                        "Permite verificar a área segura mesmo com o app fechado. "
                                + "Na próxima tela, selecione \"Permitir sempre\" para que o "
                                + "EverNear detecte saídas da zona segura mesmo com tela apagada."
                ));
            }

            // ── Passo: GPS habilitado ─────────────────────────────────────────
            // Não é uma runtime permission — exige que o usuário ative o GPS
            // manualmente nas configurações do sistema.
            passos.add(new Passo(
                    TipoPasso.GPS_VERIFICACAO,
                    null,
                    REQ_GPS,
                    "🛰️",
                    "GPS ativado",
                    "O GPS deve estar ligado para que o EverNear possa localizar você "
                            + "em uma emergência e verificar se você está dentro da área segura.\n\n"
                            + "Ative a localização na próxima tela."
            ));
        }

        // ── Último passo: isenção de otimização de bateria (ambos) ───────────
        passos.add(new Passo(
                TipoPasso.BATERIA_OTIMIZACAO,
                null,
                REQ_BATERIA,
                "⚡",
                ehPaciente ? "Monitoramento em segundo plano" : "Alertas em segundo plano",
                ehPaciente
                        ? "Permite que o EverNear continue monitorando mesmo com o app fechado.\n\nSelecione \"Não otimizar\" na próxima tela."
                        : "Garante que você receba alertas do paciente mesmo com o app completamente fechado.\n\nSelecione \"Não otimizar\" na próxima tela."
        ));
    }

    // ==================== Navegação entre passos ===========================

    /**
     * Avança até o próximo passo ainda pendente (permissão não concedida).
     * Se todos estiverem concluídos, navega para a tela principal.
     */
    private void avancarParaProximoPasso() {
        while (passoAtual < passos.size()) {
            if (!estaConcluidoPasso(passos.get(passoAtual))) break;
            passoAtual++;
        }

        if (passoAtual >= passos.size()) {
            navegarParaTelaPrincipal();
        } else {
            exibirPassoAtual();
        }
    }

    private boolean estaConcluidoPasso(Passo p) {
        if (p.tipo == TipoPasso.BATERIA_OTIMIZACAO) {
            return estaIsentoOtimizacaoBateria();
        }
        if (p.tipo == TipoPasso.GPS_VERIFICACAO) {
            return LocationHelper.gpsHabilitado(this);
        }
        return ContextCompat.checkSelfPermission(this, p.permissao)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void exibirPassoAtual() {
        Passo p = passos.get(passoAtual);
        int total = passos.size();
        int num   = passoAtual + 1;

        tvPasso.setText("Passo " + num + " de " + total);
        progressBar.setProgress((num * 100) / total);
        tvIcone.setText(p.icone);
        tvNome.setText(p.titulo);
        tvDescricao.setText(p.descricao);

        // Botão principal: texto varia conforme o tipo de passo
        if (p.tipo == TipoPasso.BATERIA_OTIMIZACAO) {
            btnPermitir.setText("Abrir configurações de bateria");
        } else if (p.tipo == TipoPasso.GPS_VERIFICACAO) {
            btnPermitir.setText("Abrir configurações de localização");
        } else {
            btnPermitir.setText("Conceder permissão");
        }

        // Passos críticos (bateria e GPS) não devem ter botão "Pular"
        boolean critico = p.tipo == TipoPasso.BATERIA_OTIMIZACAO
                || p.tipo == TipoPasso.GPS_VERIFICACAO;
        btnPular.setAlpha(critico ? 0.4f : 1f);
    }

    private void executarPassoAtual() {
        if (passoAtual >= passos.size()) { navegarParaTelaPrincipal(); return; }
        Passo p = passos.get(passoAtual);

        if (p.tipo == TipoPasso.PERMISSAO_RUNTIME) {
            // Abre DIRETAMENTE o diálogo do sistema para esta permissão
            ActivityCompat.requestPermissions(this,
                    new String[]{p.permissao}, p.requestCode);
        } else if (p.tipo == TipoPasso.GPS_VERIFICACAO) {
            // Abre as configurações de localização do sistema
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception e) {
                // Fallback: configurações gerais
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                catch (Exception ex) { proximoPasso(); }
            }
        } else {
            // Abre DIRETAMENTE a tela de bateria do sistema para este app
            abrirConfiguracaoBateria();
        }
    }

    private void proximoPasso() {
        passoAtual++;
        avancarParaProximoPasso();
    }

    // ==================== Callbacks do sistema =============================

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Independente de concedido ou negado, avança para o próximo passo
        proximoPasso();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BATERIA) {
            proximoPasso();
        }
    }

    // ==================== Bateria ==========================================

    private void abrirConfiguracaoBateria() {
        try {
            // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: abre a tela específica DESTE APP
            // O usuário vê apenas um botão "Não otimizar" — direto ao ponto
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_BATERIA);
        } catch (Exception e) {
            // Fallback: alguns fabricantes bloqueiam o intent específico — abre a lista geral
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivityForResult(fallback, REQ_BATERIA);
            } catch (Exception ex) {
                // Último recurso: pula este passo
                proximoPasso();
            }
        }
    }

    private boolean estaIsentoOtimizacaoBateria() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    // ==================== Navegação final ==================================

    private void navegarParaTelaPrincipal() {
        boolean ehPaciente = FirebaseHelper.isPaciente(userType);
        Intent intent = new Intent(this,
                ehPaciente ? PatientActivity.class : CaregiverActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}

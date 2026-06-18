package com.marcoscarvalho.evernear;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dashboard do paciente: exibe nome/apelido, código de vínculo, status dos
 * cuidadores vinculados e dados de monitoramento cardíaco.
 *
 * ┌─ Correções aplicadas ──────────────────────────────────────────────────────┐
 * │  1. Listener movido para onStart()/onStop() — dados atualizados ao voltar  │
 * │     à tela após minimizar ou navegar para outra Activity.                  │
 * │  2. uid nulo → finish() imediato — sem risco de write em "users/null".     │
 * │  3. Campo correto: cuidadoresVinculados (List) em vez de cuidadorVinculado │
 * │     (String singular inexistente) — status de vínculo agora funciona.      │
 * │  4. Geração de código fora do listener, com verificação de unicidade via   │
 * │     FirebaseHelper.garantirCodigoUnico() — sem loop de escrita.            │
 * │  5. Cache codigoAtual e cuidadoresCache — evita reads redundantes a cada   │
 * │     snapshot quando esses dados não mudaram.                               │
 * │  6. Debounce no botão Salvar Apelido.                                      │
 * │  7. btnShare com ação padrão imediata (não depende do listener retornar).  │
 * │  8. Cuidadores exibidos com prioridade em cards ordenados.                 │
 * │  9. Status de saúde (BPM, última sync, status monitor) quando disponível.  │
 * │  10. Mensagens de erro contextuais + detecção de falta de internet.        │
 * └────────────────────────────────────────────────────────────────────────────┘
 */
public class DashboardPacienteActivity extends AppCompatActivity {

    private static final String TAG = "DashboardPaciente";

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView     tvWelcome, tvCodigo, tvStatus;
    private LinearLayout llCuidadoresVinculados;
    private CardView     cardStatusSaude;
    private TextView     tvUltimoBpm, tvUltimaSync, tvStatusMonitor;
    private EditText     etApelido;
    private Button       btnSalvarApelido, btnShare;

    // ── Firebase ──────────────────────────────────────────────────────────────
    private FirebaseFirestore    db;
    private String               uid;
    private ListenerRegistration listenerRegistration;

    // ── Estado local ──────────────────────────────────────────────────────────
    /** Código atual em memória — impede geração repetida a cada snapshot. */
    private String codigoAtual = null;
    /** UIDs dos cuidadores na última renderização — evita reads redundantes. */
    private List<String> cuidadoresCache = null;
    /** Impede múltiplos cliques em Salvar Apelido. */
    private boolean salvando = false;

    // ── Holder de dados por cuidador ──────────────────────────────────────────

    private static final class DadosCuidador {
        String  nomeExibir = "Cuidador";
        int     prioridade = -1; // posição 0-indexed na lista de escalada
        boolean erro       = false;
    }

    // ==================== Ciclo de vida ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard_paciente);

        tvWelcome            = findViewById(R.id.tv_welcome_paciente);
        tvCodigo             = findViewById(R.id.tv_codigo_vinculo);
        tvStatus             = findViewById(R.id.tv_status_vinculo);
        llCuidadoresVinculados = findViewById(R.id.ll_cuidadores_vinculados);
        cardStatusSaude      = findViewById(R.id.card_status_saude);
        tvUltimoBpm          = findViewById(R.id.tv_ultimo_bpm);
        tvUltimaSync         = findViewById(R.id.tv_ultima_sync);
        tvStatusMonitor      = findViewById(R.id.tv_status_monitor);
        etApelido            = findViewById(R.id.et_apelido_paciente);
        btnSalvarApelido     = findViewById(R.id.btn_salvar_apelido_paciente);
        btnShare             = findViewById(R.id.btn_share_codigo);

        db  = FirebaseFirestore.getInstance();
        uid = FirebaseAuth.getInstance().getUid();

        // Sessão expirada ou sem autenticação → encerra imediatamente
        if (uid == null) {
            Toast.makeText(this, "Sessão expirada. Faça login novamente.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Código passado pela Intent (exibido imediatamente, sem esperar o Firestore)
        String codigoViaIntent = getIntent().getStringExtra("codigoVinculo");
        if (codigoViaIntent != null && !codigoViaIntent.isEmpty()) {
            codigoAtual = codigoViaIntent;
            tvCodigo.setText(codigoAtual);
        }

        // btnShare tem ação imediata — não depende do listener retornar
        // Se o código ainda não chegou, mostra mensagem amigável
        btnShare.setOnClickListener(v -> {
            if (codigoAtual != null && !codigoAtual.isEmpty()) {
                compartilharCodigo(codigoAtual);
            } else {
                Toast.makeText(this, "Código ainda sendo carregado...", Toast.LENGTH_SHORT).show();
            }
        });

        btnSalvarApelido.setOnClickListener(v -> salvarApelido());
    }

    /**
     * O listener é (re)criado aqui para garantir dados atualizados sempre que
     * o usuário retornar à tela — inclusive após minimizar e reabrir o app.
     */
    @Override
    protected void onStart() {
        super.onStart();
        iniciarListener();
    }

    /** Remove o listener ao sair da tela para não consumir rede em segundo plano. */
    @Override
    protected void onStop() {
        super.onStop();
        removerListener();
        cuidadoresCache = null; // força reload completo ao voltar
    }

    // ==================== Listener do Firestore ====================

    /**
     * Registra o SnapshotListener no documento do paciente.
     * Remove listener anterior antes de criar (garante unicidade mesmo após onStart()).
     */
    private void iniciarListener() {
        removerListener();

        listenerRegistration = db.collection("users").document(uid)
                .addSnapshotListener((snapshot, erro) -> {
                    if (erro != null) {
                        Log.w(TAG, "Erro no listener: " + erro.getMessage());
                        String msg = isConectado()
                                ? "Erro ao carregar dados. Tente novamente."
                                : "Sem conexão com a internet.";
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (snapshot == null) return;
                    if (!snapshot.exists()) {
                        Log.w(TAG, "Documento do usuário não encontrado");
                        return;
                    }

                    // ── Saudação ──────────────────────────────────────────────
                    String nome    = snapshot.getString("nome");
                    String apelido = snapshot.getString("apelido");
                    String exibir  = (apelido != null && !apelido.isEmpty()) ? apelido : nome;
                    if (exibir != null) tvWelcome.setText("Olá, " + exibir);

                    // Preenche campo de apelido somente se vazio (não sobrescreve edição em curso)
                    if (apelido != null && !apelido.isEmpty()
                            && etApelido.getText().toString().isEmpty()) {
                        etApelido.setText(apelido);
                    }

                    // ── Código de vínculo ─────────────────────────────────────
                    String codigoFirestore = snapshot.getString("codigoVinculo");
                    if (codigoFirestore != null && !codigoFirestore.isEmpty()) {
                        // Código existe no Firestore: exibe e configura botão
                        if (!codigoFirestore.equals(codigoAtual)) {
                            codigoAtual = codigoFirestore;
                            tvCodigo.setText(codigoAtual);
                        }
                    } else if (codigoAtual == null) {
                        // Código ausente e não está em geração: gera com verificação de unicidade
                        // codigoAtual != null impede geração duplicada em re-disparos do listener
                        codigoAtual = ""; // placeholder — bloqueia novas chamadas durante a geração
                        gerarCodigo();
                    }

                    // ── Cuidadores vinculados (campo correto: lista plural) ────
                    @SuppressWarnings("unchecked")
                    List<String> cuidadores = (List<String>) snapshot.get("cuidadoresVinculados");

                    boolean listaMudou = !isMesmaLista(cuidadores, cuidadoresCache);
                    if (listaMudou) {
                        cuidadoresCache = cuidadores;
                        carregarCuidadores(cuidadores);
                    }

                    // ── Status de saúde (campos opcionais) ───────────────────
                    atualizarStatusSaude(snapshot);
                });
    }

    private void removerListener() {
        if (listenerRegistration != null) {
            listenerRegistration.remove();
            listenerRegistration = null;
        }
    }

    // ==================== Código de vínculo ====================

    /**
     * Gera um código único via FirebaseHelper e atualiza a UI quando pronto.
     * Chamado somente quando o documento não possui código — nunca dentro de loop.
     */
    private void gerarCodigo() {
        Log.d(TAG, "Gerando código de vínculo único...");
        FirebaseHelper.garantirCodigoUnico(uid, new FirebaseHelper.Callback<String>() {
            @Override
            public void onResult(String codigo) {
                codigoAtual = codigo;
                tvCodigo.setText(codigo);
                Log.d(TAG, "Código de vínculo definido: " + codigo);
            }

            @Override
            public void onError(Exception e) {
                codigoAtual = null; // reseta para permitir nova tentativa
                Log.e(TAG, "Falha ao gerar código: " + e.getMessage());
                tvCodigo.setText("ERRO");
                Toast.makeText(DashboardPacienteActivity.this,
                        "Não foi possível gerar seu código. Verifique a conexão.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Abre o seletor de aplicativos para compartilhar o código de vínculo.
     * Inclui instruções para facilitar o uso pelo cuidador.
     */
    private void compartilharCodigo(String codigo) {
        String texto = "Meu código EverNear é: " + codigo
                + "\n\nUse este código no aplicativo EverNear para se vincular a mim como cuidador.";
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, texto);
        sendIntent.setType("text/plain");
        startActivity(Intent.createChooser(sendIntent, "Compartilhar código via"));
    }

    // ==================== Cuidadores vinculados ====================

    /**
     * Carrega os dados de cada cuidador preservando a ordem de prioridade.
     *
     * A posição na lista define a ordem de escalada dos alertas:
     *  índice 0 = Prioridade 1 (notificado primeiro)
     *  índice 1 = Prioridade 2 (notificado se o 1º não confirmar em 5 min)
     *  índice 2 = Prioridade 3
     *
     * Usa array indexado + AtomicInteger para evitar reordenação por resposta
     * assíncrona (mesmo padrão do DashboardCuidadorActivity).
     */
    private void carregarCuidadores(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            tvStatus.setText("Compartilhe este código com seu cuidador");
            tvStatus.setTextColor(Color.parseColor("#9AA4B2"));
            llCuidadoresVinculados.setVisibility(View.GONE);
            return;
        }

        int total               = uids.size();
        DadosCuidador[] dados   = new DadosCuidador[total];
        AtomicInteger   feitos  = new AtomicInteger(0);

        for (int i = 0; i < total; i++) {
            final int    idx    = i;
            final String uidCd  = uids.get(i);

            db.collection("users").document(uidCd).get()
                    .addOnSuccessListener(doc -> {
                        dados[idx] = extrairDadosCuidador(doc, idx);
                        if (feitos.incrementAndGet() == total) {
                            renderizarCuidadores(dados, total);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Erro ao carregar cuidador " + uidCd + ": " + e.getMessage());
                        DadosCuidador err = new DadosCuidador();
                        err.erro      = true;
                        err.prioridade = idx;
                        dados[idx]    = err;
                        if (feitos.incrementAndGet() == total) {
                            renderizarCuidadores(dados, total);
                        }
                    });
        }
    }

    private DadosCuidador extrairDadosCuidador(
            com.google.firebase.firestore.DocumentSnapshot doc, int indice) {
        DadosCuidador d = new DadosCuidador();
        d.prioridade = indice;
        if (!doc.exists()) { d.erro = true; return d; }
        String apelido = doc.getString("apelido");
        String nome    = doc.getString("nome");
        d.nomeExibir = (apelido != null && !apelido.isEmpty())
                ? apelido : (nome != null && !nome.isEmpty() ? nome : "Cuidador");
        return d;
    }

    /** Atualiza o status geral e reconstrói os cards de cuidadores na ordem correta. */
    private void renderizarCuidadores(DadosCuidador[] dados, int total) {
        // Status geral
        String textoStatus = total == 1
                ? "1 cuidador vinculado"
                : total + " cuidadores vinculados";
        tvStatus.setText(textoStatus);
        tvStatus.setTextColor(Color.parseColor("#4CAF50"));

        // Cards individuais
        llCuidadoresVinculados.removeAllViews();
        llCuidadoresVinculados.setVisibility(View.VISIBLE);

        for (DadosCuidador d : dados) {
            adicionarCardCuidador(d);
        }
    }

    private void adicionarCardCuidador(DadosCuidador d) {
        androidx.cardview.widget.CardView card =
                new androidx.cardview.widget.CardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 12);
        card.setLayoutParams(lp);
        card.setCardBackgroundColor(Color.parseColor("#161B22"));
        card.setRadius(12f);
        card.setCardElevation(4f);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setPadding(32, 20, 32, 20);
        inner.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvDot = new TextView(this);
        tvDot.setText("● ");
        tvDot.setTextColor(d.erro ? Color.parseColor("#FF5252") : Color.parseColor("#4CAF50"));
        tvDot.setTextSize(14f);

        LinearLayout coluna = new LinearLayout(this);
        coluna.setOrientation(LinearLayout.VERTICAL);

        TextView tvNome = new TextView(this);
        tvNome.setText(d.erro ? "Cuidador (erro ao carregar)" : d.nomeExibir);
        tvNome.setTextColor(Color.WHITE);
        tvNome.setTextSize(15f);

        coluna.addView(tvNome);

        if (!d.erro && d.prioridade >= 0) {
            TextView tvPrio = new TextView(this);
            tvPrio.setText("Prioridade " + (d.prioridade + 1));
            tvPrio.setTextColor(Color.parseColor("#9AA4B2"));
            tvPrio.setTextSize(13f);
            coluna.addView(tvPrio);
        }

        inner.addView(tvDot);
        inner.addView(coluna);
        card.addView(inner);
        llCuidadoresVinculados.addView(card);
    }

    // ==================== Status de saúde ====================

    /**
     * Lê os campos de monitoramento do snapshot e atualiza o card de status.
     * O card é exibido somente quando ao menos um campo de saúde estiver disponível.
     * Campos opcionais — não causa crash se ausentes.
     */
    private void atualizarStatusSaude(com.google.firebase.firestore.DocumentSnapshot snapshot) {
        Long   bpmLong       = snapshot.getLong("ultimoBpm");
        Long   syncMs        = snapshot.getLong("ultimaAtualizacao");
        String statusMonitor = snapshot.getString("statusMonitoramento");

        boolean temDados = bpmLong != null || syncMs != null || statusMonitor != null;
        if (!temDados) {
            cardStatusSaude.setVisibility(View.GONE);
            return;
        }

        cardStatusSaude.setVisibility(View.VISIBLE);

        if (bpmLong != null) {
            tvUltimoBpm.setText("Último BPM: " + bpmLong + " bpm");
            tvUltimoBpm.setVisibility(View.VISIBLE);
        } else {
            tvUltimoBpm.setVisibility(View.GONE);
        }

        if (syncMs != null) {
            String hora = new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(syncMs));
            tvUltimaSync.setText("Última leitura: " + hora);
            tvUltimaSync.setVisibility(View.VISIBLE);
        } else {
            tvUltimaSync.setVisibility(View.GONE);
        }

        if (statusMonitor != null && !statusMonitor.isEmpty()) {
            String label;
            int cor;
            switch (statusMonitor) {
                case "ATIVO":
                    label = "● Monitoramento ativo";
                    cor   = Color.parseColor("#4CAF50");
                    break;
                case "PARADO":
                    label = "⚠ Monitoramento parado";
                    cor   = Color.parseColor("#FFC107");
                    break;
                case "SEM_SENSOR":
                    label = "✕ Sensor não disponível";
                    cor   = Color.parseColor("#FF5252");
                    break;
                default:
                    label = statusMonitor;
                    cor   = Color.parseColor("#9AA4B2");
            }
            tvStatusMonitor.setText(label);
            tvStatusMonitor.setTextColor(cor);
            tvStatusMonitor.setVisibility(View.VISIBLE);
        } else {
            tvStatusMonitor.setVisibility(View.GONE);
        }
    }

    // ==================== Apelido ====================

    private void salvarApelido() {
        if (salvando) return;
        String apelido = etApelido.getText().toString().trim();
        if (apelido.isEmpty()) {
            Toast.makeText(this, "Digite um apelido primeiro", Toast.LENGTH_SHORT).show();
            return;
        }
        setSalvando(true);
        FirebaseHelper.salvarApelido(uid, apelido, new FirebaseHelper.Callback<Void>() {
            @Override
            public void onResult(Void result) {
                setSalvando(false);
                Toast.makeText(DashboardPacienteActivity.this,
                        "Apelido salvo!", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onError(Exception e) {
                setSalvando(false);
                String msg = isConectado()
                        ? "Erro ao salvar apelido. Tente novamente."
                        : "Sem conexão com a internet.";
                Toast.makeText(DashboardPacienteActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setSalvando(boolean ativo) {
        salvando = ativo;
        btnSalvarApelido.setEnabled(!ativo);
        btnSalvarApelido.setAlpha(ativo ? 0.5f : 1f);
        btnSalvarApelido.setText(ativo ? "..." : "Salvar");
    }

    // ==================== Utilitários ====================

    /** Verifica conexão de rede ativa. Compatível com API 28. */
    private boolean isConectado() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    /** Compara duas listas de UIDs sem lançar exceções com nulls. */
    private static boolean isMesmaLista(List<String> a, List<String> b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}

package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DashboardCuidadorActivity extends AppCompatActivity {

    private TextView     tvWelcome;
    private EditText     etApelido, etCodigo;
    private Button       btnSalvarApelido, btnVincular;
    private LinearLayout llListaPacientes;
    private TextView     tvSemPacientes;

    private FirebaseFirestore    db;
    private String               uidCuidador;
    private ListenerRegistration listenerRegistration;

    /**
     * Rastreia a última lista de UIDs renderizada.
     * Evita recriar todos os cards quando o snapshot do cuidador dispara por
     * motivos não relacionados à lista de pacientes (ex.: mudança de apelido).
     */
    private List<String> ultimosUids = null;

    // ==================== Ciclo de vida ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard_cuidador);

        tvWelcome        = findViewById(R.id.tv_welcome_cuidador);
        etApelido        = findViewById(R.id.et_apelido_cuidador);
        btnSalvarApelido = findViewById(R.id.btn_salvar_apelido);
        etCodigo         = findViewById(R.id.et_codigo_vincular);
        btnVincular      = findViewById(R.id.btn_vincular_paciente);
        llListaPacientes = findViewById(R.id.ll_lista_pacientes);
        tvSemPacientes   = findViewById(R.id.tv_sem_pacientes);

        db          = FirebaseFirestore.getInstance();
        uidCuidador = FirebaseAuth.getInstance().getUid();

        if (uidCuidador == null) {
            Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        btnSalvarApelido.setOnClickListener(v -> salvarApelido());
        btnVincular.setOnClickListener(v -> tentarVincular());
    }

    /**
     * Listener criado em onStart() e removido em onStop() — garante que os dados
     * sejam atualizados ao retornar à tela após minimizar o app.
     * O padrão onStart/onStop é o correto para Activities com SnapshotListeners.
     */
    @Override
    protected void onStart() {
        super.onStart();
        iniciarListener();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listenerRegistration != null) {
            listenerRegistration.remove();
            listenerRegistration = null;
        }
        // Reseta o cache de UIDs para forçar reload completo ao retornar
        // (um paciente pode ter sido adicionado/removido enquanto a tela estava fora do foco)
        ultimosUids = null;
    }

    // ==================== Listener Firestore ====================

    private void iniciarListener() {
        // Remove listener anterior antes de criar (garante unicidade mesmo após onStart())
        if (listenerRegistration != null) listenerRegistration.remove();

        listenerRegistration = db.collection("users").document(uidCuidador)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null || snapshot == null || !snapshot.exists()) return;

                    String apelido = snapshot.getString(FirebaseHelper.Fields.APELIDO);
                    String nome    = snapshot.getString(FirebaseHelper.Fields.NOME);
                    tvWelcome.setText("Olá, " + FirebaseHelper.nomeExibir(apelido, nome, "Cuidador"));

                    // Preenche campo de apelido somente se vazio (não sobrescreve edição em curso)
                    if (apelido != null && !apelido.isEmpty()
                            && etApelido.getText().toString().isEmpty()) {
                        etApelido.setText(apelido);
                    }

                    @SuppressWarnings("unchecked")
                    List<String> pacientesUids =
                            (List<String>) snapshot.get(FirebaseHelper.Fields.PACIENTES_VINCULADOS);
                    carregarListaPacientes(pacientesUids);
                });
    }

    // ==================== Lista de pacientes ====================

    /**
     * Carrega e exibe os pacientes vinculados ao cuidador, preservando a ordem de prioridade.
     *
     * Otimizações:
     *  • Se a lista de UIDs não mudou desde a última renderização, os cards são mantidos
     *    para evitar flickering visual causado por snapshots disparados por outros campos.
     *  • Array indexado + AtomicInteger garante que os cards sejam exibidos na ordem
     *    correta, independente da ordem de chegada dos callbacks assíncronos.
     */
    private void carregarListaPacientes(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            tvSemPacientes.setVisibility(View.VISIBLE);
            llListaPacientes.removeAllViews();
            ultimosUids = null;
            return;
        }

        tvSemPacientes.setVisibility(View.GONE);

        // Evita recriar os cards quando a lista de UIDs não mudou
        if (uids.equals(ultimosUids)) return;
        ultimosUids = new ArrayList<>(uids);

        int           total     = uids.size();
        String[]      textos    = new String[total];
        String[]      nomes     = new String[total];  // nome de exibição sem rótulo de prioridade
        AtomicInteger pendentes = new AtomicInteger(total);

        for (int i = 0; i < total; i++) {
            final int    idx         = i;
            final String uidPaciente = uids.get(i);

            db.collection("users").document(uidPaciente).get()
                    .addOnSuccessListener(doc -> {
                        if (!doc.exists()) {
                            textos[idx] = "Paciente (não encontrado)";
                            nomes[idx]  = "Paciente";
                        } else {
                            String nomePac    = doc.getString(FirebaseHelper.Fields.NOME);
                            String apelidoPac = doc.getString(FirebaseHelper.Fields.APELIDO);
                            String exibir     = FirebaseHelper.nomeExibir(apelidoPac, nomePac, "Paciente");
                            nomes[idx]        = exibir;

                            // Prioridade: posição deste cuidador na lista do paciente
                            @SuppressWarnings("unchecked")
                            List<String> cuidadores =
                                    (List<String>) doc.get(FirebaseHelper.Fields.CUIDADORES_VINCULADOS);
                            int pos = cuidadores != null ? cuidadores.indexOf(uidCuidador) : -1;
                            String labelPrio = pos >= 0 ? " · Prioridade " + (pos + 1) : "";
                            textos[idx] = exibir + labelPrio;
                        }
                        if (pendentes.decrementAndGet() == 0) {
                            construirCards(textos, uids.toArray(new String[0]), nomes);
                        }
                    })
                    .addOnFailureListener(ex -> {
                        textos[idx] = "Paciente (erro ao carregar)";
                        nomes[idx]  = "Paciente";
                        if (pendentes.decrementAndGet() == 0) {
                            construirCards(textos, uids.toArray(new String[0]), nomes);
                        }
                    });
        }
    }

    /** Constrói os cards na ordem correta após todos os reads assíncronos concluírem. */
    private void construirCards(String[] textos, String[] uids, String[] nomes) {
        llListaPacientes.removeAllViews();
        for (int i = 0; i < textos.length; i++) {
            adicionarCardPaciente(textos[i], uids[i], nomes[i]);
        }
    }

    /**
     * Cria o card de um paciente na lista.
     *
     * Toque curto → opções rápidas: "Ver no mapa" ou "Configurar zona segura".
     *
     * @param nomeExibir  texto formatado (nome + rótulo de prioridade)
     * @param uidPaciente UID do paciente para ações na tela de zona segura
     * @param nomePaciente nome de exibição limpo para a tela de configuração
     */
    private void adicionarCardPaciente(String nomeExibir, String uidPaciente, String nomePaciente) {
        CardView card = new CardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 16);
        card.setLayoutParams(cardParams);
        card.setCardBackgroundColor(Color.parseColor("#161B22"));
        card.setRadius(12f);
        card.setCardElevation(4f);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setPadding(40, 28, 40, 28);
        inner.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvDot = new TextView(this);
        tvDot.setText("● ");
        tvDot.setTextColor(Color.parseColor("#4CAF50"));
        tvDot.setTextSize(14f);

        TextView tvNome = new TextView(this);
        tvNome.setText(nomeExibir);
        tvNome.setTextColor(Color.WHITE);
        tvNome.setTextSize(16f);
        tvNome.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Ícone de engrenagem para indicar que é clicável
        TextView tvConfig = new TextView(this);
        tvConfig.setText("⚙");
        tvConfig.setTextColor(Color.parseColor("#58A6FF"));
        tvConfig.setTextSize(18f);

        inner.addView(tvDot);
        inner.addView(tvNome);
        inner.addView(tvConfig);
        card.addView(inner);
        llListaPacientes.addView(card);

        // Toque → opções de ação para este paciente
        card.setOnClickListener(v -> mostrarOpcoesCardPaciente(uidPaciente, nomePaciente));
    }

    /**
     * Exibe um diálogo de opções ao tocar em um card de paciente.
     * Permite configurar a zona segura diretamente a partir da lista.
     */
    private void mostrarOpcoesCardPaciente(String uidPaciente, String nomePaciente) {
        String[] opcoes = {"🗺️  Configurar zona segura"};
        new AlertDialog.Builder(this)
                .setTitle(nomePaciente)
                .setItems(opcoes, (dialog, which) -> {
                    if (which == 0) {
                        ConfigurarPontoReferenciaActivity.abrir(
                                DashboardCuidadorActivity.this, uidPaciente, nomePaciente);
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    // ==================== Vincular paciente ====================

    /**
     * Inicia a vinculação com o paciente cujo código foi informado.
     *
     * Não realiza verificações de limites previamente: a transação do FirebaseHelper
     * já as executa de forma ATÔMICA, eliminando a race condition que existia quando
     * a Activity fazia uma leitura prévia seguida de uma escrita separada.
     * Os erros semânticos da transação são mapeados para mensagens amigáveis em
     * {@link #vincular(String)}.
     */
    private void tentarVincular() {
        String codigo = etCodigo.getText().toString().trim().toUpperCase();
        if (codigo.length() != 6) {
            Toast.makeText(this, "Código inválido — deve ter 6 caracteres", Toast.LENGTH_SHORT).show();
            return;
        }

        FirebaseHelper.buscarPacientePorCodigo(codigo,
                new FirebaseHelper.Callback<DocumentSnapshot>() {
                    @Override
                    public void onResult(DocumentSnapshot pacienteDoc) {
                        if (pacienteDoc == null) {
                            Toast.makeText(DashboardCuidadorActivity.this,
                                    "Paciente não encontrado com esse código",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        // Extrai o nome de exibição para usar após a vinculação
                        String nomePac    = pacienteDoc.getString(FirebaseHelper.Fields.NOME);
                        String apelidoPac = pacienteDoc.getString(FirebaseHelper.Fields.APELIDO);
                        String nomeExibir = FirebaseHelper.nomeExibir(apelidoPac, nomePac, "Paciente");
                        vincular(pacienteDoc.getId(), nomeExibir);
                    }

                    @Override
                    public void onError(Exception e) {
                        Toast.makeText(DashboardCuidadorActivity.this,
                                "Erro ao buscar paciente: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /**
     * Executa a vinculação e, em caso de sucesso, oferece ao cuidador a opção de
     * configurar imediatamente a zona segura para o paciente recém-vinculado.
     */
    private void vincular(String uidPaciente, String nomePaciente) {
        FirebaseHelper.vincularPacienteCuidador(uidCuidador, uidPaciente,
                new FirebaseHelper.Callback<Void>() {
                    @Override
                    public void onResult(Void result) {
                        etCodigo.setText("");

                        // Oferece configuração imediata da zona segura
                        new AlertDialog.Builder(DashboardCuidadorActivity.this)
                                .setTitle("✅ Paciente vinculado!")
                                .setMessage("Deseja configurar agora a zona segura para "
                                        + nomePaciente + "?\n\n"
                                        + "A zona segura envia um alerta automático se o "
                                        + "paciente sair da área definida.")
                                .setPositiveButton("Configurar zona segura", (d, w) ->
                                        ConfigurarPontoReferenciaActivity.abrir(
                                                DashboardCuidadorActivity.this,
                                                uidPaciente, nomePaciente))
                                .setNegativeButton("Agora não", null)
                                .show();
                    }

                    @Override
                    public void onError(Exception e) {
                        // Mapeia os erros semânticos da transação para mensagens amigáveis.
                        // A Activity não faz leituras prévias — confia na transação atômica
                        // como única fonte de verdade para validações de vinculação.
                        String mensagem;
                        String chave = e.getMessage();
                        if ("LIMITE_CUIDADOR".equals(chave)) {
                            mensagem = "Você já possui o máximo de 3 pacientes vinculados";
                        } else if ("LIMITE_PACIENTE".equals(chave)) {
                            mensagem = "Este paciente já possui 3 cuidadores vinculados";
                        } else if ("JA_VINCULADO".equals(chave)) {
                            mensagem = "Você já está vinculado a este paciente";
                        } else {
                            mensagem = "Erro ao vincular. Verifique a conexão e tente novamente.";
                        }
                        Toast.makeText(DashboardCuidadorActivity.this,
                                mensagem, Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ==================== Apelido ====================

    private void salvarApelido() {
        String apelido = etApelido.getText().toString().trim();
        if (apelido.isEmpty()) {
            Toast.makeText(this, "Digite um apelido primeiro", Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseHelper.salvarApelido(uidCuidador, apelido, new FirebaseHelper.Callback<Void>() {
            @Override
            public void onResult(Void result) {
                Toast.makeText(DashboardCuidadorActivity.this,
                        "Apelido salvo!", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(DashboardCuidadorActivity.this,
                        EverNearApplication.isOnline()
                                ? "Erro ao salvar apelido. Tente novamente."
                                : "Sem conexão com a internet.",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}

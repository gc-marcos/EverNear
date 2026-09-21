package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DashboardCuidadorActivity extends AppCompatActivity {

    private TextView tvWelcome;
    private LinearLayout llListaPacientes;
    private View tvSemPacientes;
    private ImageButton btnExcluirPaciente;
    private FirebaseFirestore db;
    private String uidCuidador;
    private ListenerRegistration listenerRegistration;
    private List<String> ultimosUids;
    private String pacienteSelecionadoUid;
    private String pacienteSelecionadoNome;
    private CardView cardPacienteSelecionado;
    private boolean exclusaoEmAndamento;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard_cuidador);

        tvWelcome = findViewById(R.id.tv_welcome_cuidador);
        llListaPacientes = findViewById(R.id.ll_lista_pacientes);
        tvSemPacientes = findViewById(R.id.tv_sem_pacientes);
        btnExcluirPaciente = findViewById(R.id.btn_excluir_paciente);
        db = FirebaseFirestore.getInstance();
        uidCuidador = FirebaseAuth.getInstance().getUid();

        if (uidCuidador == null) {
            Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        View abrirVinculo = findViewById(R.id.btn_vincular_paciente);
        View abrirVinculoVazio = findViewById(R.id.btn_vincular_paciente_empty);
        abrirVinculo.setOnClickListener(v -> abrirVincularPaciente());
        abrirVinculoVazio.setOnClickListener(v -> abrirVincularPaciente());
        btnExcluirPaciente.setOnClickListener(v -> confirmarExclusaoPaciente());
        atualizarEstadoBotaoExcluir();
    }

    private void abrirVincularPaciente() {
        startActivity(new Intent(this, VincularPacienteActivity.class));
    }

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
        ultimosUids = null;
        limparSelecaoPaciente();
    }

    private void iniciarListener() {
        if (listenerRegistration != null) listenerRegistration.remove();
        listenerRegistration = db.collection("users").document(uidCuidador)
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null || snapshot == null || !snapshot.exists()) return;
                    tvWelcome.setText("Meus Pacientes");
                    @SuppressWarnings("unchecked")
                    List<String> uids = (List<String>) snapshot.get(
                            FirebaseHelper.Fields.PACIENTES_VINCULADOS);
                    carregarListaPacientes(uids);
                });
    }

    private void carregarListaPacientes(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            tvSemPacientes.setVisibility(View.VISIBLE);
            llListaPacientes.removeAllViews();
            ultimosUids = null;
            limparSelecaoPaciente();
            return;
        }
        tvSemPacientes.setVisibility(View.GONE);
        if (uids.equals(ultimosUids)) return;
        ultimosUids = new ArrayList<>(uids);

        String[] nomes = new String[uids.size()];
        String[] bpms = new String[uids.size()];
        AtomicInteger pendentes = new AtomicInteger(uids.size());
        for (int i = 0; i < uids.size(); i++) {
            final int index = i;
            db.collection("users").document(uids.get(i)).get()
                    .addOnSuccessListener(doc -> {
                        String nome = doc.exists()
                                ? FirebaseHelper.nomeExibir(
                                doc.getString(FirebaseHelper.Fields.APELIDO),
                                doc.getString(FirebaseHelper.Fields.NOME),
                                "Paciente")
                                : "Paciente";
                        nomes[index] = nome;
                        Long bpm = doc.getLong(FirebaseHelper.Fields.ULTIMO_BPM);
                        bpms[index] = bpm != null ? bpm + " BPM" : "Sem leitura recente";
                        if (pendentes.decrementAndGet() == 0) construirCards(nomes, bpms, uids);
                    })
                    .addOnFailureListener(error -> {
                        nomes[index] = "Paciente";
                        bpms[index] = "Sem leitura recente";
                        if (pendentes.decrementAndGet() == 0) construirCards(nomes, bpms, uids);
                    });
        }
    }

    private void construirCards(String[] nomes, String[] bpms, List<String> uids) {
        llListaPacientes.removeAllViews();
        for (int i = 0; i < nomes.length; i++) {
            adicionarCardPaciente(nomes[i], bpms[i], uids.get(i));
        }
    }

    private void adicionarCardPaciente(String nome, String bpm, String uidPaciente) {
        CardView card = new CardView(this);
        card.setCardBackgroundColor(Color.parseColor("#171D37"));
        card.setRadius(20f);
        card.setCardElevation(0f);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 14);
        card.setLayoutParams(cardParams);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(18, 18, 18, 18);

        TextView avatar = new TextView(this);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(52, 52));
        avatar.setGravity(Gravity.CENTER);
        avatar.setText(gerarIniciais(nome));
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(17);
        avatar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(Color.parseColor("#26315B"));
        avatar.setBackground(avatarBg);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(14, 0, 0, 0);
        info.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView nomeView = new TextView(this);
        nomeView.setText(nome);
        nomeView.setTextColor(Color.WHITE);
        nomeView.setTextSize(17);
        nomeView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView bpmView = new TextView(this);
        bpmView.setText("♥  " + bpm + "  •  Ver detalhes");
        bpmView.setTextColor(Color.parseColor("#A4B0D6"));
        bpmView.setTextSize(13);
        bpmView.setPadding(0, 5, 0, 0);
        info.addView(nomeView);
        info.addView(bpmView);

        TextView seta = new TextView(this);
        seta.setText("›");
        seta.setTextColor(Color.parseColor("#24D36B"));
        seta.setTextSize(30);

        inner.addView(avatar);
        inner.addView(info);
        inner.addView(seta);
        card.addView(inner);
        llListaPacientes.addView(card);

        card.setOnLongClickListener(v -> {
            selecionarPaciente(card, nome, uidPaciente);
            Toast.makeText(this, nome + " selecionado", Toast.LENGTH_SHORT).show();
            return true;
        });
        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, CaregiverActivity.class);
            intent.putExtra("pacienteId", uidPaciente);
            startActivity(intent);
        });
    }

    private void selecionarPaciente(CardView card, String nome, String uidPaciente) {
        if (cardPacienteSelecionado != null
                && cardPacienteSelecionado != card) {
            cardPacienteSelecionado.setCardBackgroundColor(Color.parseColor("#171D37"));
        }

        cardPacienteSelecionado = card;
        pacienteSelecionadoUid = uidPaciente;
        pacienteSelecionadoNome = nome;
        card.setCardBackgroundColor(Color.parseColor("#263A68"));
        atualizarEstadoBotaoExcluir();
    }

    private void limparSelecaoPaciente() {
        if (cardPacienteSelecionado != null) {
            cardPacienteSelecionado.setCardBackgroundColor(Color.parseColor("#171D37"));
        }
        cardPacienteSelecionado = null;
        pacienteSelecionadoUid = null;
        pacienteSelecionadoNome = null;
        exclusaoEmAndamento = false;
        atualizarEstadoBotaoExcluir();
    }

    private void atualizarEstadoBotaoExcluir() {
        if (btnExcluirPaciente == null) return;
        boolean ativo = pacienteSelecionadoUid != null && !exclusaoEmAndamento;
        btnExcluirPaciente.setEnabled(ativo);
        btnExcluirPaciente.setAlpha(ativo ? 1f : 0.35f);
    }

    private void confirmarExclusaoPaciente() {
        if (pacienteSelecionadoUid == null || exclusaoEmAndamento) return;

        String uidPaciente = pacienteSelecionadoUid;
        String nomePaciente = pacienteSelecionadoNome != null
                ? pacienteSelecionadoNome : "Paciente";

        new AlertDialog.Builder(this)
                .setTitle("Excluir paciente?")
                .setMessage("Deseja realmente excluir " + nomePaciente
                        + " da sua lista de pacientes?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (dialog, which) ->
                        excluirPaciente(uidPaciente, nomePaciente))
                .show();
    }

    private void excluirPaciente(String uidPaciente, String nomePaciente) {
        exclusaoEmAndamento = true;
        atualizarEstadoBotaoExcluir();

        FirebaseHelper.desvincularPacienteCuidador(uidCuidador, uidPaciente,
                new FirebaseHelper.Callback<Void>() {
                    @Override
                    public void onResult(Void result) {
                        limparSelecaoPaciente();
                        Toast.makeText(DashboardCuidadorActivity.this,
                                nomePaciente + " foi excluído da sua lista.",
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(Exception error) {
                        exclusaoEmAndamento = false;
                        atualizarEstadoBotaoExcluir();
                        Toast.makeText(DashboardCuidadorActivity.this,
                                "Não foi possível excluir o paciente. Tente novamente.",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private String gerarIniciais(String nome) {
        if (nome == null || nome.trim().isEmpty()) return "?";
        String[] partes = nome.trim().split("\\s+");
        if (partes.length == 1) return partes[0].substring(0, 1).toUpperCase();
        return (partes[0].substring(0, 1) + partes[partes.length - 1].substring(0, 1))
                .toUpperCase();
    }
}
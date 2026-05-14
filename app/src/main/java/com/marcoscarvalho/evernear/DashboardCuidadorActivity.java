package com.marcoscarvalho.evernear;

import android.graphics.Color;
import android.os.Bundle;
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
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.List;

public class DashboardCuidadorActivity extends AppCompatActivity {

    private TextView tvWelcome;
    private EditText etApelido, etCodigo;
    private Button btnSalvarApelido, btnVincular;
    private LinearLayout llListaPacientes;
    private TextView tvSemPacientes;

    private FirebaseFirestore db;
    private String uidCuidador;
    private ListenerRegistration listenerRegistration;

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

        db = FirebaseFirestore.getInstance();
        uidCuidador = FirebaseAuth.getInstance().getUid();

        if (uidCuidador == null) {
            Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        btnSalvarApelido.setOnClickListener(v -> salvarApelido());
        btnVincular.setOnClickListener(v -> tentarVincular());
        iniciarListener();
    }

    private void iniciarListener() {
        listenerRegistration = db.collection("users").document(uidCuidador)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null || snapshot == null || !snapshot.exists()) return;

                    String apelido = snapshot.getString("apelido");
                    String nome    = snapshot.getString("nome");
                    String exibir  = (apelido != null && !apelido.isEmpty()) ? apelido : nome;
                    tvWelcome.setText("Olá, " + (exibir != null ? exibir : "Cuidador"));

                    if (apelido != null && !apelido.isEmpty()
                            && etApelido.getText().toString().isEmpty()) {
                        etApelido.setText(apelido);
                    }

                    @SuppressWarnings("unchecked")
                    List<String> pacientesUids = (List<String>) snapshot.get("pacientesVinculados");
                    carregarListaPacientes(pacientesUids);
                });
    }

    private void carregarListaPacientes(List<String> uids) {
        llListaPacientes.removeAllViews();

        if (uids == null || uids.isEmpty()) {
            tvSemPacientes.setVisibility(View.VISIBLE);
            return;
        }
        tvSemPacientes.setVisibility(View.GONE);

        for (String uidPaciente : uids) {
            db.collection("users").document(uidPaciente).get()
                    .addOnSuccessListener(doc -> {
                        if (!doc.exists()) return;
                        String nomePaciente   = doc.getString("nome");
                        String apelidoPaciente = doc.getString("apelido");
                        String textoExibir = (apelidoPaciente != null && !apelidoPaciente.isEmpty())
                                ? apelidoPaciente + " (" + nomePaciente + ")"
                                : (nomePaciente != null ? nomePaciente : "Paciente");

                        // Descobre qual posição de prioridade este cuidador ocupa no paciente
                        @SuppressWarnings("unchecked")
                        List<String> cuidadores = (List<String>) doc.get("cuidadoresVinculados");
                        int prioridade = (cuidadores != null) ? cuidadores.indexOf(uidCuidador) : -1;
                        String labelPrioridade = prioridade == 0 ? " · Prioridade 1"
                                : prioridade == 1 ? " · Prioridade 2"
                                : prioridade == 2 ? " · Prioridade 3" : "";

                        adicionarCardPaciente(textoExibir + labelPrioridade);
                    })
                    .addOnFailureListener(e -> adicionarCardPaciente("Paciente (erro ao carregar)"));
        }
    }

    private void adicionarCardPaciente(String nomeExibir) {
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

        inner.addView(tvDot);
        inner.addView(tvNome);
        card.addView(inner);
        llListaPacientes.addView(card);
    }

    // ==================== Vincular paciente ====================

    private void tentarVincular() {
        String codigo = etCodigo.getText().toString().trim().toUpperCase();
        if (codigo.length() != 6) {
            Toast.makeText(this, "Código inválido — deve ter 6 caracteres", Toast.LENGTH_SHORT).show();
            return;
        }

        // Verifica limite de pacientes do cuidador (máx 3)
        db.collection("users").document(uidCuidador).get()
                .addOnSuccessListener(cuidadorDoc -> {
                    @SuppressWarnings("unchecked")
                    List<String> jaVinculados = (List<String>) cuidadorDoc.get("pacientesVinculados");
                    if (jaVinculados != null && jaVinculados.size() >= 3) {
                        Toast.makeText(this,
                                "Limite de 3 pacientes por cuidador atingido",
                                Toast.LENGTH_SHORT).show();
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

                                    // Verifica se o paciente já atingiu limite de cuidadores (máx 3)
                                    @SuppressWarnings("unchecked")
                                    List<String> cuidadoresDoPaciente =
                                            (List<String>) pacienteDoc.get("cuidadoresVinculados");
                                    if (cuidadoresDoPaciente != null
                                            && cuidadoresDoPaciente.size() >= 3) {
                                        Toast.makeText(DashboardCuidadorActivity.this,
                                                "Este paciente já possui 3 cuidadores vinculados",
                                                Toast.LENGTH_LONG).show();
                                        return;
                                    }

                                    // Verifica se este cuidador já está vinculado a este paciente
                                    if (cuidadoresDoPaciente != null
                                            && cuidadoresDoPaciente.contains(uidCuidador)) {
                                        Toast.makeText(DashboardCuidadorActivity.this,
                                                "Você já está vinculado a este paciente",
                                                Toast.LENGTH_LONG).show();
                                        return;
                                    }

                                    vincular(pacienteDoc.getId());
                                }

                                @Override
                                public void onError(Exception e) {
                                    Toast.makeText(DashboardCuidadorActivity.this,
                                            "Erro: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            });
                });
    }

    private void vincular(String uidPaciente) {
        FirebaseHelper.vincularPacienteCuidador(uidCuidador, uidPaciente,
                new FirebaseHelper.Callback<Void>() {
                    @Override
                    public void onResult(Void result) {
                        Toast.makeText(DashboardCuidadorActivity.this,
                                "Paciente vinculado com sucesso!", Toast.LENGTH_SHORT).show();
                        etCodigo.setText("");
                    }
                    @Override
                    public void onError(Exception e) {
                        Toast.makeText(DashboardCuidadorActivity.this,
                                "Erro ao vincular: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(DashboardCuidadorActivity.this, "Apelido salvo!", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onError(Exception e) {
                Toast.makeText(DashboardCuidadorActivity.this, "Erro ao salvar apelido", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listenerRegistration != null) listenerRegistration.remove();
    }
}

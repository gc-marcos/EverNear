package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;

public class VincularPacienteActivity extends AppCompatActivity {

    private EditText etCodigo;
    private TextView tvErro;
    private Button btnVincular;
    private LinearLayout conteudo;
    private String uidCuidador;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vincular_paciente);

        etCodigo = findViewById(R.id.et_codigo_vincular);
        tvErro = findViewById(R.id.tv_erro_codigo);
        btnVincular = findViewById(R.id.btn_vincular_codigo);
        conteudo = findViewById(R.id.ll_vincular_content);
        uidCuidador = FirebaseAuth.getInstance().getUid();

        findViewById(R.id.btn_voltar_vincular).setOnClickListener(v -> finish());
        etCodigo.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean preenchido = s.length() > 0;
                conteudo.setAlpha(preenchido ? 1f : 0.72f);
                tvErro.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        btnVincular.setOnClickListener(v -> tentarVincular());
    }

    private void tentarVincular() {
        String codigo = etCodigo.getText().toString().trim().toUpperCase();
        if (codigo.length() != 6) {
            mostrarErro();
            return;
        }
        btnVincular.setEnabled(false);
        FirebaseHelper.buscarPacientePorCodigo(codigo,
                new FirebaseHelper.Callback<DocumentSnapshot>() {
                    @Override public void onResult(DocumentSnapshot pacienteDoc) {
                        if (pacienteDoc == null) {
                            mostrarErro();
                            btnVincular.setEnabled(true);
                            return;
                        }
                        String nome = FirebaseHelper.nomeExibir(
                                pacienteDoc.getString(FirebaseHelper.Fields.APELIDO),
                                pacienteDoc.getString(FirebaseHelper.Fields.NOME),
                                "Paciente");
                        FirebaseHelper.vincularPacienteCuidador(uidCuidador, pacienteDoc.getId(),
                                new FirebaseHelper.Callback<Void>() {
                                    @Override public void onResult(Void result) {
                                        ConfigurarPontoReferenciaActivity.abrir(
                                                VincularPacienteActivity.this,
                                                pacienteDoc.getId(), nome);
                                        finish();
                                    }
                                    @Override public void onError(Exception error) {
                                        mostrarErro();
                                        btnVincular.setEnabled(true);
                                    }
                                });
                    }
                    @Override public void onError(Exception error) {
                        mostrarErro();
                        btnVincular.setEnabled(true);
                    }
                });
    }

    private void mostrarErro() {
        tvErro.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Código inválido. Verifique e tente novamente.",
                Toast.LENGTH_SHORT).show();
    }
}
package com.marcoscarvalho.evernear;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.List;

public class DashboardCuidadorActivity extends AppCompatActivity {

    private EditText etCodigo;
    private Button btnVincular;
    private FirebaseFirestore db;
    private String uidCuidador;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard_cuidador);

        etCodigo = findViewById(R.id.et_codigo_vincular);
        btnVincular = findViewById(R.id.btn_vincular_paciente);

        db = FirebaseFirestore.getInstance();
        uidCuidador = FirebaseAuth.getInstance().getUid();

        btnVincular.setOnClickListener(v -> tentarVincular());
    }

    private void tentarVincular() {
        String codigo = etCodigo.getText().toString().trim();
        if (codigo.length() != 6) {
            Toast.makeText(this, "Código inválido", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users").document(uidCuidador).get().addOnSuccessListener(cuidadorDoc -> {
            List<String> vinculados = (List<String>) cuidadorDoc.get("pacientesVinculados");
            if (vinculados != null && vinculados.size() >= 3) {
                Toast.makeText(this, "Limite de 3 pacientes atingido", Toast.LENGTH_SHORT).show();
                return;
            }

            FirebaseHelper.buscarPacientePorCodigo(codigo, new FirebaseHelper.Callback<DocumentSnapshot>() {
                @Override
                public void onResult(DocumentSnapshot pacienteDoc) {
                    if (pacienteDoc != null) {
                        String uidPaciente = pacienteDoc.getId();
                        if (pacienteDoc.get("cuidadorVinculado") != null) {
                            Toast.makeText(DashboardCuidadorActivity.this, "Paciente já possui cuidador", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        vincular(uidPaciente);
                    } else {
                        Toast.makeText(DashboardCuidadorActivity.this, "Paciente não encontrado", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(Exception e) {
                    Toast.makeText(DashboardCuidadorActivity.this, "Erro: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void vincular(String uidPaciente) {
        FirebaseHelper.vincularPacienteCuidador(uidCuidador, uidPaciente, new FirebaseHelper.Callback<Void>() {
            @Override
            public void onResult(Void result) {
                Toast.makeText(DashboardCuidadorActivity.this, "Vinculado com sucesso!", Toast.LENGTH_SHORT).show();
                etCodigo.setText("");
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(DashboardCuidadorActivity.this, "Erro ao vincular", Toast.LENGTH_SHORT).show();
            }
        });
    }
}

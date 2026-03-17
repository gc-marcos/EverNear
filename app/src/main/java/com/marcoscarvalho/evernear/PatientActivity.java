package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class PatientActivity extends AppCompatActivity {

    private TextView tvBpmValue;
    private TextView tvStatus;
    private Button btnEmergency;
    private Button btnVerCodigo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient);

        tvBpmValue = findViewById(R.id.tv_bpm_value);
        tvStatus = findViewById(R.id.tv_status);
        btnEmergency = findViewById(R.id.btn_emergency);
        btnVerCodigo = findViewById(R.id.btn_ver_codigo);

        // Abre o dashboard com o código de sincronização para o cuidador
        btnVerCodigo.setOnClickListener(v -> {
            startActivity(new Intent(PatientActivity.this, DashboardPacienteActivity.class));
        });

        // Botão de emergência (funcionalidade futura)
        btnEmergency.setOnClickListener(v -> {
            // TODO: disparar alerta de emergência para o cuidador vinculado
        });

        updateBpm(78);
    }

    private void updateBpm(int bpm) {
        tvBpmValue.setText(String.valueOf(bpm));

        if (bpm < 60) {
            tvStatus.setText("Atenção");
            tvStatus.setTextColor(0xFFFFFF00);
        } else if (bpm > 100) {
            tvStatus.setText("Alerta");
            tvStatus.setTextColor(0xFFFF0000);
        } else {
            tvStatus.setText("Normal");
            tvStatus.setTextColor(0xFF00FF00);
        }
    }
}

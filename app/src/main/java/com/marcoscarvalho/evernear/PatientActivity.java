package com.marcoscarvalho.evernear;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class PatientActivity extends AppCompatActivity {

    private TextView tvBpmValue;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient);

        tvBpmValue = findViewById(R.id.tv_bpm_value);
        tvStatus = findViewById(R.id.tv_status);

        // Example of dynamic update
        updateBpm(78);
    }

    private void updateBpm(int bpm) {
        tvBpmValue.setText(String.valueOf(bpm));

        if (bpm < 60) {
            tvStatus.setText("Atenção");
            tvStatus.setTextColor(0xFFFFFF00); // Yellow
        } else if (bpm > 100) {
            tvStatus.setText("Alerta");
            tvStatus.setTextColor(0xFFFF0000); // Red
        } else {
            tvStatus.setText("Normal");
            tvStatus.setTextColor(0xFF00FF00); // Green
        }
    }
}
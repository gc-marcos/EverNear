package com.marcoscarvalho.evernear;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Tela para configurar limites mínimos e máximos de frequência cardíaca.
 */
public class HeartRateSettingsActivity extends Activity {

    private static final String PREFERENCES_NAME = "heart_rate_prefs";
    private static final String KEY_MIN_HEART_RATE = "min_hr";
    private static final String KEY_MAX_HEART_RATE = "max_hr";

    private EditText minHeartRateInput;
    private EditText maxHeartRateInput;
    private Button saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heart_rate_settings);

        initializeViews();
        populateExistingValues();
        setupSaveAction();
    }

    private void initializeViews() {
        minHeartRateInput = findViewById(R.id.input_min_hr);
        maxHeartRateInput = findViewById(R.id.input_max_hr);
        saveButton = findViewById(R.id.button_save_hr);
    }

    private void populateExistingValues() {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        int minHr = preferences.getInt(KEY_MIN_HEART_RATE, 60);
        int maxHr = preferences.getInt(KEY_MAX_HEART_RATE, 100);

        minHeartRateInput.setText(String.valueOf(minHr));
        maxHeartRateInput.setText(String.valueOf(maxHr));
    }

    private void setupSaveAction() {
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String minText = minHeartRateInput.getText().toString().trim();
                String maxText = maxHeartRateInput.getText().toString().trim();

                if (TextUtils.isEmpty(minText) || TextUtils.isEmpty(maxText)) {
                    Toast.makeText(HeartRateSettingsActivity.this, "Preencha ambos os valores.", Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    int minHr = Integer.parseInt(minText);
                    int maxHr = Integer.parseInt(maxText);

                    if (minHr <= 0 || maxHr <= 0) {
                        Toast.makeText(HeartRateSettingsActivity.this, "Valores devem ser positivos.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (minHr >= maxHr) {
                        Toast.makeText(HeartRateSettingsActivity.this, "Mínimo deve ser menor que o máximo.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    SharedPreferences prefs = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
                    prefs.edit()
                        .putInt(KEY_MIN_HEART_RATE, minHr)
                        .putInt(KEY_MAX_HEART_RATE, maxHr)
                        .apply();

                    Toast.makeText(HeartRateSettingsActivity.this, "Salvo com sucesso.", Toast.LENGTH_SHORT).show();
                    finish();
                } catch (NumberFormatException ex) {
                    Toast.makeText(HeartRateSettingsActivity.this, "Insira números válidos.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}




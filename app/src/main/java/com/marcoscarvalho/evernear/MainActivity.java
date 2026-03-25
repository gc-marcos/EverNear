package com.marcoscarvalho.evernear;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cl_main_activity), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ImageButton patientButton = findViewById(R.id.btn_patient);
        ImageButton caregiverButton = findViewById(R.id.btn_caregiver);

        patientButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            intent.putExtra("userType", "patient");
            startActivity(intent);
        });

        caregiverButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            intent.putExtra("userType", "caregiver");
            startActivity(intent);
        });

        // Verifica se já existe sessão ativa e redireciona, pulando o login
        verificarSessao();
    }

    private void verificarSessao() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;

        String uid = FirebaseAuth.getInstance().getUid();
        FirebaseFirestore.getInstance().collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;

                    String tipo = doc.getString("tipo");
                    if ("patient".equals(tipo) || "paciente".equals(tipo)) {
                        // Paciente autenticado → monitor cardíaco
                        startActivity(new Intent(MainActivity.this, PatientActivity.class));
                    } else {
                        // Cuidador autenticado → tela de monitoramento do cuidador
                        startActivity(new Intent(MainActivity.this, CaregiverActivity.class));
                    }
                    finish();
                });
    }
}

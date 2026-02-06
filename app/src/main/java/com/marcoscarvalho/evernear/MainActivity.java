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

        patientButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                intent.putExtra("userType", "patient");
                startActivity(intent);
            }
        });

        caregiverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                intent.putExtra("userType", "caregiver");
                startActivity(intent);
            }
        });

        verificarSessao();
    }

    private void verificarSessao() {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
            String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(uid).get()
                    .addOnSuccessListener(documentSnapshot -> {
                        if (documentSnapshot.exists()) {
                            String tipo = documentSnapshot.getString("tipo");
                            if ("patient".equals(tipo)) {
                                startActivity(new Intent(MainActivity.this, DashboardPacienteActivity.class));
                            } else {
                                startActivity(new Intent(MainActivity.this, DashboardCuidadorActivity.class));
                            }
                            finish();
                        }
                    });
        }
    }
}

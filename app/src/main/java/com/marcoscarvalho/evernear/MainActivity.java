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

        // Dispositivos com smallestScreenWidthDp <= 360dp começam diretamente
        // no login do paciente. A ActivityMain continua sendo o ponto launcher
        // para preservar a configuração existente, mas não exibe sua interface
        // nesse fluxo.
        if (DeviceClassifier.isPacienteDevice(this)
                && FirebaseAuth.getInstance().getCurrentUser() == null) {
            abrirLoginPaciente();
            finish();
            return;
        }

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

    private void abrirLoginPaciente() {
        Intent intent = new Intent(MainActivity.this, LoginActivity.class);
        intent.putExtra("userType", "patient");
        startActivity(intent);
    }

    private void verificarSessao() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;

        String uid = FirebaseAuth.getInstance().getUid();
        FirebaseFirestore.getInstance().collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;

                    String tipo = doc.getString(FirebaseHelper.Fields.TIPO);
                    // Tipo ausente ou desconhecido → permanece na tela de seleção de papel
                    if (tipo == null) return;

                    boolean ehPaciente = FirebaseHelper.isPaciente(tipo);
                    boolean ehCuidador = FirebaseHelper.isCuidador(tipo);
                    if (!ehPaciente && !ehCuidador) {
                        return; // valor desconhecido — não redireciona
                    }

                    BootReceiver.salvarTipoAposLogin(MainActivity.this, tipo);

                    Intent destino;
                    if (PermissaoHelper.precisaConfigurarPermissoes(MainActivity.this)) {
                        destino = new Intent(MainActivity.this,
                                SetupPermissoesActivity.class);
                        destino.putExtra("userType", tipo);
                    } else {
                        destino = new Intent(MainActivity.this,
                                ehPaciente ? PatientActivity.class
                                        : DashboardCuidadorActivity.class);
                    }
                    startActivity(destino);
                    finish();
                });
    }
}

package com.marcoscarvalho.evernear.debug;

import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.marcoscarvalho.evernear.BuildConfig;
import com.marcoscarvalho.evernear.FirebaseHelper;
import com.marcoscarvalho.evernear.HeartRateService;
import com.marcoscarvalho.evernear.R;

import java.util.Locale;

/**
 * Tela de Debug de Geolocalização — disponível somente em BuildConfig.DEBUG == true.
 *
 * <p><b>Objetivo:</b> Permitir testar toda a lógica de geofencing (cálculo de
 * distância, verificação de raio, envio de alerta ao Firebase, notificação ao
 * cuidador) em dispositivos físicos sem precisar mover o relógio ou usar o
 * emulador.</p>
 *
 * <p><b>Princípio fundamental (sem duplicação de lógica):</b>
 * Esta Activity não reimplementa nenhuma regra de negócio. Ela apenas
 * cria um objeto {@link Location} simulado via {@link DebugGeofenceHelper} e
 * o encaminha para a mesma rotina usada pelo GPS real:
 * <pre>
 *   criarLocalizacaoSimulada(lat, lng)
 *     → ACTION_DEBUG_GEOFENCE_EXIT (Intent para HeartRateService)
 *     → HeartRateService.tratarSaidaGeofenceComLocalizacao(location)
 *     → FirebaseHelper.enviarAlerta()       ← idêntico à produção
 *     → Notificação ao cuidador             ← idêntico à produção
 * </pre>
 * </p>
 *
 * <p><b>Segurança:</b> Se {@code BuildConfig.DEBUG == false}, {@code onCreate()}
 * encerra imediatamente — nenhum código de debug é executado em Release.</p>
 *
 * <p><b>Campos exibidos em tempo real:</b>
 * <ul>
 *   <li>Latitude / longitude simuladas</li>
 *   <li>Latitude / longitude do ponto seguro (Firestore)</li>
 *   <li>Distância calculada em metros</li>
 *   <li>Raio configurado</li>
 *   <li>Status: "Dentro da Área" ou "Fora da Área"</li>
 * </ul>
 * </p>
 */
public class DebugLocationActivity extends AppCompatActivity {

    private static final String TAG = "DebugLocationActivity";

    // ── Views ──────────────────────────────────────────────────────────────────
    private EditText etLatitude;
    private EditText etLongitude;
    private Button   btnEnviar;
    private Button   btnDentro;
    private Button   btnFora50;
    private Button   btnFora100;
    private Button   btnFora300;
    private Button   btnFora1km;

    // Zona segura (lida do Firestore)
    private TextView tvZonaLat;
    private TextView tvZonaLng;
    private TextView tvZonaRaio;

    // Estado atual
    private TextView tvSimLat;
    private TextView tvSimLng;
    private TextView tvDistancia;
    private TextView tvStatus;

    // ── Dados da zona segura ──────────────────────────────────────────────────
    /** Coordenadas e raio lidos do Firestore. null se ainda não carregados. */
    private Double zonaLat   = null;
    private Double zonaLng   = null;
    private Float  zonaRaio  = null;

    // ── Firebase ──────────────────────────────────────────────────────────────
    private String uidPaciente;

    // ==================== Ciclo de vida ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Guarda de segurança ────────────────────────────────────────────────
        // Em builds de Release esta Activity nunca deve ser acessível.
        // A verificação aqui é uma camada extra além da ausência do botão de acesso.
        if (!BuildConfig.DEBUG) {
            Log.e(TAG, "DebugLocationActivity instanciada em build Release — encerrando");
            finish();
            return;
        }

        setContentView(R.layout.activity_debug_location);
        vincularViews();
        configurarBotoes();
        carregarZonaDoFirestore();
    }

    // ==================== Setup inicial ====================

    private void vincularViews() {
        etLatitude   = findViewById(R.id.et_debug_latitude);
        etLongitude  = findViewById(R.id.et_debug_longitude);
        btnEnviar    = findViewById(R.id.btn_debug_enviar);
        btnDentro    = findViewById(R.id.btn_debug_dentro);
        btnFora50    = findViewById(R.id.btn_debug_fora_50);
        btnFora100   = findViewById(R.id.btn_debug_fora_100);
        btnFora300   = findViewById(R.id.btn_debug_fora_300);
        btnFora1km   = findViewById(R.id.btn_debug_fora_1km);

        tvZonaLat    = findViewById(R.id.tv_debug_zona_lat);
        tvZonaLng    = findViewById(R.id.tv_debug_zona_lng);
        tvZonaRaio   = findViewById(R.id.tv_debug_zona_raio);

        tvSimLat     = findViewById(R.id.tv_debug_sim_lat);
        tvSimLng     = findViewById(R.id.tv_debug_sim_lng);
        tvDistancia  = findViewById(R.id.tv_debug_distancia);
        tvStatus     = findViewById(R.id.tv_debug_status);
    }

    private void configurarBotoes() {
        // Botão Enviar: usa os campos de texto como fonte de lat/lng
        btnEnviar.setOnClickListener(v -> {
            Double lat = parsarCampo(etLatitude, "Latitude");
            Double lng = parsarCampo(etLongitude, "Longitude");
            if (lat != null && lng != null) {
                processarLocalizacaoSimulada(lat, lng);
            }
        });

        // Botão "Dentro da Área Segura"
        btnDentro.setOnClickListener(v -> {
            if (!zonaDisponivel()) return;
            // Calcula ponto a 10% do raio do centro — inequivocamente dentro
            double[] ponto = DebugGeofenceHelper.calcularPontoDentroDoRaio(zonaLat, zonaLng, zonaRaio);
            preencherCamposEProcessar(ponto[0], ponto[1]);
        });

        // Botões "Fora N metros" — offset ao Norte do centro da zona
        btnFora50 .setOnClickListener(v -> simularForaDoCentro(50f));
        btnFora100.setOnClickListener(v -> simularForaDoCentro(100f));
        btnFora300.setOnClickListener(v -> simularForaDoCentro(300f));
        btnFora1km.setOnClickListener(v -> simularForaDoCentro(1000f));
    }

    // ==================== Carga da zona segura ====================

    /**
     * Lê os campos safeZoneLatitude/Longitude/Radius do documento do paciente
     * no Firestore e atualiza a UI.
     *
     * Chamado em onCreate(). Os dados ficam disponíveis para os botões rápidos
     * assim que o read assíncrono retornar.
     */
    private void carregarZonaDoFirestore() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            tvZonaLat.setText("Lat:  não autenticado");
            return;
        }
        uidPaciente = user.getUid();

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uidPaciente)
                .get()
                .addOnSuccessListener(this::aplicarDadosZona)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Falha ao ler zona segura: " + e.getMessage());
                    tvZonaLat.setText("Lat:  erro ao carregar");
                });
    }

    /**
     * Aplica os dados lidos do Firestore: popula as variáveis de zona e atualiza UI.
     */
    private void aplicarDadosZona(DocumentSnapshot doc) {
        Double lat   = doc.getDouble(FirebaseHelper.Fields.SAFE_ZONE_LATITUDE);
        Double lng   = doc.getDouble(FirebaseHelper.Fields.SAFE_ZONE_LONGITUDE);
        Double raio  = doc.getDouble(FirebaseHelper.Fields.SAFE_ZONE_RADIUS);

        if (lat == null || lng == null || raio == null || raio <= 0) {
            tvZonaLat.setText("Lat:  zona não configurada");
            tvZonaLng.setText("Long: configure no app do cuidador");
            tvZonaRaio.setText("Raio: —");
            Log.w(TAG, "Zona segura não configurada — botões rápidos desabilitados");
            return;
        }

        zonaLat  = lat;
        zonaLng  = lng;
        zonaRaio = raio.floatValue();

        tvZonaLat .setText(String.format(Locale.getDefault(), "Lat:  %.6f", zonaLat));
        tvZonaLng .setText(String.format(Locale.getDefault(), "Long: %.6f", zonaLng));
        tvZonaRaio.setText(String.format(Locale.getDefault(), "Raio: %.0f m", zonaRaio));

        Log.d(TAG, "Zona carregada: " + zonaLat + ", " + zonaLng + " r=" + zonaRaio + " m");
    }

    // ==================== Processamento da localização simulada ====================

    /**
     * Pré-popula os campos de texto e imediatamente processa a localização.
     * Usado pelos botões de simulação rápida.
     */
    private void preencherCamposEProcessar(double lat, double lng) {
        etLatitude .setText(String.format(Locale.getDefault(), "%.6f", lat));
        etLongitude.setText(String.format(Locale.getDefault(), "%.6f", lng));
        processarLocalizacaoSimulada(lat, lng);
    }

    /**
     * Calcula o ponto a {@code distanciaMetros} ao Norte do centro da zona
     * e o processa. Usado pelos botões "Fora N m".
     */
    private void simularForaDoCentro(float distanciaMetros) {
        if (!zonaDisponivel()) return;
        double[] ponto = DebugGeofenceHelper.calcularPontoADistancia(
                zonaLat, zonaLng, distanciaMetros, DebugGeofenceHelper.BEARING_NORTE);
        preencherCamposEProcessar(ponto[0], ponto[1]);
    }

    /**
     * Núcleo do Modo Debug — único ponto de entrada para localização simulada.
     *
     * Fluxo:
     *  1. Cria {@link Location} simulado via {@link DebugGeofenceHelper}.
     *  2. Calcula distância até o ponto seguro (se zona disponível).
     *  3. Atualiza todos os campos da UI com os valores calculados.
     *  4. Decide se está dentro ou fora do raio.
     *  5. Se fora → envia ACTION_DEBUG_GEOFENCE_EXIT para {@link HeartRateService},
     *     que executa exatamente a mesma cadeia de produção (salva no Firestore +
     *     envia alerta ao cuidador + agenda escalada).
     *  6. Se dentro → apenas exibe status; nenhum alerta é gerado.
     *
     * <b>Não há duplicação de lógica de negócio aqui.</b> A decisão de "enviar alerta"
     * fica em HeartRateService; esta Activity apenas fornece o Location e lê o resultado.
     *
     * @param lat latitude da posição simulada
     * @param lng longitude da posição simulada
     */
    private void processarLocalizacaoSimulada(double lat, double lng) {
        // 1. Cria o objeto Location simulado (mesma estrutura do GPS real)
        Location fakeLocation = DebugGeofenceHelper.criarLocalizacaoSimulada(lat, lng);

        // 2. Atualiza campos de posição simulada
        tvSimLat.setText(String.format(Locale.getDefault(), "Lat sim: %.6f", lat));
        tvSimLng.setText(String.format(Locale.getDefault(), "Long sim: %.6f", lng));

        // 3. Calcula distância (se a zona está configurada)
        if (!zonaDisponivel()) {
            atualizarStatus("Zona segura não configurada", false);
            tvDistancia.setText("Distância: zona não configurada");
            Toast.makeText(this,
                    "Configure a zona segura no app do cuidador primeiro",
                    Toast.LENGTH_LONG).show();
            return;
        }

        float distancia = DebugGeofenceHelper.calcularDistanciaMetros(
                lat, lng, zonaLat, zonaLng);

        tvDistancia.setText(String.format(Locale.getDefault(), "Distância: %.1f m", distancia));

        boolean foraDoRaio = distancia > zonaRaio;

        Log.d(TAG, String.format(Locale.getDefault(),
                "Simulação: lat=%.6f lng=%.6f | dist=%.1f m | raio=%.0f m | fora=%b",
                lat, lng, distancia, zonaRaio, foraDoRaio));

        // 4. Atualiza status visual
        if (foraDoRaio) {
            atualizarStatus(String.format(Locale.getDefault(),
                    "🚨  FORA DA ÁREA\n(%.1f m do limite)", distancia - zonaRaio), true);
        } else {
            atualizarStatus(String.format(Locale.getDefault(),
                    "✅  DENTRO DA ÁREA\n(%.1f m do limite)", zonaRaio - distancia), false);
        }

        // 5. Se fora → dispara exatamente o mesmo fluxo de produção via HeartRateService
        if (foraDoRaio) {
            dispararAlertaDebug(fakeLocation);
        }
    }

    /**
     * Envia ACTION_DEBUG_GEOFENCE_EXIT para HeartRateService com o Location simulado.
     *
     * HeartRateService.tratarSaidaGeofenceComLocalizacao(location) será chamado —
     * exatamente o mesmo método ativado pelo GeofenceReceiver em produção.
     * O resultado é indistinguível de uma saída de zona real:
     *  • localização salva no Firestore (users/{uid})
     *  • alerta criado na coleção "alerts" com lat/lng embarcados
     *  • CaregiverAlertService notifica o cuidador
     *  • escalada via AlarmManager, se necessário
     */
    private void dispararAlertaDebug(Location fakeLocation) {
        Intent intent = new Intent(this, HeartRateService.class);
        intent.setAction(HeartRateService.ACTION_DEBUG_GEOFENCE_EXIT);
        intent.putExtra(HeartRateService.EXTRA_DEBUG_LOCATION, fakeLocation);

        try {
            startService(intent);
            Toast.makeText(this,
                    "⚠ Alerta de saída enviado ao Firebase!", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "ACTION_DEBUG_GEOFENCE_EXIT enviado: "
                    + fakeLocation.getLatitude() + ", " + fakeLocation.getLongitude());
        } catch (Exception e) {
            Log.e(TAG, "Falha ao iniciar HeartRateService para debug: " + e.getMessage());
            Toast.makeText(this,
                    "Erro: HeartRateService não está rodando", Toast.LENGTH_LONG).show();
        }
    }

    // ==================== Auxiliares ====================

    /**
     * Atualiza o TextView de status com a cor adequada.
     * @param mensagem  texto a exibir
     * @param foraDoRaio true → vermelho; false → verde
     */
    private void atualizarStatus(String mensagem, boolean foraDoRaio) {
        tvStatus.setText("Status:\n" + mensagem);
        tvStatus.setTextColor(foraDoRaio
                ? Color.parseColor("#FF5252")   // vermelho — fora da área
                : Color.parseColor("#4CAF50")); // verde — dentro da área
    }

    /**
     * Verifica se a zona segura foi carregada do Firestore.
     * Exibe Toast se não estiver disponível.
     */
    private boolean zonaDisponivel() {
        if (zonaLat == null || zonaLng == null || zonaRaio == null) {
            Toast.makeText(this,
                    "Zona segura ainda não carregada — aguarde ou configure no app do cuidador",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /**
     * Parseia um {@link EditText} como {@code Double}.
     * Exibe Toast e retorna null se o campo estiver vazio ou inválido.
     */
    private Double parsarCampo(EditText campo, String nomeCampo) {
        String texto = campo.getText() != null ? campo.getText().toString().trim() : "";
        if (TextUtils.isEmpty(texto)) {
            Toast.makeText(this, nomeCampo + " não pode estar vazio", Toast.LENGTH_SHORT).show();
            return null;
        }
        try {
            return Double.parseDouble(texto);
        } catch (NumberFormatException e) {
            Toast.makeText(this, nomeCampo + " inválido: " + texto, Toast.LENGTH_SHORT).show();
            return null;
        }
    }
}

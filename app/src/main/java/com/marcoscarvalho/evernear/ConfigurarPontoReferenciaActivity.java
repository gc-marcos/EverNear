package com.marcoscarvalho.evernear;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import com.google.firebase.firestore.FirebaseFirestore;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Tela do CUIDADOR para configurar a zona segura do paciente.
 *
 * ┌─ Responsabilidades ────────────────────────────────────────────────────────┐
 * │  1. Exibir mapa Google Maps para o cuidador selecionar o ponto de          │
 *  │     referência (toque no mapa, busca de endereço ou localização atual).    │
 * │  2. Permitir definição do raio de segurança (100 m, 300 m, 500 m, 1 km).  │
 * │  3. Visualizar a zona como um círculo semitransparente no mapa.            │
 * │  4. Salvar ou remover a zona segura no Firestore via FirebaseHelper.       │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Integração com o app do paciente ─────────────────────────────────────────┐
 * │  Após salvar, o HeartRateService do paciente lê os campos                  │
 * │  safeZoneLatitude/Longitude/Radius do Firestore via SnapshotListener e    │
 * │  registra automaticamente o geofence no Android (LocationHelper).         │
 * │  Quando o paciente sai da zona, o GeofenceReceiver cria o alerta tipo     │
 * │  "SAIDA_ZONA" e o CaregiverAlertService notifica o cuidador.              │
 * └────────────────────────────────────────────────────────────────────────────┘
 */
public class ConfigurarPontoReferenciaActivity extends AppCompatActivity
        implements OnMapReadyCallback {

    private static final String TAG = "ConfigurarPontoRef";

    // ── Extras do Intent ───────────────────────────────────────────────────────
    public static final String EXTRA_UID_PACIENTE   = "uidPaciente";
    public static final String EXTRA_NOME_PACIENTE  = "nomePaciente";

    // ── Request codes ──────────────────────────────────────────────────────────
    private static final int REQ_LOCATION_PERMISSION = 3001;

    // ── Raios predefinidos (metros) ────────────────────────────────────────────
    private static final int[] RAIOS = {100, 300, 500, 1000};

    // ── Dados do paciente ──────────────────────────────────────────────────────
    private String uidPaciente;
    private String nomePaciente;

    // ── Views ──────────────────────────────────────────────────────────────────
    private TextView  tvSubtitulo;
    private TextView  tvZonaAtiva;
    private TextView  tvLocalSelecionado;
    private EditText  etEndereco;
    private Button    btnBuscar;
    private Button    btnMinhaLocalizacao;
    private Button[]  btnRaios;
    private Button    btnSalvar;
    private Button    btnRemover;

    // ── Mapa ───────────────────────────────────────────────────────────────────
    private GoogleMap googleMap;
    private Marker    marcador;
    private Circle    circulo;

    // ── Estado ────────────────────────────────────────────────────────────────
    private LatLng localSelecionado = null;
    private int    raioSelecionado  = 0;   // 0 = nenhum selecionado
    private boolean zonaExistente   = false;

    // ── Localização ───────────────────────────────────────────────────────────
    private FusedLocationProviderClient fusedClient;

    // ==================== Lançamento ====================

    /**
     * Inicia a tela de configuração de zona segura.
     *
     * @param origem       Activity que está chamando (contexto)
     * @param uidPaciente  UID do paciente no Firestore
     * @param nomePaciente nome de exibição do paciente
     */
    public static void abrir(Activity origem, String uidPaciente, String nomePaciente) {
        Intent intent = new Intent(origem, ConfigurarPontoReferenciaActivity.class);
        intent.putExtra(EXTRA_UID_PACIENTE,  uidPaciente);
        intent.putExtra(EXTRA_NOME_PACIENTE, nomePaciente != null ? nomePaciente : "Paciente");
        origem.startActivity(intent);
    }

    // ==================== Ciclo de vida ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_configurar_ponto_referencia);

        uidPaciente  = getIntent().getStringExtra(EXTRA_UID_PACIENTE);
        nomePaciente = getIntent().getStringExtra(EXTRA_NOME_PACIENTE);

        if (uidPaciente == null || uidPaciente.isEmpty()) {
            Toast.makeText(this, "Erro: paciente não identificado", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        inicializarViews();
        configurarListeners();
        carregarZonaExistente();

        // Inicializa o mapa
        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map_fragment);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    // ==================== Views ====================

    private void inicializarViews() {
        tvSubtitulo        = findViewById(R.id.tv_subtitulo);
        tvZonaAtiva        = findViewById(R.id.tv_zona_ativa);
        tvLocalSelecionado = findViewById(R.id.tv_local_selecionado);
        etEndereco         = findViewById(R.id.et_endereco);
        btnBuscar          = findViewById(R.id.btn_buscar);
        btnMinhaLocalizacao = findViewById(R.id.btn_minha_localizacao);
        btnSalvar          = findViewById(R.id.btn_salvar);
        btnRemover         = findViewById(R.id.btn_remover);

        btnRaios = new Button[]{
                findViewById(R.id.btn_raio_100),
                findViewById(R.id.btn_raio_300),
                findViewById(R.id.btn_raio_500),
                findViewById(R.id.btn_raio_1000)
        };

        tvSubtitulo.setText("Paciente: " + nomePaciente);
    }

    private void configurarListeners() {
        // Voltar
        findViewById(R.id.btn_voltar).setOnClickListener(v -> finish());

        // Busca de endereço via teclado (action "Buscar")
        etEndereco.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                buscarEndereco();
                return true;
            }
            return false;
        });
        btnBuscar.setOnClickListener(v -> buscarEndereco());

        // Localização atual
        btnMinhaLocalizacao.setOnClickListener(v -> usarMinhaLocalizacao());

        // Botões de raio
        int[] raiosValues = RAIOS;
        for (int i = 0; i < btnRaios.length; i++) {
            final int raio = raiosValues[i];
            btnRaios[i].setOnClickListener(v -> selecionarRaio(raio));
        }

        // Salvar
        btnSalvar.setOnClickListener(v -> salvarZona());

        // Remover
        btnRemover.setOnClickListener(v -> confirmarRemocao());
    }

    // ==================== Mapa ====================

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;

        // Configurações visuais
        googleMap.getUiSettings().setZoomControlsEnabled(true);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.getUiSettings().setMyLocationButtonEnabled(false); // usamos botão próprio

        // Tema escuro — tenta aplicar estilo. Falha silenciosa se o arquivo não existir.
        try {
            googleMap.setMapStyle(
                    com.google.android.gms.maps.model.MapStyleOptions.loadRawResourceStyle(
                            this, R.raw.map_style_dark));
        } catch (Exception ignored) {
            // Estilo escuro opcional — não crítico para o funcionamento
        }

        // Permissão de localização para o botão "Minha localização"
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            googleMap.setMyLocationEnabled(false); // controlamos via FusedLocation
        }

        // Toque no mapa define o local
        googleMap.setOnMapClickListener(latLng -> {
            selecionarLocal(latLng, "Local selecionado no mapa");
        });

        // Se já existe zona, centraliza o mapa nela; senão tenta localização atual
        if (localSelecionado != null) {
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(localSelecionado, 15f));
            atualizarMarcadorECirculo();
        } else {
            centralizarNaLocalizacaoAtual();
        }
    }

    // ==================== Seleção de local ====================

    /**
     * Define o local selecionado, atualiza o mapa e habilita o botão salvar se possível.
     *
     * @param latLng      coordenadas do ponto
     * @param descricao   texto de descrição para o usuário (endereço ou "Local no mapa")
     */
    private void selecionarLocal(LatLng latLng, String descricao) {
        localSelecionado = latLng;

        tvLocalSelecionado.setText(
                String.format(Locale.getDefault(),
                        "%s\nLat: %.5f  Lng: %.5f",
                        descricao, latLng.latitude, latLng.longitude));
        tvLocalSelecionado.setTextColor(Color.WHITE);

        atualizarMarcadorECirculo();
        atualizarBotaoSalvar();

        // Anima câmera para o local selecionado
        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f));
    }

    /**
     * Atualiza o marcador e o círculo de zona no mapa conforme o estado atual
     * ({@link #localSelecionado} e {@link #raioSelecionado}).
     */
    private void atualizarMarcadorECirculo() {
        if (googleMap == null || localSelecionado == null) return;

        // Remove marcador e círculo anteriores
        if (marcador != null) marcador.remove();
        if (circulo  != null) circulo.remove();

        // Marcador vermelho no ponto de referência
        marcador = googleMap.addMarker(new MarkerOptions()
                .position(localSelecionado)
                .title("Ponto de referência")
                .snippet(nomePaciente)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));

        // Círculo de zona segura (visível apenas se raio selecionado)
        if (raioSelecionado > 0) {
            circulo = googleMap.addCircle(new CircleOptions()
                    .center(localSelecionado)
                    .radius(raioSelecionado)
                    .strokeColor(Color.parseColor("#1F6FEB"))
                    .strokeWidth(3f)
                    .fillColor(Color.parseColor("#331F6FEB")));
        }
    }

    // ==================== Raio ====================

    private void selecionarRaio(int raioMetros) {
        raioSelecionado = raioMetros;

        // Atualiza estilo dos botões
        for (int i = 0; i < btnRaios.length; i++) {
            boolean selecionado = (RAIOS[i] == raioMetros);
            btnRaios[i].setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            selecionado ? Color.parseColor("#1F6FEB")
                                    : Color.parseColor("#21262D")));
        }

        atualizarMarcadorECirculo();
        atualizarBotaoSalvar();

        Log.d(TAG, "Raio selecionado: " + raioMetros + " m");
    }

    private void atualizarBotaoSalvar() {
        boolean habilitado = localSelecionado != null && raioSelecionado > 0;
        btnSalvar.setEnabled(habilitado);
        btnSalvar.setAlpha(habilitado ? 1f : 0.5f);
    }

    // ==================== Busca de endereço ====================

    /**
     * Busca o endereço digitado usando {@link Geocoder} (sem custo de API extra).
     * Funciona offline para alguns dispositivos, mas requer internet para precisão total.
     */
    private void buscarEndereco() {
        String textoEndereco = etEndereco.getText().toString().trim();
        if (textoEndereco.isEmpty()) {
            Toast.makeText(this, "Digite um endereço para buscar", Toast.LENGTH_SHORT).show();
            return;
        }

        // Fecha teclado
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etEndereco.getWindowToken(), 0);

        if (!Geocoder.isPresent()) {
            Toast.makeText(this, "Serviço de geocodificação não disponível neste dispositivo",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Buscando endereço…", Toast.LENGTH_SHORT).show();

        // Executa em thread de background — Geocoder é síncrono e bloqueia a UI
        new Thread(() -> {
            try {
                Geocoder geocoder = new Geocoder(this, new Locale("pt", "BR"));
                List<Address> resultados = geocoder.getFromLocationName(textoEndereco, 5);

                runOnUiThread(() -> {
                    if (resultados == null || resultados.isEmpty()) {
                        Toast.makeText(this,
                                "Endereço não encontrado. Tente ser mais específico.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    if (resultados.size() == 1) {
                        // Único resultado → seleciona diretamente
                        aplicarResultadoGeocode(resultados.get(0));
                    } else {
                        // Múltiplos resultados → mostra lista para o usuário escolher
                        String[] opcoes = new String[resultados.size()];
                        for (int i = 0; i < resultados.size(); i++) {
                            opcoes[i] = formatarEndereco(resultados.get(i));
                        }
                        new AlertDialog.Builder(this)
                                .setTitle("Selecione o endereço")
                                .setItems(opcoes, (dialog, which) ->
                                        aplicarResultadoGeocode(resultados.get(which)))
                                .show();
                    }
                });
            } catch (IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(this,
                                "Erro ao buscar endereço. Verifique a conexão.",
                                Toast.LENGTH_LONG).show());
                Log.w(TAG, "Geocoder erro: " + e.getMessage());
            }
        }).start();
    }

    private void aplicarResultadoGeocode(Address address) {
        LatLng latLng = new LatLng(address.getLatitude(), address.getLongitude());
        selecionarLocal(latLng, formatarEndereco(address));
    }

    private String formatarEndereco(Address address) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(address.getAddressLine(i));
        }
        return sb.length() > 0 ? sb.toString() : "Endereço encontrado";
    }

    // ==================== Localização atual ====================

    private void usarMinhaLocalizacao() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOCATION_PERMISSION);
            return;
        }

        Toast.makeText(this, "Obtendo localização…", Toast.LENGTH_SHORT).show();

        fusedClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
                        selecionarLocal(latLng, "Minha localização atual");
                    } else {
                        Toast.makeText(this,
                                "Localização indisponível — ative o GPS e tente novamente",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Erro ao obter localização: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
    }

    /** Centraliza o mapa na localização atual do cuidador (sem selecionar o local). */
    private void centralizarNaLocalizacaoAtual() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        fusedClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && googleMap != null) {
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(location.getLatitude(), location.getLongitude()), 13f));
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            usarMinhaLocalizacao();
        }
    }

    // ==================== Zona existente ====================

    /**
     * Carrega a zona segura já configurada para este paciente (se houver).
     * Pré-popula o mapa e os controles para que o cuidador possa editar.
     */
    private void carregarZonaExistente() {
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uidPaciente)
                .get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;

                    Double lat    = doc.getDouble(FirebaseHelper.Fields.SAFE_ZONE_LATITUDE);
                    Double lng    = doc.getDouble(FirebaseHelper.Fields.SAFE_ZONE_LONGITUDE);
                    Double radius = doc.getDouble(FirebaseHelper.Fields.SAFE_ZONE_RADIUS);

                    if (lat == null || lng == null || radius == null || radius <= 0) return;

                    // Zona existente encontrada
                    zonaExistente        = true;
                    localSelecionado     = new LatLng(lat, lng);
                    int raioCarregado    = raioMaisProximo((int) Math.round(radius));
                    raioSelecionado      = raioCarregado;

                    runOnUiThread(() -> {
                        tvZonaAtiva.setVisibility(View.VISIBLE);
                        btnRemover.setVisibility(View.VISIBLE);
                        btnSalvar.setText("Atualizar zona segura");

                        // Pré-seleciona o botão de raio correspondente
                        selecionarRaio(raioCarregado);

                        tvLocalSelecionado.setText(
                                String.format(Locale.getDefault(),
                                        "Zona configurada\nLat: %.5f  Lng: %.5f  |  Raio: %d m",
                                        lat, lng, (int) Math.round(radius)));
                        tvLocalSelecionado.setTextColor(Color.parseColor("#4CAF50"));

                        // Atualiza mapa se já estiver pronto
                        if (googleMap != null) {
                            googleMap.moveCamera(
                                    CameraUpdateFactory.newLatLngZoom(localSelecionado, 15f));
                            atualizarMarcadorECirculo();
                        }
                        atualizarBotaoSalvar();
                    });

                    Log.d(TAG, "Zona existente carregada: lat=" + lat + " lng=" + lng
                            + " raio=" + radius);
                })
                .addOnFailureListener(e ->
                        Log.w(TAG, "Falha ao carregar zona existente: " + e.getMessage()));
    }

    /**
     * Retorna o raio predefinido mais próximo do valor carregado do Firestore.
     * Evita discrepância visual caso o valor salvo não bata exatamente com os botões.
     */
    private int raioMaisProximo(int raio) {
        int melhor    = RAIOS[0];
        int menorDiff = Math.abs(raio - melhor);
        for (int r : RAIOS) {
            int diff = Math.abs(raio - r);
            if (diff < menorDiff) {
                menorDiff = diff;
                melhor    = r;
            }
        }
        return melhor;
    }

    // ==================== Salvar / Remover ====================

    private void salvarZona() {
        if (localSelecionado == null || raioSelecionado <= 0) return;

        btnSalvar.setEnabled(false);
        btnSalvar.setText("Salvando…");

        FirebaseHelper.salvarZonaSegura(
                uidPaciente,
                localSelecionado.latitude,
                localSelecionado.longitude,
                (float) raioSelecionado,
                new FirebaseHelper.Callback<Void>() {
                    @Override
                    public void onResult(Void v) {
                        zonaExistente = true;
                        runOnUiThread(() -> {
                            Toast.makeText(ConfigurarPontoReferenciaActivity.this,
                                    "✅ Zona segura salva! O monitoramento será ativado em breve.",
                                    Toast.LENGTH_LONG).show();
                            tvZonaAtiva.setVisibility(View.VISIBLE);
                            btnRemover.setVisibility(View.VISIBLE);
                            btnSalvar.setText("Atualizar zona segura");
                            btnSalvar.setEnabled(true);
                            btnSalvar.setAlpha(1f);
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        runOnUiThread(() -> {
                            Toast.makeText(ConfigurarPontoReferenciaActivity.this,
                                    "Erro ao salvar: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                            btnSalvar.setText(zonaExistente ? "Atualizar zona segura"
                                    : "Salvar zona segura");
                            btnSalvar.setEnabled(true);
                            btnSalvar.setAlpha(1f);
                        });
                    }
                });
    }

    private void confirmarRemocao() {
        new AlertDialog.Builder(this)
                .setTitle("Remover zona segura?")
                .setMessage("O alerta de saída de zona ficará inativo para " + nomePaciente
                        + " até você configurar uma nova zona.")
                .setPositiveButton("Remover", (d, w) -> removerZona())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void removerZona() {
        btnRemover.setEnabled(false);

        FirebaseHelper.removerZonaSegura(
                uidPaciente,
                new FirebaseHelper.Callback<Void>() {
                    @Override
                    public void onResult(Void v) {
                        runOnUiThread(() -> {
                            zonaExistente        = false;
                            localSelecionado     = null;
                            raioSelecionado      = 0;

                            // Limpa mapa
                            if (marcador != null) { marcador.remove(); marcador = null; }
                            if (circulo  != null) { circulo.remove();  circulo  = null; }

                            // Reseta UI
                            tvZonaAtiva.setVisibility(View.GONE);
                            btnRemover.setVisibility(View.GONE);
                            btnRemover.setEnabled(true);
                            btnSalvar.setText("Salvar zona segura");
                            btnSalvar.setEnabled(false);
                            btnSalvar.setAlpha(0.5f);

                            // Desmarca botões de raio
                            for (Button b : btnRaios) {
                                b.setBackgroundTintList(
                                        android.content.res.ColorStateList.valueOf(
                                                Color.parseColor("#21262D")));
                            }

                            tvLocalSelecionado.setText(
                                    "Toque no mapa ou busque um endereço para definir o ponto de referência");
                            tvLocalSelecionado.setTextColor(Color.parseColor("#9AA4B2"));

                            Toast.makeText(ConfigurarPontoReferenciaActivity.this,
                                    "Zona segura removida", Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        runOnUiThread(() -> {
                            btnRemover.setEnabled(true);
                            Toast.makeText(ConfigurarPontoReferenciaActivity.this,
                                    "Erro ao remover: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }
}

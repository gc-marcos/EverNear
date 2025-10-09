package com.marcoscarvalho.evernear;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

/**
 * Screen to set up GPS monitoring with radius limit and emergency alerts.
 */
public class LocationSettingsActivity extends FragmentActivity {

    private static final String PREFERENCES_NAME = "location_prefs";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_RADIUS_METERS = "radius_m";
    private static final String KEY_MONITORING_ENABLED = "monitoring_enabled";

    private static final int REQ_LOCATION_PERMISSION = 1001;
    private static final int REQ_SMS_PERMISSION = 1002;

    private TextView currentLocationText;
    private TextView statusText;
    private EditText radiusInput;
    private Button gpsButton;
    private Button mapButton;
    private Button startMonitoringButton;
    private Button stopMonitoringButton;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Double selectedLatitude;
    private Double selectedLongitude;
    private int radiusMeters;
    private boolean isMonitoring = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_settings);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        initializeViews();
        loadSettings();
        setupLocationCallback();
        setupButtons();
    }

    private void initializeViews() {
        currentLocationText = findViewById(R.id.text_current_location);
        statusText = findViewById(R.id.text_status);
        radiusInput = findViewById(R.id.input_radius);
        gpsButton = findViewById(R.id.button_use_gps);
        mapButton = findViewById(R.id.button_use_map);
        startMonitoringButton = findViewById(R.id.button_start_monitoring);
        stopMonitoringButton = findViewById(R.id.button_stop_monitoring);
    }

    private void loadSettings() {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        String lat = preferences.getString(KEY_LATITUDE, "");
        String lon = preferences.getString(KEY_LONGITUDE, "");
        radiusMeters = preferences.getInt(KEY_RADIUS_METERS, 100);
        isMonitoring = preferences.getBoolean(KEY_MONITORING_ENABLED, false);

        if (!lat.isEmpty() && !lon.isEmpty()) {
            selectedLatitude = Double.parseDouble(lat);
            selectedLongitude = Double.parseDouble(lon);
            currentLocationText.setText("Ponto inicial: " + lat + ", " + lon);
        }
        
        radiusInput.setText(String.valueOf(radiusMeters));
        updateMonitoringStatus();
    }

    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                
                Location currentLocation = locationResult.getLastLocation();
                if (currentLocation != null && selectedLatitude != null && selectedLongitude != null) {
                    Location referenceLocation = new Location("reference");
                    referenceLocation.setLatitude(selectedLatitude);
                    referenceLocation.setLongitude(selectedLongitude);
                    
                    float distance = currentLocation.distanceTo(referenceLocation);
                    
                    if (distance > radiusMeters) {
                        // User has exceeded the radius limit
                        sendEmergencyAlert(currentLocation);
                        statusText.setText("ALERTA: Fora do raio permitido!");
                        statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    } else {
                        statusText.setText("Dentro do raio permitido (" + String.format("%.0f", distance) + "m)");
                        statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                    }
                }
            }
        };
    }

    private void setupButtons() {
        gpsButton.setOnClickListener(v -> getCurrentLocation());
        mapButton.setOnClickListener(v -> showMapFragment());
        startMonitoringButton.setOnClickListener(v -> startMonitoring());
        stopMonitoringButton.setOnClickListener(v -> stopMonitoring());
    }

    private void getCurrentLocation() {
        if (!checkLocationPermission()) return;
        
        try {
            CancellationTokenSource cts = new CancellationTokenSource();
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        selectedLatitude = location.getLatitude();
                        selectedLongitude = location.getLongitude();
                        currentLocationText.setText("Ponto inicial: " + selectedLatitude + ", " + selectedLongitude);
                        saveLocation();
                    } else {
                        Toast.makeText(this, "Não foi possível obter a localização.", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Erro ao obter localização.", Toast.LENGTH_SHORT).show());
        } catch (SecurityException se) {
            Toast.makeText(this, "Permissão de localização necessária.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showMapFragment() {
        MapFragment mapFragment = new MapFragment();
        mapFragment.setLocationListener(new MapFragment.OnLocationSelectedListener() {
            @Override
            public void onLocationSelected(double latitude, double longitude) {
                selectedLatitude = latitude;
                selectedLongitude = longitude;
                currentLocationText.setText("Ponto inicial: " + latitude + ", " + longitude);
                saveLocation();
                
                getSupportFragmentManager().beginTransaction()
                    .remove(mapFragment)
                    .commit();
            }
        });
        
        getSupportFragmentManager().beginTransaction()
            .replace(android.R.id.content, mapFragment)
            .commit();
    }

    private void startMonitoring() {
        if (selectedLatitude == null || selectedLongitude == null) {
            Toast.makeText(this, "Defina um ponto inicial primeiro.", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String radiusText = radiusInput.getText().toString().trim();
        if (radiusText.isEmpty()) {
            Toast.makeText(this, "Defina o raio limite.", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            radiusMeters = Integer.parseInt(radiusText);
            if (radiusMeters <= 0) {
                Toast.makeText(this, "Raio deve ser positivo.", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Raio inválido.", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!checkLocationPermission()) return;
        if (!checkSmsPermission()) return;
        
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(3000)
            .build();
        
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
            isMonitoring = true;
            saveMonitoringState();
            updateMonitoringStatus();
            Toast.makeText(this, "Monitoramento iniciado!", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "Permissão de localização necessária.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopMonitoring() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
        isMonitoring = false;
        saveMonitoringState();
        updateMonitoringStatus();
        statusText.setText("Monitoramento parado");
        statusText.setTextColor(getResources().getColor(android.R.color.darker_gray));
        Toast.makeText(this, "Monitoramento parado!", Toast.LENGTH_SHORT).show();
    }

    private void sendEmergencyAlert(Location currentLocation) {
        SharedPreferences prefs = getSharedPreferences("evernear_prefs", MODE_PRIVATE);
        String contact1 = prefs.getString("emergency_contact_1", "");
        String contact2 = prefs.getString("emergency_contact_2", "");
        String contact3 = prefs.getString("emergency_contact_3", "");
        
        String message = "🚨 ALERTA DE EMERGÊNCIA 🚨\n" +
                        "Localização atual: " + currentLocation.getLatitude() + ", " + currentLocation.getLongitude() + "\n" +
                        "Tempo: " + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date());
        
        try {
            SmsManager smsManager = SmsManager.getDefault();
            
            if (!contact1.isEmpty() && contact1.contains("\n")) {
                String phone1 = contact1.split("\n")[1];
                smsManager.sendTextMessage(phone1, null, message, null, null);
            }
            if (!contact2.isEmpty() && contact2.contains("\n")) {
                String phone2 = contact2.split("\n")[1];
                smsManager.sendTextMessage(phone2, null, message, null, null);
            }
            if (!contact3.isEmpty() && contact3.contains("\n")) {
                String phone3 = contact3.split("\n")[1];
                smsManager.sendTextMessage(phone3, null, message, null, null);
            }
            
            Toast.makeText(this, "Alertas de emergência enviados!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao enviar alertas: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveLocation() {
        SharedPreferences prefs = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_LATITUDE, String.valueOf(selectedLatitude))
            .putString(KEY_LONGITUDE, String.valueOf(selectedLongitude))
            .putInt(KEY_RADIUS_METERS, radiusMeters)
            .apply();
    }

    private void saveMonitoringState() {
        SharedPreferences prefs = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        prefs.edit()
            .putBoolean(KEY_MONITORING_ENABLED, isMonitoring)
            .apply();
    }

    private void updateMonitoringStatus() {
        if (isMonitoring) {
            startMonitoringButton.setVisibility(View.GONE);
            stopMonitoringButton.setVisibility(View.VISIBLE);
            statusText.setText("Monitoramento ativo");
            statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            startMonitoringButton.setVisibility(View.VISIBLE);
            stopMonitoringButton.setVisibility(View.GONE);
            statusText.setText("Monitoramento parado");
            statusText.setTextColor(getResources().getColor(android.R.color.darker_gray));
        }
    }

    private boolean checkLocationPermission() {
        boolean fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        
        if (!fineGranted && !coarseGranted) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                REQ_LOCATION_PERMISSION);
            return false;
        }
        return true;
    }

    private boolean checkSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS}, REQ_SMS_PERMISSION);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQ_LOCATION_PERMISSION) {
            boolean granted = false;
            if (grantResults != null && grantResults.length > 0) {
                for (int result : grantResults) {
                    if (result == PackageManager.PERMISSION_GRANTED) {
                        granted = true;
                        break;
                    }
                }
            }
            if (!granted) {
                Toast.makeText(this, "Permissão de localização negada.", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_SMS_PERMISSION) {
            if (grantResults == null || grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permissão de SMS negada.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isMonitoring) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}
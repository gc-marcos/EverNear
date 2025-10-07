package com.marcoscarvalho.evernear;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

/**
 * Screen to define initial location using device GPS and a geofence radius.
 */
public class LocationSettingsActivity extends FragmentActivity {

    private static final String PREFERENCES_NAME = "location_prefs";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_RADIUS_METERS = "radius_m";

    private static final int REQ_LOCATION_PERMISSION = 1001;

    private TextView currentLocationText;
    private EditText radiusInput;
    private Button saveButton;
    private Button useGpsButton;
    private Button useMapButton;

    private FusedLocationProviderClient fusedLocationClient;
    private Double selectedLatitude;
    private Double selectedLongitude;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_settings);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        initializeViews();
        populateExistingValues();
        setupSaveAction();
        setupUseGpsAction();
        setupUseMapAction();
    }

    private void initializeViews() {
        currentLocationText = findViewById(R.id.text_current_location);
        radiusInput = findViewById(R.id.input_radius);
        saveButton = findViewById(R.id.button_save_location);
        useGpsButton = findViewById(R.id.button_use_gps);
        useMapButton = findViewById(R.id.button_use_map);
    }

    private void populateExistingValues() {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        String lat = preferences.getString(KEY_LATITUDE, "");
        String lon = preferences.getString(KEY_LONGITUDE, "");
        int radius = preferences.getInt(KEY_RADIUS_METERS, 100);

        if (!lat.isEmpty() && !lon.isEmpty()) {
            currentLocationText.setText("Local atual: " + lat + ", " + lon);
            try {
                selectedLatitude = Double.parseDouble(lat);
                selectedLongitude = Double.parseDouble(lon);
            } catch (NumberFormatException ignored) {}
        }
        radiusInput.setText(String.valueOf(radius));
    }

    private void setupUseGpsAction() {
        useGpsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureLocationAndFetch();
            }
        });
    }

    private void setupUseMapAction() {
        useMapButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMapFragment();
            }
        });
    }

    private void showMapFragment() {
        MapFragment mapFragment = new MapFragment();
        mapFragment.setLocationListener(new MapFragment.OnLocationSelectedListener() {
            @Override
            public void onLocationSelected(double latitude, double longitude) {
                selectedLatitude = latitude;
                selectedLongitude = longitude;
                currentLocationText.setText("Local atual: " + latitude + ", " + longitude);
                
                // Remove the map fragment
                getSupportFragmentManager().beginTransaction()
                    .remove(mapFragment)
                    .commit();
            }
        });
        
        getSupportFragmentManager().beginTransaction()
            .replace(android.R.id.content, mapFragment)
            .commit();
    }

    private void ensureLocationAndFetch() {
        boolean fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fineGranted && !coarseGranted) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                REQ_LOCATION_PERMISSION);
            return;
        }
        fetchCurrentLocation();
    }

    private void fetchCurrentLocation() {
        try {
            CancellationTokenSource cts = new CancellationTokenSource();
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        selectedLatitude = location.getLatitude();
                        selectedLongitude = location.getLongitude();
                        currentLocationText.setText("Local atual: " + selectedLatitude + ", " + selectedLongitude);
                    } else {
                        Toast.makeText(this, "Não foi possível obter a localização.", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Erro ao obter localização.", Toast.LENGTH_SHORT).show());
        } catch (SecurityException se) {
            Toast.makeText(this, "Permissão de localização necessária.", Toast.LENGTH_SHORT).show();
        }
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
            if (granted) {
                fetchCurrentLocation();
            } else {
                Toast.makeText(this, "Permissão negada.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupSaveAction() {
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String radiusText = radiusInput.getText().toString().trim();

                if (selectedLatitude == null || selectedLongitude == null) {
                    Toast.makeText(LocationSettingsActivity.this, "Use o GPS para definir o ponto inicial.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (radiusText.isEmpty()) {
                    Toast.makeText(LocationSettingsActivity.this, "Informe o raio (m).", Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    int radius = Integer.parseInt(radiusText);
                    if (radius <= 0) {
                        Toast.makeText(LocationSettingsActivity.this, "Raio deve ser positivo (m).", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    SharedPreferences prefs = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
                    prefs.edit()
                        .putString(KEY_LATITUDE, String.valueOf(selectedLatitude))
                        .putString(KEY_LONGITUDE, String.valueOf(selectedLongitude))
                        .putInt(KEY_RADIUS_METERS, radius)
                        .apply();

                    Toast.makeText(LocationSettingsActivity.this, "Localização salva.", Toast.LENGTH_SHORT).show();
                    finish();
                } catch (NumberFormatException ex) {
                    Toast.makeText(LocationSettingsActivity.this, "Insira um raio válido.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}



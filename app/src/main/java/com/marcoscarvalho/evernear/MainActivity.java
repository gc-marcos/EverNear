package com.marcoscarvalho.evernear;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.viewpager2.widget.ViewPager2;

public class MainActivity extends Activity {
    
    private ImageView heartIcon;
    private ImageView heartRateButton;
    private ImageView locationButton;
    private ImageView contactsButton;
    private TextView batteryStatus;
    private TextView connectionStatus;
    private ViewPager2 viewPager;
    private Animation pulseAnimation;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initializeViews();
        setupHeartAnimation();
        setupStatusIndicators();
        setupViewPager();
    }
    
    private void initializeViews() {
        heartIcon = findViewById(R.id.heart_icon);
        heartRateButton = findViewById(R.id.heart_rate_button);
        locationButton = findViewById(R.id.location_button);
        contactsButton = findViewById(R.id.contacts_button);
        batteryStatus = findViewById(R.id.battery_status);
        connectionStatus = findViewById(R.id.connection_status);
        viewPager = findViewById(R.id.view_pager);
    }
    
    private void setupHeartAnimation() {
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.heart_pulse);
        heartRateButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, HeartRateSettingsActivity.class);
            startActivity(intent);
        });
        locationButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LocationSettingsActivity.class);
            startActivity(intent);
        });
        contactsButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, EmergencyContactsActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (heartIcon != null && pulseAnimation != null) {
            heartIcon.startAnimation(pulseAnimation);
        }
    }

    @Override
    protected void onPause() {
        if (heartIcon != null) {
            heartIcon.clearAnimation();
        }
        super.onPause();
    }
    
    private void setupStatusIndicators() {
        // Simular status da bateria e conexão
        batteryStatus.setText("85%");
        connectionStatus.setText("●");
    }
    
    private void setupViewPager() {
        // Configurar ViewPager para navegação horizontal
        MainPagerAdapter adapter = new MainPagerAdapter(this);
        viewPager.setAdapter(adapter);
        
        // Configurar orientação horizontal para smartwatch
        viewPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
        
        // Listener para detectar mudanças de página
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                // Aqui você pode adicionar lógica para cada página
            }
        });
    }
}

package com.marcoscarvalho.evernear;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

/**
 * Fragment with Google Maps for location selection.
 */
public class MapFragment extends Fragment implements OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private MapView mapView;
    private GoogleMap googleMap;
    private Marker selectedMarker;
    private OnLocationSelectedListener locationListener;

    public interface OnLocationSelectedListener {
        void onLocationSelected(double latitude, double longitude);
    }

    public void setLocationListener(OnLocationSelectedListener listener) {
        this.locationListener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_map, container, false);
        
        mapView = view.findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
        
        return view;
    }

    @Override
    public void onMapReady(GoogleMap map) {
        googleMap = map;
        googleMap.setOnMapClickListener(this);
        
        // Set initial location (São Paulo as default)
        LatLng defaultLocation = new LatLng(-23.5505, -46.6333);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 12));
    }

    @Override
    public void onMapClick(LatLng latLng) {
        if (googleMap != null) {
            // Remove previous marker
            if (selectedMarker != null) {
                selectedMarker.remove();
            }
            
            // Add new marker
            selectedMarker = googleMap.addMarker(new MarkerOptions()
                .position(latLng)
                .title("Ponto selecionado"));
            
            // Notify listener
            if (locationListener != null) {
                locationListener.onLocationSelected(latLng.latitude, latLng.longitude);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mapView != null) {
            mapView.onPause();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mapView != null) {
            mapView.onDestroy();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) {
            mapView.onLowMemory();
        }
    }
}




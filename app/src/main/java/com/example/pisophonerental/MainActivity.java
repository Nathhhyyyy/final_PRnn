package com.example.pisophonerental;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TARGET_SSID = "\"PisoPhone_Vendo\""; 
    private static final String ADMIN_PASSWORD = "admin_bypass_123";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private TextView txtStatus;
    private boolean isAdminBypassed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // Setup minimalist UI purely programmatically to skip saving layout XML files
        androidx.widget.RelativeLayout layout = new androidx.widget.RelativeLayout(this);
        layout.setBackgroundColor(0xFF000000);

        txtStatus = new TextView(this);
        txtStatus.setText("PISOPHONE RENTAL\n\nInsert Coin to Start Using Phone");
        txtStatus.setTextColor(0xFFFFFFFF);
        txtStatus.setTextSize(24);
        txtStatus.setGravity(android.view.Gravity.CENTER);
        
        androidx.widget.RelativeLayout.LayoutParams params = new androidx.widget.RelativeLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(androidx.widget.RelativeLayout.CENTER_IN_PARENT);
        layout.addView(txtStatus, params);

        // Invisible Admin button in the top right corner
        View adminView = new View(this);
        androidx.widget.RelativeLayout.LayoutParams btnParams = new androidx.widget.RelativeLayout.LayoutParams(150, 150);
        btnParams.addRule(androidx.widget.RelativeLayout.ALIGN_PARENT_TOP);
        btnParams.addRule(androidx.widget.RelativeLayout.ALIGN_PARENT_END);
        adminView.setOnClickListener(v -> onAdminClick());
        layout.addView(adminView, btnParams);

        setContentView(layout);
        
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        hideSystemUI();
        checkPermissions();
        startNetworkTracking();
    }

    private void checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void startNetworkTracking() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                super.onCapabilitiesChanged(network, capabilities);
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                String currentSSID = wifiInfo.getSSID();

                if (currentSSID.equals(TARGET_SSID)) {
                    boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (hasInternet && !isAdminBypassed) {
                        runOnUiThread(() -> moveTaskToBack(true));
                    } else if (!hasInternet) {
                        runOnUiThread(() -> bringToFront());
                    }
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                runOnUiThread(() -> bringToFront());
            }
        };
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    }

    private void bringToFront() {
        hideSystemUI();
        if (!isAdminBypassed) {
            this.recreate();
        }
    }

    private void onAdminClick() {
        final EditText input = new EditText(this);
        new AlertDialog.Builder(this)
                .setTitle("Admin Bypass")
                .setMessage("Input security pass:")
                .setView(input)
                .setPositiveButton("Bypass", (dialog, which) -> {
                    if (input.getText().toString().equals(ADMIN_PASSWORD)) {
                        isAdminBypassed = true;
                        finish();
                    } else {
                        Toast.makeText(MainActivity.this, "Invalid Password", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    public void onBackPressed() {}

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
}

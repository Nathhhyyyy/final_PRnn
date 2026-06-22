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
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
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
        
        // Main Container Layout (Deep Dark Theme)
        RelativeLayout layout = new RelativeLayout(this);
        layout.setBackgroundColor(0xFF000000);
        layout.setPadding(50, 50, 50, 50);

        // Center Status Typography
        txtStatus = new TextView(this);
        txtStatus.setText("PISOPHONE RENTAL\n\nInsert Coin to Start Using Phone");
        txtStatus.setTextColor(0xFFFFFFFF);
        txtStatus.setTextSize(24);
        txtStatus.setGravity(android.view.Gravity.CENTER);
        
        RelativeLayout.LayoutParams textParams = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        layout.addView(txtStatus, textParams);

        // Visible Minimalist Admin Button at the bottom
        Button btnAdmin = new Button(this);
        btnAdmin.setText("ADMIN LOGIN");
        btnAdmin.setTextColor(0xFF888888); // Subtle gray so it stays low-key for customers
        btnAdmin.setBackgroundColor(0x00000000); // Transparent background
        btnAdmin.setOnClickListener(v -> onAdminClick());
        
        RelativeLayout.LayoutParams btnParams = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        btnParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        btnParams.setMargins(0, 0, 0, 40); // Lift it slightly above the bottom edge
        layout.addView(btnAdmin, btnParams);

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
                
                String currentSSID = "";
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                    if (wifiInfo != null) {
                        currentSSID = wifiInfo.getSSID();
                    }
                }

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
    public void onBackPressed() {
        // Disabled back operations
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
    }
}

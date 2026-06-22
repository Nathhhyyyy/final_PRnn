package com.example.pisophonerental;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    private static final String TARGET_SSID = "\"PISOWIFI\""; 
    private static final String ADMIN_PASSWORD = "admin_bypass_123";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;
    private static final int OVERLAY_PERMISSION_REQ_CODE = 102;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    
    private WindowManager windowManager;
    private RelativeLayout overlayLayout;
    private boolean isOverlayShowing = false;
    private boolean isAdminBypassed = false;
    
    private int safetyClickCount = 0;
    private long lastClickTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        View emptyView = new View(this);
        setContentView(emptyView);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        checkOverlayPermission();
        checkPermissions();
        startNetworkTracking();
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE);
            } else {
                showSystemOverlay();
            }
        } else {
            showSystemOverlay();
        }
    }

    private void showSystemOverlay() {
        if (isOverlayShowing || isAdminBypassed) return;

        runOnUiThread(() -> {
            overlayLayout = new RelativeLayout(this);
            overlayLayout.setBackgroundColor(0xFF000000);
            
            overlayLayout.setOnClickListener(v -> {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < 500) {
                    safetyClickCount++;
                    if (safetyClickCount >= 5) {
                        Toast.makeText(this, "Safety Escape Triggered!", Toast.LENGTH_SHORT).show();
                        isAdminBypassed = true;
                        hideSystemOverlay();
                        finish();
                    }
                } else {
                    safetyClickCount = 1;
                }
                lastClickTime = currentTime;
            });

            TextView txtStatus = new TextView(this);
            txtStatus.setText("PISOPHONE RENTAL\n\nInsert Coin to Start Using Phone");
            txtStatus.setTextColor(0xFFFFFFFF);
            txtStatus.setTextSize(24);
            txtStatus.setGravity(Gravity.CENTER);
            
            RelativeLayout.LayoutParams textParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.addRule(RelativeLayout.CENTER_IN_PARENT);
            overlayLayout.addView(txtStatus, textParams);

            Button btnAdmin = new Button(this);
            btnAdmin.setText("ADMIN LOGIN");
            btnAdmin.setTextColor(0xFF888888);
            btnAdmin.setBackgroundColor(0x00000000);
            btnAdmin.setOnClickListener(v -> onAdminClick());
            
            RelativeLayout.LayoutParams btnParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            btnParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
            btnParams.setMargins(0, 0, 0, 160); 
            overlayLayout.addView(btnAdmin, btnParams);

            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_PHONE;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN 
                    | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT
            );
            
            params.dimAmount = 1.0f; 
            params.gravity = Gravity.CENTER;

            windowManager.addView(overlayLayout, params);
            isOverlayShowing = true;
        });
    }

    private void hideSystemOverlay() {
        if (!isOverlayShowing || overlayLayout == null) return;
        runOnUiThread(() -> {
            try {
                windowManager.removeView(overlayLayout);
                isOverlayShowing = false;
            } catch (Exception e) {
                // Safe handle
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && !isAdminBypassed && isOverlayShowing) {
            // Re-evaluate if we are in captive portal mode before pulling front
            checkCurrentNetworkStateAndEnforce();
        }
    }

    private void checkCurrentNetworkStateAndEnforce() {
        if (isAdminBypassed) return;
        
        Network activeNet = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNet);
        
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            boolean isCaptivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
            boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            
            // If the portal is active, DO NOT force the app to the front. Let the user tap the portal!
            if (isCaptivePortal || hasInternet) {
                hideSystemOverlay();
                return;
            }
        }
        
        // Otherwise, pull back to front
        forceAppToFront();
    }

    private void forceAppToFront() {
        if (isAdminBypassed) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT 
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
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
                    boolean isCaptivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);

                    // Drop the overlay if there is internet OR if a portal login option is waiting
                    if ((hasInternet || isCaptivePortal) && !isAdminBypassed) {
                        hideSystemOverlay();
                    } else if (!hasInternet && !isCaptivePortal) {
                        showSystemOverlay();
                        forceAppToFront();
                    }
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                showSystemOverlay();
                forceAppToFront();
            }
        };
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    }

    private void onAdminClick() {
        final EditText input = new EditText(this);
        
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Admin Bypass")
                .setMessage("Input security pass:")
                .setView(input)
                .setPositiveButton("Bypass", (d, which) -> {
                    if (input.getText().toString().equals(ADMIN_PASSWORD)) {
                        isAdminBypassed = true;
                        hideSystemOverlay();
                        finish();
                    } else {
                        Toast.makeText(MainActivity.this, "Invalid Password", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .create();

        if (dialog.getWindow() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
            }
        }
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            showSystemOverlay();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideSystemOverlay();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
    }
}

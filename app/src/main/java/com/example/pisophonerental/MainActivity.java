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
import android.view.KeyEvent;
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
            overlayLayout = new RelativeLayout(this) {
                @Override
                public boolean dispatchKeyEvent(KeyEvent event) {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                        return true; 
                    }
                    return super.dispatchKeyEvent(event);
                }
            };
            overlayLayout.setBackgroundColor(0xFF000000);

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
            btnParams.setMargins(0, 0, 0, 120); // Pushed up slightly so it doesn't overlap navigation buttons
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
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
            );
            
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
                // Catch view references
            }
        });
    }

    // --- ANTI-EXIT RECOVERY SYSTEM ---
    
    // Catch when Home/Recent button forces the app to lose focus
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && !isAdminBypassed && isOverlayShowing) {
            forceAppToFront();
        }
    }

    // Catch when the user leaves the application space completely
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!isAdminBypassed && isOverlayShowing) {
            forceAppToFront();
        }
    }

    // Catch if another app try to open over it, force it right back
    @Override
    protected void onPause() {
        super.onPause();
        if (!isAdminBypassed && isOverlayShowing) {
            forceAppToFront();
        }
    }

    private void forceAppToFront() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT 
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    // ---------------------------------

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
                        hideSystemOverlay();
                    } else if (!hasInternet) {
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
        new AlertDialog.Builder(this)
                .setTitle("Admin Bypass")
                .setMessage("Input security pass:")
                .setView(input)
                .setPositiveButton("Bypass", (dialog, which) -> {
                    if (input.getText().toString().equals(ADMIN_PASSWORD)) {
                        isAdminBypassed = true;
                        hideSystemOverlay();
                        finish();
                    } else {
                        Toast.makeText(MainActivity.this, "Invalid Password", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
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

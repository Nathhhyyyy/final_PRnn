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
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final String TARGET_SSID = "\"PISOWIFI\""; 
    private static final String ADMIN_PASSWORD = "admin_bypass_123";
    private static final String PORTAL_URL = "http://10.0.0.1";
    private static final String STATUS_URL = "http://10.0.0.1/status"; 

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;
    private static final int OVERLAY_PERMISSION_REQ_CODE = 102;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    
    private WindowManager windowManager;
    private RelativeLayout overlayLayout;
    private View statusBarShield;
    
    private WebView portalWebView;
    private TextView txtStatus;
    private Button btnRetry; 
    
    private boolean isOverlayShowing = false;
    private boolean isAdminBypassed = false;
    
    private CountDownTimer rentalTimer;
    private boolean isTimerRunning = false;
    private long currentRentalTimeLeftMs = 0;
    
    private Handler extensionCheckHandler = new Handler(Looper.getMainLooper());
    private Runnable extensionCheckRunnable;
    private long lastKnownRouterTimeMs = 0;

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
        setupExtensionChecker();
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
                        stopRentalTimer();
                        hideSystemOverlay();
                        finish();
                    }
                } else {
                    safetyClickCount = 1;
                }
                lastClickTime = currentTime;
            });

            // 1. Setup Embedded Browser Panel
            portalWebView = new WebView(this);
            RelativeLayout.LayoutParams webParams = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            webParams.setMargins(0, 0, 0, 320); 
            portalWebView.setVisibility(View.GONE); 
            
            WebSettings webSettings = portalWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            
            portalWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    runOnUiThread(() -> {
                        if (btnRetry != null) btnRetry.setVisibility(View.VISIBLE);
                    });
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (!url.equals("about:blank") && btnRetry != null) {
                        btnRetry.setVisibility(View.GONE);
                    }
                }
            });
            overlayLayout.addView(portalWebView, webParams);

            // 2. Setup Central Status Text Window
            txtStatus = new TextView(this);
            txtStatus.setText("PISO PHONE RENTAL\n\nInsert Coin to Start Using Phone");
            txtStatus.setTextColor(0xFFFFFFFF);
            txtStatus.setTextSize(24);
            txtStatus.setGravity(Gravity.CENTER);
            
            RelativeLayout.LayoutParams textParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.addRule(RelativeLayout.CENTER_IN_PARENT);
            overlayLayout.addView(txtStatus, textParams);

            // 3. Setup Manual Refresh/Retry Connection Button Link
            btnRetry = new Button(this);
            btnRetry.setText("RETRY CONNECTION");
            btnRetry.setTextColor(0xFFFFFFFF);
            btnRetry.setBackgroundColor(0x44FFFFFF); 
            btnRetry.setVisibility(View.GONE); 
            btnRetry.setOnClickListener(v -> {
                if (portalWebView != null) {
                    btnRetry.setVisibility(View.GONE);
                    portalWebView.loadUrl(PORTAL_URL);
                }
            });
            RelativeLayout.LayoutParams retryParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            retryParams.addRule(RelativeLayout.CENTER_IN_PARENT);
            retryParams.setMargins(0, 200, 0, 0); 
            overlayLayout.addView(btnRetry, retryParams);

            // 4. Setup Admin Login Control Button (Added last to float over WebView)
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

            int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT
            );
            
            params.dimAmount = 1.0f; 
            params.gravity = Gravity.CENTER;

            windowManager.addView(overlayLayout, params);
            isOverlayShowing = true;

            blockNotificationBar(layoutType);
        });
    }

    private void blockNotificationBar(int layoutType) {
        if (statusBarShield != null) return;

        statusBarShield = new View(this);
        int statusBarHeight = (int) (25 * getResources().getDisplayMetrics().density);

        WindowManager.LayoutParams statusParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                statusBarHeight,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        statusParams.gravity = Gravity.TOP;

        try {
            windowManager.addView(statusBarShield, statusParams);
        } catch (Exception e) {
            // Layout trap
        }
    }

    private void hideSystemOverlay() {
        if (!isOverlayShowing || overlayLayout == null) return;
        runOnUiThread(() -> {
            try {
                windowManager.removeView(overlayLayout);
                if (statusBarShield != null) {
                    windowManager.removeView(statusBarShield);
                    statusBarShield = null;
                }
                isOverlayShowing = false;
            } catch (Exception e) {
                // Window protection
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && !isAdminBypassed && (isOverlayShowing || !isTimerRunning)) {
            forceAppToFront();
        }
    }

    private void forceAppToFront() {
        if (isAdminBypassed) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT 
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
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

                    runOnUiThread(() -> {
                        if (hasInternet && !isAdminBypassed) {
                            if (!isTimerRunning) {
                                fetchRouterSessionAndApplyFormula(false);
                            }
                            startExtensionLoop();
                        } else if (isCaptivePortal && !isAdminBypassed) {
                            stopRentalTimer(); 
                            stopExtensionLoop();
                            if (txtStatus != null) txtStatus.setVisibility(View.GONE);
                            if (portalWebView != null) {
                                portalWebView.setVisibility(View.VISIBLE);
                                portalWebView.loadUrl(PORTAL_URL);
                            }
                        } else if (!isTimerRunning) {
                            resetToLockView();
                            showSystemOverlay();
                        }
                    });
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                runOnUiThread(() -> {
                    stopRentalTimer();
                    stopExtensionLoop();
                    resetToLockView();
                    showSystemOverlay();
                    forceAppToFront();
                });
            }
        };
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    }

    private void setupExtensionChecker() {
        extensionCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (isTimerRunning && !isAdminBypassed) {
                    fetchRouterSessionAndApplyFormula(true); 
                }
                extensionCheckHandler.postDelayed(this, 30000); 
            }
        };
    }

    private void startExtensionLoop() {
        extensionCheckHandler.removeCallbacks(extensionCheckRunnable);
        extensionCheckHandler.postDelayed(extensionCheckRunnable, 30000);
    }

    private void stopExtensionLoop() {
        extensionCheckHandler.removeCallbacks(extensionCheckRunnable);
    }

    private void fetchRouterSessionAndApplyFormula(boolean isCheckingForExtension) {
        new Thread(() -> {
            try {
                Document doc = Jsoup.connect(STATUS_URL).timeout(4000).get();
                Element timeElement = doc.select("td:contains(remain), td:contains(Time Left), .time-left").first();
                
                if (timeElement != null) {
                    String currentRouterTimeStr = timeElement.nextElementSibling() != null ? 
                            timeElement.nextElementSibling().text().toLowerCase() : timeElement.text().toLowerCase();

                    long currentRouterMs = parseTimeToMillis(currentRouterTimeStr);

                    if (isCheckingForExtension) {
                        if (currentRouterMs > lastKnownRouterTimeMs) {
                            long differenceMs = currentRouterMs - lastKnownRouterTimeMs;
                            long reducedExtensionMs = differenceMs / 2;

                            lastKnownRouterTimeMs = currentRouterMs;
                            runOnUiThread(() -> {
                                if (isTimerRunning) {
                                    long updatedDuration = currentRentalTimeLeftMs + reducedExtensionMs;
                                    stopRentalTimer();
                                    startRentalTimer(updatedDuration);
                                    Toast.makeText(MainActivity.this, "Time Extended (50% Rate Applied)!", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } else {
                            lastKnownRouterTimeMs = currentRouterMs;
                        }
                    } else {
                        lastKnownRouterTimeMs = currentRouterMs;
                        long halvedInitialSessionMs = currentRouterMs / 2;

                        runOnUiThread(() -> {
                            startRentalTimer(halvedInitialSessionMs);
                            hideSystemOverlay();
                        });
                    }
                }
            } catch (IOException e) {
                // Connection protection
            }
        }).start();
    }

    private long parseTimeToMillis(String timeStr) {
        long totalMs = 0;
        try {
            if (timeStr.contains(":")) {
                String[] units = timeStr.split(":");
                long hours = Long.parseLong(units[0].trim());
                long minutes = Long.parseLong(units[1].trim());
                long seconds = Long.parseLong(units[2].trim());
                totalMs = ((hours * 3600) + (minutes * 60) + seconds) * 1000;
            } else {
                String temp = timeStr.trim();
                if (temp.contains("h")) {
                    String[] parts = temp.split("h");
                    totalMs += Long.parseLong(parts[0].replaceAll("[^0-9]", "")) * 3600 * 1000;
                    if (parts.length > 1 && parts[1].contains("m")) {
                        totalMs += Long.parseLong(parts[1].replaceAll("[^0-9]", "")) * 60 * 1000;
                    }
                } else if (temp.contains("m")) {
                    totalMs += Long.parseLong(temp.replaceAll("[^0-9]", "")) * 60 * 1000;
                }
            }
        } catch (Exception e) {
            return 30 * 60 * 1000; 
        }
        return totalMs;
    }

    private void startRentalTimer(long sessionDurationMs) {
        isTimerRunning = true;
        currentRentalTimeLeftMs = sessionDurationMs;

        rentalTimer = new CountDownTimer(sessionDurationMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                currentRentalTimeLeftMs = millisUntilFinished;
            }

            @Override
            public void onFinish() {
                isTimerRunning = false;
                stopExtensionLoop();
                resetToLockView();
                showSystemOverlay();
                forceAppToFront();
                Toast.makeText(MainActivity.this, "Rental Session Expired! Please insert coin.", Toast.LENGTH_LONG).show();
            }
        }.start();
    }

    private void stopRentalTimer() {
        if (rentalTimer != null) {
            rentalTimer.cancel();
        }
        isTimerRunning = false;
    }

    private void resetToLockView() {
        if (portalWebView != null) {
            portalWebView.setVisibility(View.GONE);
            portalWebView.loadUrl("about:blank");
        }
        if (btnRetry != null) {
            btnRetry.setVisibility(View.GONE);
        }
        if (txtStatus != null) {
            txtStatus.setText("PISO PHONE RENTAL\n\nSession Expired!\nInsert Coin to Extend Time");
            txtStatus.setVisibility(View.VISIBLE);
        }
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
                        stopRentalTimer();
                        stopExtensionLoop();
                        hideSystemOverlay();
                        finish();
                    } else {
                        Toast.makeText(MainActivity.this, "Invalid Password", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setType((Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
    }

    private void checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        }
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
        stopRentalTimer();
        stopExtensionLoop();
        hideSystemOverlay();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
    }
}

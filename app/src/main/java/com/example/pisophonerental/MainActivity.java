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
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TARGET_SSID = "\"PISOWIFI\""; 
    private static final String ADMIN_PASSWORD = "admin_bypass_123";
    private static final String PORTAL_URL = "http://10.0.0.1";
    private static final String LOGOUT_URL = "http://10.0.0.1/logout"; 

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;
    private static final int OVERLAY_PERMISSION_REQ_CODE = 102;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    
    private WindowManager windowManager;
    private RelativeLayout overlayLayout;
    private View statusBarShield;
    private TextView floatingTimerView; 
    
    private WebView portalWebView;
    private TextView txtStatus;
    private Button btnRetry; 
    
    private boolean isOverlayShowing = false;
    private boolean isAdminBypassed = false;
    
    private CountDownTimer rentalTimer;
    private boolean isTimerRunning = false;
    private long currentRentalTimeLeftMs = 0;
    
    private Handler loopHandler = new Handler(Looper.getMainLooper());
    private Runnable sessionLoopRunnable;
    private long lastKnownRouterTimeMs = -1; 

    private int safetyClickCount = 0;
    private long lastClickTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        View emptyView = new View(this);
        setContentView(emptyView);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        setupSessionLoop();
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
                setupFloatingTimer();
                showSystemOverlay();
            }
        } else {
            setupFloatingTimer();
            showSystemOverlay();
        }
    }

    private void setupFloatingTimer() {
        if (floatingTimerView != null) return;
        
        runOnUiThread(() -> {
            floatingTimerView = new TextView(this);
            floatingTimerView.setTextColor(0xFFFFFFFF);
            floatingTimerView.setBackgroundColor(0xAA000000); 
            floatingTimerView.setPadding(30, 10, 30, 10);
            floatingTimerView.setTextSize(14);
            floatingTimerView.setGravity(Gravity.CENTER);
            floatingTimerView.setVisibility(View.GONE); 

            int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = 50; 

            windowManager.addView(floatingTimerView, params);
        });
    }

    private void updateFloatingTimerText(long millis) {
        if (floatingTimerView != null) {
            int hours = (int) (millis / 3600000);
            int minutes = (int) (millis % 3600000) / 60000;
            int seconds = (int) (millis % 60000) / 1000;
            
            String timeFormatted;
            if (hours > 0) {
                timeFormatted = String.format(Locale.getDefault(), "Time Left: %d:%02d:%02d", hours, minutes, seconds);
            } else {
                timeFormatted = String.format(Locale.getDefault(), "Time Left: %02d:%02d", minutes, seconds);
            }
            floatingTimerView.setText(timeFormatted);
        }
    }

    private void showSystemOverlay() {
        if (isOverlayShowing || isAdminBypassed) return;

        runOnUiThread(() -> {
            if (floatingTimerView != null) floatingTimerView.setVisibility(View.GONE); 

            overlayLayout = new RelativeLayout(this);
            overlayLayout.setBackgroundColor(0xFF000000);
            
            overlayLayout.setOnClickListener(v -> {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < 500) {
                    safetyClickCount++;
                    if (safetyClickCount >= 5) {
                        isAdminBypassed = true;
                        stopRentalTimer();
                        stopSessionLoop();
                        hideSystemOverlay();
                        if (floatingTimerView != null) floatingTimerView.setVisibility(View.GONE);
                        finish();
                    }
                } else {
                    safetyClickCount = 1;
                }
                lastClickTime = currentTime;
            });

            // WebView restricted to top portion to preserve clear space for panel
            portalWebView = new WebView(this);
            RelativeLayout.LayoutParams webParams = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            webParams.setMargins(0, 0, 0, 420); 
            portalWebView.setVisibility(View.VISIBLE); 
            portalWebView.loadUrl(PORTAL_URL);
            
            WebSettings webSettings = portalWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            
            portalWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    runOnUiThread(() -> { if (btnRetry != null) btnRetry.setVisibility(View.VISIBLE); });
                }
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (btnRetry != null) btnRetry.setVisibility(View.GONE);
                }
            });
            overlayLayout.addView(portalWebView, webParams);

            // Separate container ensures elements remain stacked non-overlapping
            LinearLayout bottomPanel = new LinearLayout(this);
            bottomPanel.setOrientation(LinearLayout.VERTICAL);
            bottomPanel.setGravity(Gravity.CENTER_HORIZONTAL);
            bottomPanel.setPadding(30, 20, 30, 20);
            bottomPanel.setBackgroundColor(0xEE111111); 

            RelativeLayout.LayoutParams panelParams = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            panelParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            
            txtStatus = new TextView(this);
            txtStatus.setText("PISO PHONE RENTAL");
            txtStatus.setTextColor(0xFFFFFFFF);
            txtStatus.setTextSize(18);
            txtStatus.setGravity(Gravity.CENTER);
            txtStatus.setPadding(0, 10, 0, 10);
            bottomPanel.addView(txtStatus);

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
            bottomPanel.addView(btnRetry);

            Button btnAdmin = new Button(this);
            btnAdmin.setText("ADMIN LOGIN");
            btnAdmin.setTextColor(0xFF888888);
            btnAdmin.setBackgroundColor(0x00000000); 
            btnAdmin.setOnClickListener(v -> onAdminClick());
            bottomPanel.addView(btnAdmin);

            overlayLayout.addView(bottomPanel, panelParams);

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
                WindowManager.LayoutParams.MATCH_PARENT, statusBarHeight, layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        statusParams.gravity = Gravity.TOP;
        try { windowManager.addView(statusBarShield, statusParams); } catch (Exception e) { }
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
                
                if (isTimerRunning && floatingTimerView != null) {
                    floatingTimerView.setVisibility(View.VISIBLE);
                }
            } catch (Exception e) { }
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void startNetworkTracking() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                super.onCapabilitiesChanged(network, capabilities);
                
                String currentSSID = "";
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                    if (wifiInfo != null) currentSSID = wifiInfo.getSSID();
                }

                if (currentSSID.equals(TARGET_SSID)) {
                    boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    boolean isCaptivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);

                    runOnUiThread(() -> {
                        if (hasInternet && !isAdminBypassed) {
                            startSessionLoop();
                            if (txtStatus != null) txtStatus.setText("PISO PHONE RENTAL");
                            if (portalWebView != null) portalWebView.setVisibility(View.VISIBLE);
                        } else if (isCaptivePortal && !isAdminBypassed) {
                            stopSessionLoop();
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
                    lastKnownRouterTimeMs = -1; 
                    stopRentalTimer();
                    stopSessionLoop();
                    resetToLockView();
                    showSystemOverlay();
                    forceAppToFront();
                });
            }
        };
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    }

    private void setupSessionLoop() {
        sessionLoopRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAdminBypassed) fetchRouterSessionAndApplyFormula();
                loopHandler.postDelayed(this, 10000); 
            }
        };
    }

    private void startSessionLoop() {
        loopHandler.removeCallbacks(sessionLoopRunnable);
        loopHandler.post(sessionLoopRunnable);
    }

    private void stopSessionLoop() {
        loopHandler.removeCallbacks(sessionLoopRunnable);
    }

    private void fetchRouterSessionAndApplyFormula() {
        new Thread(() -> {
            try {
                Document doc = Jsoup.connect(PORTAL_URL).timeout(5000).get();
                String pageText = doc.body().text().toLowerCase();
                
                long currentRouterMs = 0;
                
                int remainIdx = pageText.indexOf("remain");
                if (remainIdx == -1) remainIdx = pageText.indexOf("time left");
                if (remainIdx == -1) remainIdx = pageText.indexOf("status: connected"); 
                
                if (remainIdx != -1) {
                    String substring = pageText.substring(remainIdx);
                    Matcher m = Pattern.compile("(\\d+\\s*h\\s*:?\\s*\\d+\\s*m\\s*:?\\s*\\d+\\s*s|\\d+\\s*m\\s*:?\\s*\\d+\\s*s|\\d+:\\d+:\\d+|\\d+\\s*h\\s*\\d+\\s*m|\\d+\\s*m|\\d+\\s*s)").matcher(substring);
                    if (m.find()) {
                        currentRouterMs = parseTimeToMillis(m.group(1));
                    }
                }

                if (currentRouterMs > 0) {
                    if (lastKnownRouterTimeMs == -1) {
                        lastKnownRouterTimeMs = currentRouterMs;
                        long halvedMs = currentRouterMs / 2;
                        runOnUiThread(() -> startRentalSession(halvedMs));
                    } else {
                        if (currentRouterMs > lastKnownRouterTimeMs + 5000) {
                            long diffMs = currentRouterMs - lastKnownRouterTimeMs;
                            long reducedExtensionMs = diffMs / 2;
                            lastKnownRouterTimeMs = currentRouterMs; 
                            
                            runOnUiThread(() -> {
                                long newTime = isTimerRunning ? currentRentalTimeLeftMs + reducedExtensionMs : reducedExtensionMs;
                                startRentalSession(newTime);
                                Toast.makeText(MainActivity.this, "Coin Inserted! Time Extended.", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            lastKnownRouterTimeMs = currentRouterMs;
                        }
                    }
                }
            } catch (IOException e) {
                // Connection failures fall back gracefully
            }
        }).start();
    }

    private long parseTimeToMillis(String timeStr) {
        long totalMs = 0;
        try {
            timeStr = timeStr.toLowerCase().replaceAll("\\s+", ""); 
            
            if (timeStr.contains("h") || timeStr.contains("m") || timeStr.contains("s")) {
                timeStr = timeStr.replace(":", ""); 
                
                Matcher hMatcher = Pattern.compile("(\\d+)h").matcher(timeStr);
                if (hMatcher.find()) totalMs += Long.parseLong(hMatcher.group(1)) * 3600000L;
                
                Matcher mMatcher = Pattern.compile("(\\d+)m").matcher(timeStr);
                if (mMatcher.find()) totalMs += Long.parseLong(mMatcher.group(1)) * 60000L;
                
                Matcher sMatcher = Pattern.compile("(\\d+)s").matcher(timeStr);
                if (sMatcher.find()) totalMs += Long.parseLong(sMatcher.group(1)) * 1000L;
                
            } else if (timeStr.contains(":")) {
                String[] parts = timeStr.split(":");
                if (parts.length == 3) {
                    totalMs += Long.parseLong(parts[0]) * 3600000L;
                    totalMs += Long.parseLong(parts[1]) * 60000L;
                    totalMs += Long.parseLong(parts[2]) * 1000L;
                } else if (parts.length == 2) {
                    totalMs += Long.parseLong(parts[0]) * 60000L;
                    totalMs += Long.parseLong(parts[1]) * 1000L;
                }
            }
        } catch (Exception e) {
            return 0; 
        }
        return totalMs;
    }

    private void startRentalSession(long ms) {
        stopRentalTimer();
        isTimerRunning = true;
        currentRentalTimeLeftMs = ms;
        hideSystemOverlay();
        
        rentalTimer = new CountDownTimer(ms, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                currentRentalTimeLeftMs = millisUntilFinished;
                runOnUiThread(() -> updateFloatingTimerText(millisUntilFinished));
            }

            @Override
            public void onFinish() {
                isTimerRunning = false;
                lastKnownRouterTimeMs = -1; 
                
                forceMikrotikLogout();
                
                resetToLockView();
                showSystemOverlay();
                forceAppToFront();
                Toast.makeText(MainActivity.this, "App Time Expired! Insert coin for more.", Toast.LENGTH_LONG).show();
            }
        }.start();
    }

    private void stopRentalTimer() {
        if (rentalTimer != null) rentalTimer.cancel();
        isTimerRunning = false;
        if (floatingTimerView != null) floatingTimerView.setVisibility(View.GONE);
    }

    private void forceMikrotikLogout() {
        new Thread(() -> {
            try {
                Jsoup.connect(LOGOUT_URL).timeout(3000).get();
            } catch (Exception e) {
                // Silently drop trace if network drops first
            }
        }).start();
    }

    private void resetToLockView() {
        if (portalWebView != null) {
            portalWebView.setVisibility(View.VISIBLE);
            portalWebView.loadUrl(PORTAL_URL); 
        }
        if (txtStatus != null) txtStatus.setText("PISO PHONE RENTAL");
        if (btnRetry != null) btnRetry.setVisibility(View.GONE);
        if (floatingTimerView != null) floatingTimerView.setVisibility(View.GONE);
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
                        stopSessionLoop();
                        hideSystemOverlay();
                        if (floatingTimerView != null) floatingTimerView.setVisibility(View.GONE);
                        finish();
                    } else {
                        Toast.makeText(MainActivity.this, "Invalid Password", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("Close", null).create();

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
            setupFloatingTimer();
            showSystemOverlay();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRentalTimer();
        stopSessionLoop();
        hideSystemOverlay();
        
        if (floatingTimerView != null) {
            try {
                windowManager.removeView(floatingTimerView);
            } catch (Exception e) { }
        }
        
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
    }
}

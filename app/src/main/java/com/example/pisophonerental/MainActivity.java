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
    private WindowManager.LayoutParams overlayParams; // Persistent layout controllers
    private LinearLayout bottomPanel;
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
                initSystemOverlayWindow();
            }
        } else {
            setupFloatingTimer();
            initSystemOverlayWindow();
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

    // Creates the window hierarchy exactly once to preserve background execution stability
    private void initSystemOverlayWindow() {
        if (overlayLayout != null) return;

        runOnUiThread(() -> {
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
                        destroySystemOverlay();
                        finish();
                    }
                } else {
                    safetyClickCount = 1;
                }
                lastClickTime = currentTime;
            });

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

            bottomPanel = new LinearLayout(this);
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

            overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT
                );
            overlayParams.dimAmount = 1.0f; 
            overlayParams.gravity = Gravity.CENTER;

            windowManager.addView(overlayLayout, overlayParams);
            isOverlayShowing = true;

            blockNotificationBar(layoutType);
        });
    }

    private void showSystemOverlay() {
        if (overlayLayout == null || isAdminBypassed) return;
        runOnUiThread(() -> {
            if (floatingTimerView != null) floatingTimerView.setVisibility(View.GONE); 

            // Restore full-screen locking parameters
            overlayParams.width = WindowManager.LayoutParams.MATCH_PARENT;
            overlayParams.height = WindowManager.LayoutParams.MATCH_PARENT;
            overlayParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            overlayParams.dimAmount = 1.0f;
            
            if (bottomPanel != null) bottomPanel.setVisibility(View.VISIBLE);
            if (portalWebView != null) portalWebView.setVisibility(View.VISIBLE);
            
            windowManager.updateViewLayout(overlayLayout, overlayParams);
            isOverlayShowing = true;
            
            int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            blockNotificationBar(layoutType);
        });
    }

    private void hideSystemOverlay() {
        if (overlayLayout == null || !isOverlayShowing) return;
        runOnUiThread(() -> {
            try {
                // The Ghost Window Trick: Shrink to 1x1 pixel and strip click interceptor flags
                overlayParams.width = 1;
                overlayParams.height = 1;
                overlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                overlayParams.dimAmount = 0.0f;
                
                if (bottomPanel != null) bottomPanel.setVisibility(View.GONE);
                
                windowManager.updateViewLayout(overlayLayout, overlayParams);
                
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
                    runOnUiThread(() -> {
                        startSessionLoop(); // Keep the verification loop rolling continuously
                        if (hasInternet && !isAdminBypassed) {
                            if (txtStatus != null) txtStatus.setText("PISO PHONE RENTAL");
                        } else if (!isAdminBypassed && !isTimerRunning) {
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
                loopHandler.postDelayed(this, 3000); 
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
        if (portalWebView == null) return;
        
        runOnUiThread(() -> {
            portalWebView.evaluateJavascript(
                "(function() { return document.body.innerText; })();",
                html -> {
                    if (html == null || html.equals("null")) return;
                    
                    String pageText = html.toLowerCase().replace("\\n", " ").replace("\"", "");
                    if (!pageText.contains("connected")) return;

                    long currentRouterMs = parseTimeToMillisDirect(pageText);

                    if (currentRouterMs > 0) {
                        if (lastKnownRouterTimeMs == -1) {
                            lastKnownRouterTimeMs = currentRouterMs;
                            long halvedMs = currentRouterMs / 2;
                            startRentalSession(halvedMs); 
                        } else {
                            // Detects extensions of ANY scale/coin variance perfectly
                            if (currentRouterMs > lastKnownRouterTimeMs + 5000) { 
                                long diffMs = currentRouterMs - lastKnownRouterTimeMs;
                                long reducedExtensionMs = diffMs / 2;
                                lastKnownRouterTimeMs = currentRouterMs; 
                                
                                long newTime = isTimerRunning ? currentRentalTimeLeftMs + reducedExtensionMs : reducedExtensionMs;
                                startRentalSession(newTime);
                                Toast.makeText(MainActivity.this, "Coin Inserted! Time Extended.", Toast.LENGTH_SHORT).show();
                            } else {
                                lastKnownRouterTimeMs = currentRouterMs;
                            }
                        }
                    }
                }
            );
        });
    }

    private long parseTimeToMillisDirect(String text) {
        long totalMs = 0;
        try {
            Matcher m1 = Pattern.compile("(\\d+)\\s*h\\s*:\\s*(\\d+)\\s*m\\s*:\\s*(\\d+)\\s*s").matcher(text);
            if (m1.find()) {
                totalMs += Long.parseLong(m1.group(1)) * 3600000L;
                totalMs += Long.parseLong(m1.group(2)) * 60000L;
                totalMs += Long.parseLong(m1.group(3)) * 1000L;
                return totalMs;
            }
            
            Matcher m2 = Pattern.compile("(\\d+)\\s*m\\s*:\\s*(\\d+)\\s*s").matcher(text);
            if (m2.find()) {
                totalMs += Long.parseLong(m2.group(1)) * 60000L;
                totalMs += Long.parseLong(m2.group(2)) * 1000L;
                return totalMs;
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
        runOnUiThread(() -> {
            if (portalWebView != null) {
                portalWebView.loadUrl(LOGOUT_URL);
            }
        });
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

    private void destroySystemOverlay() {
        runOnUiThread(() -> {
            try {
                if (overlayLayout != null) {
                    windowManager.removeView(overlayLayout);
                    overlayLayout = null;
                }
                if (statusBarShield != null) {
                    windowManager.removeView(statusBarShield);
                    statusBarShield = null;
                }
                if (floatingTimerView != null) floatingTimerView.setVisibility(View.GONE);
            } catch (Exception e) { }
        });
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
                        destroySystemOverlay();
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
            initSystemOverlayWindow();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRentalTimer();
        stopSessionLoop();
        destroySystemOverlay();
        
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

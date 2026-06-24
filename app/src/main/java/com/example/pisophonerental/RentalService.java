package com.example.pisophonerental;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationCompat;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RentalService extends Service {

    private static final String TARGET_SSID = "\"PISOWIFI\"";
    private static final String ADMIN_PASSWORD = "admin_bypass_123";
    private static final String PORTAL_URL = "http://10.0.0.1";
    private static final String LOGOUT_URL = "http://10.0.0.1/logout";
    private static final String CHANNEL_ID = "RentalServiceChannel";

    private WindowManager windowManager;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private RelativeLayout overlayLayout;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout bottomPanel;
    private View statusBarShield;
    private TextView floatingTimerView;

    private WebView portalWebView;
    private TextView txtStatus;
    private Button btnRetry;

    // Strict Window State Tracking
    private boolean isOverlayAttached = false;
    private boolean isTimerAttached = false;
    private boolean isShieldAttached = false;

    private CountDownTimer rentalTimer;
    private boolean isTimerRunning = false;
    
    // Core Temporal Engine Trackers
    private long targetEndSystemTimeMs = 0; 
    private boolean isInLogoutCooldown = false;

    private Handler loopHandler = new Handler(Looper.getMainLooper());
    private Runnable sessionLoopRunnable;
    private long lastKnownRouterTimeMs = -1;

    private int safetyClickCount = 0;
    private long lastClickTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Piso Phone Rental Guard")
                .setContentText("Active background security lock enabled.")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        
        // Strict Foreground Typing for Android 14 (API 34)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }

        setupSessionLoop();
        setupFloatingTimer();
        initSystemOverlayWindow();
        startNetworkTracking();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_SERVICE".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY; 
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "Rental Guard Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    private void setupFloatingTimer() {
        if (floatingTimerView != null) return;
        loopHandler.post(() -> {
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
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = 50;

            try {
                if (!isTimerAttached) {
                    windowManager.addView(floatingTimerView, params);
                    isTimerAttached = true;
                }
            } catch (Exception e) { /* View lifecycle protection */ }
        });
    }

    private void updateFloatingTimerText(long millis) {
        if (floatingTimerView != null) {
            if (millis < 0) millis = 0;
            int hours = (int) (millis / 3600000);
            int minutes = (int) (millis % 3600000) / 60000;
            int seconds = (int) (millis % 60000) / 1000;
            String timeFormatted = hours > 0 ? 
                    String.format(Locale.getDefault(), "Time Left: %d:%02d:%02d", hours, minutes, seconds) :
                    String.format(Locale.getDefault(), "Time Left: %02d:%02d", minutes, seconds);
            floatingTimerView.setText(timeFormatted);
        }
    }

    private void initSystemOverlayWindow() {
        if (overlayLayout != null) return;
        loopHandler.post(() -> {
            overlayLayout = new RelativeLayout(this);
            overlayLayout.setBackgroundColor(0xFF000000);

            // Secret 5-tap bypass kill switch on the black background
            overlayLayout.setOnClickListener(v -> {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < 500) {
                    safetyClickCount++;
                    if (safetyClickCount >= 5) stopSelf();
                } else {
                    safetyClickCount = 1;
                }
                lastClickTime = currentTime;
            });

            portalWebView = new WebView(this);
            RelativeLayout.LayoutParams webParams = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            webParams.setMargins(0, 0, 0, 420); // Leave room for bottom panel
            portalWebView.setVisibility(View.VISIBLE);
            portalWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            
            WebSettings webSettings = portalWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE); // Prevents reading stale logout pages

            portalWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    loopHandler.post(() -> { if (btnRetry != null) btnRetry.setVisibility(View.VISIBLE); });
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
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT
            );
            overlayParams.dimAmount = 1.0f;
            overlayParams.gravity = Gravity.CENTER;

            try {
                if (!isOverlayAttached) {
                    windowManager.addView(overlayLayout, overlayParams);
                    isOverlayAttached = true;
                    blockNotificationBar(layoutType);
                    portalWebView.loadUrl(PORTAL_URL);
                }
            } catch (Exception e) { /* Intercept structural overlapping additions */ }
        });
    }

    private void showSystemOverlay() {
        if (overlayLayout == null) return;
        loopHandler.post(() -> {
            if (floatingTimerView != null) floatingTimerView.setVisibility(View.GONE);
            overlayParams.width = WindowManager.LayoutParams.MATCH_PARENT;
            overlayParams.height = WindowManager.LayoutParams.MATCH_PARENT;
            overlayParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            overlayParams.dimAmount = 1.0f;

            if (bottomPanel != null) bottomPanel.setVisibility(View.VISIBLE);
            if (portalWebView != null) portalWebView.setVisibility(View.VISIBLE);

            try {
                if (isOverlayAttached) {
                    windowManager.updateViewLayout(overlayLayout, overlayParams);
                } else {
                    windowManager.addView(overlayLayout, overlayParams);
                    isOverlayAttached = true;
                }
                int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                blockNotificationBar(layoutType);
            } catch (Exception e) { }
        });
    }

    private void hideSystemOverlay() {
        if (overlayLayout == null || !isOverlayAttached) return;
        loopHandler.post(() -> {
            try {
                overlayParams.width = 1;
                overlayParams.height = 1;
                overlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                overlayParams.dimAmount = 0.0f;

                if (bottomPanel != null) bottomPanel.setVisibility(View.GONE);
                windowManager.updateViewLayout(overlayLayout, overlayParams);

                if (statusBarShield != null && isShieldAttached) {
                    windowManager.removeView(statusBarShield);
                    isShieldAttached = false;
                }
                
                if (isTimerRunning && floatingTimerView != null) {
                    floatingTimerView.setVisibility(View.VISIBLE);
                }
            } catch (Exception e) { }
        });
    }

    private void blockNotificationBar(int layoutType) {
        if (isShieldAttached || statusBarShield != null) return;
        statusBarShield = new View(this);
        int statusBarHeight = (int) (25 * getResources().getDisplayMetrics().density);

        WindowManager.LayoutParams statusParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, statusBarHeight, layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        statusParams.gravity = Gravity.TOP;
        try { 
            windowManager.addView(statusBarShield, statusParams); 
            isShieldAttached = true;
        } catch (Exception e) { }
    }

    private void startNetworkTracking() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();

        if (networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception e) {}
        }

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                super.onCapabilitiesChanged(network, capabilities);
                String currentSSID = "";
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null && wifiManager.getConnectionInfo() != null) {
                    currentSSID = wifiManager.getConnectionInfo().getSSID();
                }

                if (currentSSID.equals(TARGET_SSID)) {
                    boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    loopHandler.post(() -> {
                        startSessionLoop();
                        if (hasInternet) {
                            if (txtStatus != null) txtStatus.setText("PISO PHONE RENTAL");
                        } else if (!isTimerRunning && !isInLogoutCooldown) { 
                            resetToLockView();
                            showSystemOverlay();
                        }
                    });
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                loopHandler.post(() -> {
                    lastKnownRouterTimeMs = -1;
                    stopRentalTimer();
                    stopSessionLoop();
                    resetToLockView();
                    showSystemOverlay();
                });
            }
        };
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    }

    private void setupSessionLoop() {
        sessionLoopRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isInLogoutCooldown) {
                    fetchRouterSessionAndApplyFormula();
                }
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
        loopHandler.post(() -> {
            portalWebView.evaluateJavascript(
                "(function() { return (document.body && document.body.innerText) ? document.body.innerText : ''; })();",
                html -> {
                    if (html == null || html.equals("null") || html.trim().isEmpty() || isInLogoutCooldown) return;
                    String pageText = html.toLowerCase().replace("\\n", " ").replace("\"", "");
                    if (!pageText.contains("connected")) return;

                    long currentRouterMs = parseTimeToMillisDirect(pageText);
                    if (currentRouterMs > 0) {
                        if (lastKnownRouterTimeMs == -1) {
                            lastKnownRouterTimeMs = currentRouterMs;
                            startRentalSession(currentRouterMs / 2);
                        } else {
                            if (currentRouterMs > lastKnownRouterTimeMs + 5000) {
                                long diffMs = currentRouterMs - lastKnownRouterTimeMs;
                                lastKnownRouterTimeMs = currentRouterMs;
                                
                                long currentLeft = isTimerRunning ? targetEndSystemTimeMs - System.currentTimeMillis() : 0;
                                if (currentLeft < 0) currentLeft = 0;
                                
                                long extendedTargetTime = currentLeft + (diffMs / 2);
                                startRentalSession(extendedTargetTime);
                                Toast.makeText(this, "Coin Inserted! Time Extended.", Toast.LENGTH_SHORT).show();
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
        } catch (Exception e) { }
        return totalMs;
    }

    private void startRentalSession(long ms) {
        stopRentalTimer();
        isTimerRunning = true;
        
        targetEndSystemTimeMs = System.currentTimeMillis() + ms;
        hideSystemOverlay();

        rentalTimer = new CountDownTimer(ms, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long trueRemainingMs = targetEndSystemTimeMs - System.currentTimeMillis();
                if (trueRemainingMs <= 0) {
                    onFinish();
                    cancel();
                } else {
                    updateFloatingTimerText(trueRemainingMs);
                }
            }

            @Override
            public void onFinish() {
                isTimerRunning = false;
                lastKnownRouterTimeMs = -1;
                isInLogoutCooldown = true; 
                
                forceMikrotikLogout();
                resetToLockView();
                showSystemOverlay();
                Toast.makeText(RentalService.this, "App Time Expired!", Toast.LENGTH_LONG).show();

                loopHandler.postDelayed(() -> isInLogoutCooldown = false, 8000);
            }
        }.start();
    }

    private void stopRentalTimer() {
        if (rentalTimer != null) rentalTimer.cancel();
        isTimerRunning = false;
        if (floatingTimerView != null) floatingTimerView.setVisibility(View.GONE);
    }

    private void forceMikrotikLogout() {
        loopHandler.post(() -> { 
            if (portalWebView != null) {
                // Clearing cache ensures a clean logout state next read
                portalWebView.clearCache(true);
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

    private void onAdminClick() {
        final EditText input = new EditText(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Admin Bypass")
                .setMessage("Input security pass:")
                .setView(input)
                .setPositiveButton("Bypass", (d, which) -> {
                    if (input.getText().toString().equals(ADMIN_PASSWORD)) {
                        stopSelf();
                    } else {
                        Toast.makeText(this, "Invalid Password", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("Close", null).create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setType((Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRentalTimer();
        stopSessionLoop();
        
        try {
            if (overlayLayout != null && isOverlayAttached) {
                windowManager.removeView(overlayLayout);
                isOverlayAttached = false;
            }
            if (statusBarShield != null && isShieldAttached) {
                windowManager.removeView(statusBarShield);
                isShieldAttached = false;
            }
            if (floatingTimerView != null && isTimerAttached) {
                windowManager.removeView(floatingTimerView);
                isTimerAttached = false;
            }
        } catch (Exception e) { /* Suppress runtime removal errors */ }
        
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception e) {}
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}

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
    private TextView floatingTimerView;
    
    private WebView portalWebView;
    private TextView txtStatus;
    private Button btnRetry; 
    
    private boolean isOverlayShowing = false;
    private boolean isAdminBypassed = false;
    
    private CountDownTimer rentalTimer;
    private boolean isTimerRunning = false;
    
    private Handler loopHandler = new Handler(Looper.getMainLooper());
    private Runnable sessionLoopRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        View emptyView = new View(this);
        setContentView(emptyView);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        setupSessionLoop();
        checkOverlayPermission();
        checkPermissions(); // Method restored!
        startNetworkTracking();
    }

    private void checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
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
            floatingTimerView.setVisibility(View.GONE);

            int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = 50;
            windowManager.addView(floatingTimerView, params);
        });
    }

    private void updateFloatingTimerText(long millis) {
        if (floatingTimerView != null) {
            int minutes = (int) (millis % 3600000) / 60000;
            int seconds = (int) (millis % 60000) / 1000;
            floatingTimerView.setText(String.format(Locale.getDefault(), "Time Left: %02d:%02d", minutes, seconds));
        }
    }

    private void showSystemOverlay() {
        if (isOverlayShowing || isAdminBypassed) return;
        runOnUiThread(() -> {
            overlayLayout = new RelativeLayout(this);
            overlayLayout.setBackgroundColor(0xFF000000);
            
            portalWebView = new WebView(this);
            RelativeLayout.LayoutParams webParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            webParams.setMargins(0, 0, 0, 320); 
            portalWebView.loadUrl(PORTAL_URL);
            portalWebView.getSettings().setJavaScriptEnabled(true);
            portalWebView.getSettings().setDomStorageEnabled(true);
            
            portalWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    runOnUiThread(() -> { if (btnRetry != null) btnRetry.setVisibility(View.VISIBLE); });
                }
            });
            overlayLayout.addView(portalWebView, webParams);

            txtStatus = new TextView(this);
            txtStatus.setId(View.generateViewId());
            txtStatus.setText("PISO PHONE RENTAL\n\nPlease Connect to Wi-Fi");
            txtStatus.setTextColor(0xFFFFFFFF);
            txtStatus.setTextSize(24);
            txtStatus.setGravity(Gravity.CENTER);
            RelativeLayout.LayoutParams textParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.addRule(RelativeLayout.CENTER_IN_PARENT);
            overlayLayout.addView(txtStatus, textParams);

            btnRetry = new Button(this);
            btnRetry.setText("RETRY CONNECTION");
            btnRetry.setVisibility(View.GONE);
            btnRetry.setOnClickListener(v -> {
                btnRetry.setVisibility(View.GONE);
                portalWebView.loadUrl(PORTAL_URL);
            });
            RelativeLayout.LayoutParams retryParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            retryParams.addRule(RelativeLayout.BELOW, txtStatus.getId());
            retryParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
            overlayLayout.addView(btnRetry, retryParams);

            int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT
            );
            params.dimAmount = 1.0f;
            windowManager.addView(overlayLayout, params);
            isOverlayShowing = true;
        });
    }

    private void hideSystemOverlay() {
        if (!isOverlayShowing || overlayLayout == null) return;
        runOnUiThread(() -> {
            try { windowManager.removeView(overlayLayout); } catch (Exception e) {}
            isOverlayShowing = false;
            if (isTimerRunning && floatingTimerView != null) floatingTimerView.setVisibility(View.VISIBLE);
        });
    }

    private void startNetworkTracking() {
        NetworkRequest networkRequest = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    runOnUiThread(() -> { if (!isAdminBypassed) startSessionLoop(); });
                }
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

    private void fetchRouterSessionAndApplyFormula() {
        new Thread(() -> {
            try {
                Document doc = Jsoup.connect(PORTAL_URL).timeout(5000).get();
                String pageText = doc.body().text().toLowerCase();
                int remainIdx = pageText.indexOf("remain");
                if (remainIdx != -1) {
                    Matcher m = Pattern.compile("(\\d+):(\\d+):(\\d+)").matcher(pageText.substring(remainIdx));
                    if (m.find()) {
                        long ms = (Long.parseLong(m.group(1)) * 3600000L) + (Long.parseLong(m.group(2)) * 60000L) + (Long.parseLong(m.group(3)) * 1000L);
                        if (ms > 0) runOnUiThread(() -> startRentalSession(ms / 2));
                    }
                }
            } catch (IOException e) {}
        }).start();
    }

    private void startRentalSession(long ms) {
        if (isTimerRunning) return;
        isTimerRunning = true;
        hideSystemOverlay();
        rentalTimer = new CountDownTimer(ms, 1000) {
            @Override
            public void onTick(long millisUntilFinished) { updateFloatingTimerText(millisUntilFinished); }
            @Override
            public void onFinish() {
                isTimerRunning = false;
                new Thread(() -> { try { Jsoup.connect(LOGOUT_URL).get(); } catch (Exception e) {} }).start();
                showSystemOverlay();
            }
        }.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rentalTimer != null) rentalTimer.cancel();
        loopHandler.removeCallbacks(sessionLoopRunnable);
        hideSystemOverlay();
    }
}

package com.example.pisophonerental;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int OVERLAY_PERMISSION_REQ_CODE = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        View emptyView = new View(this);
        emptyView.setBackgroundColor(0xFF000000); // Black background while loading
        setContentView(emptyView);

        checkStandardPermissions();
    }

    private void checkStandardPermissions() {
        List<String> requiredPermissions = new ArrayList<>();
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> missingPermissions = new ArrayList<>();
        for (String permission : requiredPermissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            checkOverlayPermission();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                checkOverlayPermission();
            } else {
                Toast.makeText(this, "Crucial permissions missing. App cannot function.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE);
            } else {
                startRentalGuardService();
            }
        } else {
            startRentalGuardService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startRentalGuardService();
                } else {
                    Toast.makeText(this, "Overlay permission is strictly required.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void startRentalGuardService() {
        Intent serviceIntent = new Intent(this, RentalService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        // Closes the blank setup activity so the user just sees the overlay/timer
        finish(); 
    }
}

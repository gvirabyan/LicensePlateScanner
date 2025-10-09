package com.example.licenseplatescanner;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity implements ServiceCallback {

    private static final int REQUEST_PERMISSIONS = 1;

    // UI Components
    private androidx.camera.view.PreviewView previewView;
    private ToggleButton flashlightButton;
    private ToggleButton autoScanSwitch;
    private TextView plateLogView;
    private TextView intervalTextView;
    private ImageButton intervalButton;
    private Button resetFileButton;
    private TextView remainingScansView;

    // Service
    private BackgroundScanService scanService;
    private boolean isBound = false;
    private final int[] intervals = {2, 3, 5, 10, 15, 30, 60};
    private int currentInterval = 3000;

    private LogManager logManager;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();
        logManager = new LogManager(this);
        setupListeners();
    }

    private void initViews() {
        previewView = findViewById(R.id.preview_view);
        flashlightButton = findViewById(R.id.flashlight_button);
        autoScanSwitch = findViewById(R.id.auto_scan_switch);
        plateLogView = findViewById(R.id.plate_log_view);
        intervalTextView = findViewById(R.id.interval_text);
        intervalButton = findViewById(R.id.interval_button);
        resetFileButton = findViewById(R.id.reset_file_button);
        remainingScansView = findViewById(R.id.remaining_scans_view);
    }

    private void setupListeners() {
        resetFileButton.setOnClickListener(v -> showResetConfirmationDialog());

        flashlightButton.setOnClickListener(v -> {
            if (isBound) {
                scanService.toggleFlashlight(flashlightButton.isChecked());
            }
        });

        autoScanSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isBound) return;
            if (isChecked) {
                Intent serviceIntent = new Intent(this, BackgroundScanService.class);
                ContextCompat.startForegroundService(this, serviceIntent);
                scanService.startAutoScan();
            } else {
                scanService.stopAutoScan();
            }
        });

        updateIntervalLabel();
        intervalButton.setOnClickListener(this::showIntervalMenu);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BackgroundScanService.LocalBinder binder = (BackgroundScanService.LocalBinder) service;
            scanService = binder.getService();
            isBound = true;
            scanService.setCallback(MainActivity.this);
            scanService.attachPreview(previewView);

            // Update UI with current service state
            autoScanSwitch.setChecked(scanService.isAutoScanning());
            onRemainingScansUpdated(scanService.getRemainingScans());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            scanService = null;
        }
    };

    // --- ServiceCallback Implementation ---
    @Override
    public void onPlateRecognized(String plate) {
        runOnUiThread(() -> {
            plateLogView.setText("Found: " + plate);
            VibratorHelper.vibrate(MainActivity.this, 20);
        });
    }

    @Override
    public void onScanStarted() {
        runOnUiThread(() -> {
            plateLogView.setText("Auto Scan ON");
            autoScanSwitch.setChecked(true);
        });
    }

    @Override
    public void onScanStopped() {
        runOnUiThread(() -> {
            plateLogView.setText("Auto Scan OFF");
            autoScanSwitch.setChecked(false);
        });
    }

    @Override
    public void onScanError(String message) {
        runOnUiThread(() -> plateLogView.setText(message));
    }

    @Override
    public void onRemainingScansUpdated(int remainingScans) {
        runOnUiThread(() -> {
            remainingScansView.setText("Remaining: " + remainingScans);
            if (remainingScans <= 50) {
                remainingScansView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            } else {
                remainingScansView.setTextColor(ContextCompat.getColor(this, android.R.color.white));
            }
        });
    }

    // --- Permissions ---
    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissions granted, we can now start the service connection in onStart
            } else {
                Toast.makeText(this, "Camera and storage permissions are required.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // --- UI Management ---
    private void showResetConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Clear Log?")
                .setMessage("Are you sure you want to delete all saved license plates?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    logManager.resetLogFile();
                    plateLogView.setText("Log cleared.");
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void showIntervalMenu(View view) {
        PopupMenu popup = new PopupMenu(this, view);
        for (int i = 0; i < intervals.length; i++) {
            popup.getMenu().add(0, i, i, intervals[i] + " sec");
        }

        popup.setOnMenuItemClickListener(item -> {
            int selected = intervals[item.getItemId()];
            currentInterval = selected * 1000;
            updateIntervalLabel();
            Toast.makeText(this, "Interval set to " + selected + " sec", Toast.LENGTH_SHORT).show();

            if (isBound) {
                scanService.setScanInterval(currentInterval);
            }
            return true;
        });
        popup.show();
    }

    private void updateIntervalLabel() {
        int sec = currentInterval / 1000;
        intervalTextView.setText(sec + "s");
    }

    // --- Lifecycle ---
    @Override
    protected void onStart() {
        super.onStart();
        if (checkPermissions()) {
            Intent intent = new Intent(this, BackgroundScanService.class);
            startService(intent); // Start the service to keep it alive for the preview
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } else {
            requestPermissions();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            scanService.setCallback(null);
            scanService.detachPreview();
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanService != null && !scanService.isAutoScanning()) {
            stopService(new Intent(this, BackgroundScanService.class));
        }
    }
}
package com.example.licenseplatescanner;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity implements PlateImageAnalyzer.ScanListener {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String TAG = "LPR_SCANNER";

    // UI Components
    private androidx.camera.view.PreviewView previewView;
    private ToggleButton flashlightButton;
    private ToggleButton autoScanSwitch;
    private TextView plateLogView;
    private TextView intervalTextView;
    private ImageButton intervalButton;
    private Button resetFileButton;
    private TextView remainingScansView; // Добавлено

    // Managers and Clients
    private CameraManager cameraManager;
    private ScanLimitManager scanLimitManager; // Добавлено
    private PlateRecognizerClient recognizerClient;
    private PlateImageAnalyzer plateAnalyzer;
    private LogManager logManager;
    private ExecutorService cameraExecutor;

    // Auto-Scan Logic
    private final Handler autoScanHandler = new Handler();
    private int AUTO_SCAN_INTERVAL = 3000; // Default: 3 seconds
    private boolean isAutoScanEnabled = false;
    private final int[] intervals = {2, 3, 5, 10, 15, 30, 60};
    private Runnable autoScanTask;
    //private final Handler autoScanHandler = new Handler();
   /* private final Runnable autoScanTask = new Runnable() {
        @Override
        public void run() {
            if (isAutoScanEnabled) {
                // ВАЖНО: Никакой блокировки здесь нет, сканирование запускается всегда
                runOnUiThread(() -> plateLogView.setText("Auto Scanning..."));
                plateAnalyzer.requestScan(); // Trigger frame capture
            }
            if (isAutoScanEnabled) {
                autoScanHandler.postDelayed(this, AUTO_SCAN_INTERVAL);
            }
        }
    };
*/
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();

        // Initialize components
        cameraExecutor = Executors.newSingleThreadExecutor();
        recognizerClient = new PlateRecognizerClient();
        scanLimitManager = new ScanLimitManager(this); // Инициализация
        logManager = new LogManager(this);
        cameraManager = new CameraManager(this, cameraExecutor);
        plateAnalyzer = new PlateImageAnalyzer(this);

        if (checkPermissions()) {
            startCameraSetup();
        } else {
            requestPermissions();
        }

        setupListeners();
        updateRemainingScansUI();

        autoScanTask = new Runnable() {
            @Override
            public void run() {
                if (isAutoScanEnabled) {
                    runOnUiThread(() -> {
                        plateLogView.setText("Auto Scanning...");
                    });

                    plateAnalyzer.requestScan();
                    autoScanHandler.postDelayed(this, AUTO_SCAN_INTERVAL);
                }
            }
        };

    }

    private void initViews() {
        previewView = findViewById(R.id.preview_view);
        flashlightButton = findViewById(R.id.flashlight_button);
        autoScanSwitch = findViewById(R.id.auto_scan_switch);
        plateLogView = findViewById(R.id.plate_log_view);
        intervalTextView = findViewById(R.id.interval_text);
        intervalButton = findViewById(R.id.interval_button);
        resetFileButton = findViewById(R.id.reset_file_button);
        remainingScansView = findViewById(R.id.remaining_scans_view); // Инициализация
    }

    // --- Метод для обновления UI счетчика ---
    private void updateRemainingScansUI() {
        int remaining = scanLimitManager.getRemainingScans();
        remainingScansView.setText("Remaining: " + remaining);

        // Цветовое выделение, если сканирований мало
        if (remaining <= 50) {
            remainingScansView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
        } else {
            remainingScansView.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        }

        // ВАЖНО: Никакого отключения элементов UI здесь нет
    }

    private void setupListeners() {
        resetFileButton.setOnClickListener(v -> showResetConfirmationDialog());
        flashlightButton.setOnClickListener(v -> cameraManager.toggleFlashlight(flashlightButton.isChecked()));

        autoScanSwitch.setChecked(false);
        autoScanSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                isAutoScanEnabled = true;
                startAutoScan();
                plateLogView.setText("Auto Scan ON");

//                Intent serviceIntent = new Intent(this, BackgroundScanService.class);
//                ContextCompat.startForegroundService(this, serviceIntent);
            } else {
                isAutoScanEnabled = false;
                stopAutoScan();
                plateLogView.setText("Auto Scan OFF");

//                stopService(new Intent(this, BackgroundScanService.class));

            }
        });

        updateIntervalLabel();
        intervalButton.setOnClickListener(this::showIntervalMenu);
    }

    // --- Camera & Permissions (Без изменений) ---

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraSetup();
            } else {
                Toast.makeText(this, "Camera and storage permissions were not granted.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startCameraSetup() {
        cameraManager.startCamera(previewView, plateAnalyzer);
    }

    // --- ScanListener (Callback from PlateImageAnalyzer) ---

    @Override
    public void onImageReady(byte[] jpegBytes) {

        // УМЕНЬШЕНИЕ СЧЕТЧИКА: уменьшаем счетчик и обновляем UI сразу после захвата кадра
        scanLimitManager.decrementScanCount();
        runOnUiThread(this::updateRemainingScansUI);

        recognizerClient.recognizePlate(jpegBytes, new Callback<PlateRecognitionResponse>() {
            @Override
            public void onResponse(@NonNull Call<PlateRecognitionResponse> call,
                                   @NonNull Response<PlateRecognitionResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().results != null && response.body().results.length > 0) {
                    String plate = response.body().results[0].plate;
                    runOnUiThread(() -> {
                        plateLogView.setText("Found: " + plate);
                        logManager.logLicensePlate(plate);
                        VibratorHelper.vibrate(MainActivity.this, 100); // вибрация 150мс

                    });
                } else {
                    runOnUiThread(() -> plateLogView.setText("No plate found."));
                }
            }

            @Override
            public void onFailure(@NonNull Call<PlateRecognitionResponse> call, @NonNull Throwable t) {
                runOnUiThread(() -> {
                    plateLogView.setText("Network Error.");
                    Log.e(TAG, "Network request failed: " + t.getMessage());
                });
            }
        });
    }

    // --- UI/Log Management (Без изменений) ---

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
            AUTO_SCAN_INTERVAL = selected * 1000;
            updateIntervalLabel();
            Toast.makeText(this, "Interval set to " + selected + " sec", Toast.LENGTH_SHORT).show();

            if (isAutoScanEnabled) {
                startAutoScan();
            }

            return true;
        });

        popup.show();
    }

    private void updateIntervalLabel() {
        int sec = AUTO_SCAN_INTERVAL / 1000;
        intervalTextView.setText(sec + "s");
    }

    // --- Auto Scan Control (Без изменений) ---

    private void startAutoScan() {
        stopAutoScan();
        autoScanHandler.post(autoScanTask);
        Log.i(TAG, "Auto scan started.");
    }

    private void stopAutoScan() {
        autoScanHandler.removeCallbacks(autoScanTask);
        Log.i(TAG, "Auto scan stopped.");
    }

    // --- Lifecycle (Без изменений) ---

    @Override
    protected void onResume() {
        super.onResume();
        if (autoScanSwitch.isChecked()) {
            isAutoScanEnabled = true;
            startAutoScan();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
       // stopService(new Intent(this, BackgroundScanService.class));

    }
}
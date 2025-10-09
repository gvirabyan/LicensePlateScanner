package com.example.licenseplatescanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class BackgroundScanService extends Service implements PlateImageAnalyzer.ScanListener, LifecycleOwner {

    private static final String CHANNEL_ID = "scan_channel";
    private static final String TAG = "LPR_SERVICE";

    // Service components
    private final IBinder binder = new LocalBinder();
    private ServiceCallback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LifecycleRegistry lifecycleRegistry;

    // Core components
    private CameraManager cameraManager;
    private PlateRecognizerClient recognizerClient;
    private PlateImageAnalyzer plateAnalyzer;
    private LogManager logManager;
    private ScanLimitManager scanLimitManager;
    private ExecutorService cameraExecutor;

    // State
    private boolean isAutoScanEnabled = false;
    private boolean isCameraActive = false;
    private int autoScanInterval = 3000; // Default interval
    private androidx.camera.view.PreviewView servicePreviewView;


    public class LocalBinder extends Binder {
        BackgroundScanService getService() {
            return BackgroundScanService.this;
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        // Initialize all the necessary components
        cameraExecutor = Executors.newSingleThreadExecutor();
        recognizerClient = new PlateRecognizerClient();
        logManager = new LogManager(this);
        scanLimitManager = new ScanLimitManager(this);
        cameraManager = new CameraManager(this, cameraExecutor);
        plateAnalyzer = new PlateImageAnalyzer(this);

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, createNotification());
        return START_STICKY;
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("License Plate Scanner")
                .setContentText("Auto-scan is active in the background.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    // --- Public methods for MainActivity to call ---
    public void setCallback(ServiceCallback callback) {
        this.callback = callback;
    }

    public void attachPreview(androidx.camera.view.PreviewView previewView) {
        this.servicePreviewView = previewView;
        updateCameraState();
    }

    public void detachPreview() {
        this.servicePreviewView = null;
        updateCameraState();
    }

    private void updateCameraState() {
        boolean shouldBeActive = servicePreviewView != null || isAutoScanEnabled;
        if (shouldBeActive && !isCameraActive) {
            // Start camera
            cameraManager.startCamera(servicePreviewView, plateAnalyzer);
            isCameraActive = true;
            Log.i(TAG, "Camera started.");
        } else if (!shouldBeActive && isCameraActive) {
            // Stop camera
            cameraManager.stopCamera();
            isCameraActive = false;
            Log.i(TAG, "Camera stopped.");
        } else if (shouldBeActive && isCameraActive) {
            // Camera is already active, just re-bind use cases (e.g., to add/remove preview)
            cameraManager.startCamera(servicePreviewView, plateAnalyzer);
            Log.i(TAG, "Camera re-bound with new preview state.");
        }
    }

    public void startAutoScan() {
        if (isAutoScanEnabled) return;
        isAutoScanEnabled = true;
        updateCameraState(); // Ensures camera is running
        handler.post(autoScanTask);
        if (callback != null) {
            callback.onScanStarted();
        }
        Log.i(TAG, "Auto scan started.");
    }

    public void stopAutoScan() {
        if (!isAutoScanEnabled) return;
        isAutoScanEnabled = false;
        handler.removeCallbacks(autoScanTask);
        updateCameraState(); // May stop camera if preview is also detached
        if (callback != null) {
            callback.onScanStopped();
        }
        Log.i(TAG, "Auto scan stopped.");
    }

    public boolean isAutoScanning() {
        return isAutoScanEnabled;
    }

    public void setScanInterval(int intervalMillis) {
        this.autoScanInterval = intervalMillis;
        if (isAutoScanEnabled) {
            handler.removeCallbacks(autoScanTask);
            handler.post(autoScanTask);
        }
    }

    public void toggleFlashlight(boolean enable) {
        cameraManager.toggleFlashlight(enable);
    }

    public int getRemainingScans() {
        return scanLimitManager.getRemainingScans();
    }

    // --- Scanning Logic ---
    private final Runnable autoScanTask = new Runnable() {
        @Override
        public void run() {
            if (isAutoScanEnabled) {
                plateAnalyzer.requestScan();
                handler.postDelayed(this, autoScanInterval);
            }
        }
    };

    @Override
    public void onImageReady(byte[] jpegBytes) {
        if (scanLimitManager.getRemainingScans() <= 0) {
            if (callback != null) {
                callback.onScanError("Scan limit reached.");
            }
            stopAutoScan();
            return;
        }

        scanLimitManager.decrementScanCount();
        if (callback != null) {
            callback.onRemainingScansUpdated(scanLimitManager.getRemainingScans());
        }

        recognizerClient.recognizePlate(jpegBytes, new Callback<PlateRecognitionResponse>() {
            @Override
            public void onResponse(@NonNull Call<PlateRecognitionResponse> call, @NonNull Response<PlateRecognitionResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().results != null && response.body().results.length > 0) {
                    String plate = response.body().results[0].plate;
                    logManager.logLicensePlate(plate);
                    VibratorHelper.vibrate(BackgroundScanService.this, 100);
                    if (callback != null) {
                        callback.onPlateRecognized(plate);
                    }
                } else {
                    if (callback != null) {
                        callback.onScanError("No plate found.");
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<PlateRecognitionResponse> call, @NonNull Throwable t) {
                Log.e(TAG, "Network request failed: " + t.getMessage());
                if (callback != null) {
                    callback.onScanError("Network Error.");
                }
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Background Scan Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        stopAutoScan();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        Log.i(TAG, "Service destroyed.");
    }
}
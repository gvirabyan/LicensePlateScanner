package com.example.licenseplatescanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class BackgroundScanService extends Service implements PlateImageAnalyzer.ScanListener {

    private static final String CHANNEL_ID = "scan_channel";
    private static final String TAG = "LPR_SERVICE";

    private PlateImageAnalyzer plateAnalyzer;
    private PlateRecognizerClient recognizerClient;
    private CameraManager cameraManager;
    private Handler handler = new Handler();
    private boolean isRunning = false;
    private int AUTO_SCAN_INTERVAL = 3000;

    @Override
    public void onCreate() {
        super.onCreate();
        recognizerClient = new PlateRecognizerClient();
        plateAnalyzer = new PlateImageAnalyzer(this);
        cameraManager = new CameraManager(this, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundService();
        startCameraAndAutoScan();
        return START_STICKY;
    }

    private void startForegroundService() {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("License Plate Scanner")
                .setContentText("Работает в фоновом режиме")
                .build();
        startForeground(1, notification);
    }

    private void startCameraAndAutoScan() {
        if (!isRunning) {
            isRunning = true;
            cameraManager.startCamera(null, plateAnalyzer); // Можно без preview
            handler.post(scanTask);
        }
    }

    private final Runnable scanTask = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                plateAnalyzer.requestScan();
                handler.postDelayed(this, AUTO_SCAN_INTERVAL);
            }
        }
    };

    @Override
    public void onImageReady(byte[] jpegBytes) {
        recognizerClient.recognizePlate(jpegBytes, new retrofit2.Callback<PlateRecognitionResponse>() {
            @Override
            public void onResponse(retrofit2.Call<PlateRecognitionResponse> call,
                                   retrofit2.Response<PlateRecognitionResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().results.length > 0) {
                    String plate = response.body().results[0].plate;
                    Log.i(TAG, "Found plate: " + plate);
                } else {
                    Log.i(TAG, "No plate found.");
                }
            }

            @Override
            public void onFailure(retrofit2.Call<PlateRecognitionResponse> call, Throwable t) {
                Log.e(TAG, "Network error: " + t.getMessage());
            }
        });
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        handler.removeCallbacks(scanTask);
    }
}

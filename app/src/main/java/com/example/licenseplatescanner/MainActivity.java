package com.example.licenseplatescanner;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import android.os.VibrationEffect;
import android.os.Vibrator;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String TAG = "LPR_SCANNER";

    private PreviewView previewView;
    private ToggleButton flashlightButton;
    private ToggleButton autoScanSwitch;
    private TextView plateLogView;
    private TextView intervalTextView;
    private ImageButton intervalButton;
    private Button resetFileButton;

    private String lastPlate = "";
    private Camera camera;
    private ExecutorService cameraExecutor;
    private PlateRecognizerService apiService;
    private volatile boolean scanRequested = false;

    // --- Авто-сканирование ---
    private final Handler autoScanHandler = new Handler();
    private int AUTO_SCAN_INTERVAL = 3000; // по умолчанию 3 сек
    private boolean isAutoScanEnabled = false;

    private TextView remainingScansView;
    private int remainingScans = -1;

    private final int[] intervals = {2, 3, 5, 10, 15, 30, 60};

    private final Runnable autoScanTask = new Runnable() {
        @Override
        public void run() {
            if (isAutoScanEnabled && !scanRequested) {
                runOnUiThread(() -> plateLogView.setText("Auto Scanning..."));
                scanRequested = true;
            }
            if (isAutoScanEnabled) {
                autoScanHandler.postDelayed(this, AUTO_SCAN_INTERVAL);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        previewView = findViewById(R.id.preview_view);
        flashlightButton = findViewById(R.id.flashlight_button);
        autoScanSwitch = findViewById(R.id.auto_scan_switch);
        plateLogView = findViewById(R.id.plate_log_view);
        intervalTextView = findViewById(R.id.interval_text);
        intervalButton = findViewById(R.id.interval_button);
        remainingScansView = findViewById(R.id.remaining_scans_view);


        cameraExecutor = Executors.newSingleThreadExecutor();
        setupRetrofit();

        if (checkPermissions()) {
            startCamera();
        } else {
            requestPermissions();
        }

        resetFileButton = findViewById(R.id.reset_file_button);
        resetFileButton.setOnClickListener(v -> showResetConfirmationDialog());

        flashlightButton.setOnClickListener(v -> toggleFlashlight());

        autoScanSwitch.setChecked(false);
        autoScanSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                isAutoScanEnabled = true;
                startAutoScan();
                plateLogView.setText("Auto Scan ON");
            } else {
                isAutoScanEnabled = false;
                stopAutoScan();
                plateLogView.setText("Auto Scan OFF");
            }
        });

        // Установка текста текущего интервала
        updateIntervalLabel();

        // Обработка клика на иконку часов
        intervalButton.setOnClickListener(this::showIntervalMenu);
    }

    private void vibrateShort() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(150);
            }
        }
    }

    private void showResetConfirmationDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear Log?")
                .setMessage("Are you sure you want to delete all saved license plates?")
                .setPositiveButton("Yes", (dialog, which) -> resetLogFile())
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .show();
    }


    private void resetLogFile() {
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File logFile = new File(documentsDir, "license_plates.txt");

        if (logFile.exists()) {
            try (FileWriter writer = new FileWriter(logFile, false)) {
                writer.write(""); // очистка содержимого
                Toast.makeText(this, "Файл очищен успешно", Toast.LENGTH_SHORT).show();
                plateLogView.setText("Log cleared.");
                lastPlate = "";
            } catch (IOException e) {
                Log.e(TAG, "Ошибка при очистке файла", e);
                Toast.makeText(this, "Ошибка при очистке файла", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show();
        }
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
            return true;
        });

        popup.show();
    }

    private void updateIntervalLabel() {
        int sec = AUTO_SCAN_INTERVAL / 1000;
        intervalTextView.setText(sec + "s");
    }

    private void setupRetrofit() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://api.platerecognizer.com/v1/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        apiService = retrofit.create(PlateRecognizerService.class);
    }

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
                startCamera();
            } else {
                Toast.makeText(this, "Разрешение на камеру не предоставлено.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Ошибка запуска камеры", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setTargetRotation(previewView.getDisplay().getRotation());
        imageAnalysis.setAnalyzer(cameraExecutor, new PlateAnalyzer());

        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
    }

    private class PlateAnalyzer implements ImageAnalysis.Analyzer {
        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            if (scanRequested) {
                scanRequested = false;
                byte[] imageBytes = imageProxyToJpegByteArray(imageProxy);
                if (imageBytes != null) {
                    uploadImageForRecognition(imageBytes);
                } else {
                    runOnUiThread(() -> plateLogView.setText("Failed to capture image."));
                }
            }
            imageProxy.close();
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private byte[] imageProxyToJpegByteArray(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) return null;

        if (imageProxy.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Неподдерживаемый формат изображения: " + imageProxy.getFormat());
            return null;
        }

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 90, out);

        byte[] jpegBytes = out.toByteArray();

        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        if (rotationDegrees != 0) {
            jpegBytes = rotateJpeg(jpegBytes, rotationDegrees);
        }

        return jpegBytes;
    }

    private byte[] rotateJpeg(byte[] jpeg, int degrees) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rotated.compress(Bitmap.CompressFormat.JPEG, 90, out);
        return out.toByteArray();
    }

    private void uploadImageForRecognition(byte[] imageBytes) {
        RequestBody imageBody = RequestBody.create(MediaType.parse("image/jpeg"), imageBytes);
        MultipartBody.Part imagePart = MultipartBody.Part.createFormData("upload", "frame.jpg", imageBody);
        RequestBody regions = RequestBody.create(MediaType.parse("text/plain"), "us");

        String authToken = "Token " + PlateRecognizerService.API_KEY;
        apiService.uploadImage(authToken, imagePart, regions, "true")
                .enqueue(new Callback<PlateRecognitionResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<PlateRecognitionResponse> call,
                                           @NonNull Response<PlateRecognitionResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().results.length > 0) {
                            String plate = response.body().results[0].plate;
                            runOnUiThread(() -> {
                                plateLogView.setText("Found: " + plate);
                                logLicensePlate(plate);
                            });
                        } else {
                            runOnUiThread(() -> plateLogView.setText("No plate found."));
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<PlateRecognitionResponse> call, @NonNull Throwable t) {
                        runOnUiThread(() -> {
                            plateLogView.setText("Network Error.");
                            Toast.makeText(MainActivity.this, "Network request failed.", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }



    private void logLicensePlate(String licensePlate) {
        if (!Objects.equals(lastPlate, licensePlate)) {
            String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String logEntry = timeStamp + ", " + licensePlate + "\n";

            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (documentsDir != null && !documentsDir.exists()) {
                documentsDir.mkdirs();
            }

            File logFile = new File(documentsDir, "license_plates.txt");
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.append(logEntry);
                vibrateShort();
            } catch (IOException e) {
                Log.e(TAG, "Error saving license plate to file.", e);
            }
            lastPlate = licensePlate;
        }
    }

    private void toggleFlashlight() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(flashlightButton.isChecked());
        }
    }

    private void startAutoScan() {
        stopAutoScan();
        autoScanHandler.post(autoScanTask);
        Log.i(TAG, "Auto scan started.");
    }

    private void stopAutoScan() {
        autoScanHandler.removeCallbacks(autoScanTask);
        scanRequested = false;
        Log.i(TAG, "Auto scan stopped.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        isAutoScanEnabled = false;
        autoScanSwitch.setChecked(false);
        stopAutoScan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}

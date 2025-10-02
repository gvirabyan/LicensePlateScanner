package com.example.licenseplatescanner;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
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

import com.example.licenseplatescanner.PlateRecognizerService;
import com.example.licenseplatescanner.PlateRecognitionResponse;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
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

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String TAG = "LPR_SCANNER";

    private PreviewView previewView;
    // Кнопка сканирования больше не нужна, так как анализ идет в потоке
    private ToggleButton flashlightButton;

    private Camera camera;
    private ExecutorService cameraExecutor;
    private PlateRecognizerService apiService;
    private long lastApiCall = 0;
    // Ограничение запросов до 1 раза в 1.5 секунды, чтобы не превысить лимит API
    private static final long API_CALL_INTERVAL_MS = 1500;

    // Флаг для предотвращения спама тостами о найденном номере
    private String lastRecognizedPlate = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Убедитесь, что ваш layout (activity_main.xml) содержит PreviewView с ID preview_view
        // и ToggleButton с ID flashlight_button
        previewView = findViewById(R.id.preview_view);
        flashlightButton = findViewById(R.id.flashlight_button);

        cameraExecutor = Executors.newSingleThreadExecutor();
        setupRetrofit();

        if (checkPermissions()) {
            startCamera();
        } else {
            requestPermissions();
        }

        flashlightButton.setOnClickListener(v -> toggleFlashlight());
    }

    private void setupRetrofit() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        // Уровень логирования BASIC или BODY для отладки
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
        // Проверяем только камеру, так как запись в getExternalFilesDir не требует WRITE_EXTERNAL_STORAGE
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

        imageAnalysis.setAnalyzer(cameraExecutor, new PlateAnalyzer());

        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
    }

    private class PlateAnalyzer implements ImageAnalysis.Analyzer {
        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            // Ограничение частоты вызовов API
            if (System.currentTimeMillis() - lastApiCall < API_CALL_INTERVAL_MS) {
                imageProxy.close();
                return;
            }
            lastApiCall = System.currentTimeMillis();

            // Конвертация ImageProxy в JPEG ByteArray
            byte[] imageBytes = imageProxyToJpegByteArray(imageProxy);
            imageProxy.close();

            if (imageBytes != null) {
                uploadImageForRecognition(imageBytes);
            }
        }
    }

    // **ВАЖНАЯ ФУНКЦИЯ КОНВЕРТАЦИИ**
    @OptIn(markerClass = ExperimentalGetImage.class)
    private byte[] imageProxyToJpegByteArray(ImageProxy imageProxy) {
        if (imageProxy.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Неподдерживаемый формат изображения: " + imageProxy.getFormat());
            return null;
        }

        Image image = imageProxy.getImage();
        if (image == null) return null;

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        // Копирование Y, V, U
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        // Создание YuvImage и сжатие в JPEG
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Сжатие с качеством 90%
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 90, out);

        return out.toByteArray();
    }


    private void uploadImageForRecognition(byte[] imageBytes) {
        RequestBody imageBody = RequestBody.create(MediaType.parse("image/jpeg"), imageBytes);
        MultipartBody.Part imagePart = MultipartBody.Part.createFormData("upload", "frame.jpg", imageBody);

        // Установка регионов: Массачусетс и США
        RequestBody regions = RequestBody.create(MediaType.parse("text/plain"), "us-massachusetts,us");

        apiService.uploadImage(PlateRecognizerService.API_KEY, imagePart, regions, "true")
                .enqueue(new Callback<PlateRecognitionResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<PlateRecognitionResponse> call,
                                           @NonNull Response<PlateRecognitionResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            PlateRecognitionResponse body = response.body();
                            if (body.results != null && body.results.length > 0) {
                                String plate = body.results[0].plate;

                                // Сохраняем в файл только если это новый номер
                                if (!plate.equals(lastRecognizedPlate)) {
                                    lastRecognizedPlate = plate;
                                    logLicensePlate(plate);
                                    Toast.makeText(MainActivity.this, "Номер найден: " + plate, Toast.LENGTH_LONG).show();
                                }
                            }
                        } else {
                            // Логируем ошибку, но не показываем Toast, чтобы не мешать пользователю
                            Log.e(TAG, "Ошибка API: " + response.code() + " " + response.message());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<PlateRecognitionResponse> call, @NonNull Throwable t) {
                        Log.e(TAG, "Сетевая ошибка", t);
                    }
                });
    }

    private void logLicensePlate(String licensePlate) {
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String logEntry = timeStamp + ", " + licensePlate + "\n";

        // Используем getExternalFilesDir() для Scoped Storage, не требуя WRITE_EXTERNAL_STORAGE
        File logFile = new File(getExternalFilesDir(null), "license_plates.txt");

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.append(logEntry);
            Toast.makeText(this, "Номер сохранен в файл.", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Ошибка сохранения номера в файл.", e);
            Toast.makeText(this, "Ошибка сохранения номера.", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFlashlight() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(flashlightButton.isChecked());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
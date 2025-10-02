package com.example.licenseplatescanner;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1;
    private PreviewView previewView;
    private Button scanButton;
    private ToggleButton flashlightButton;
    private ImageCapture imageCapture;
    private Camera camera;

    // ⭐ Твой облачный API токен Plate Recognizer
    private final String API_KEY = "f33976fb5fd347e31a11fa808efbed2b85cc31f1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview_view);
        scanButton = findViewById(R.id.scan_button);
        flashlightButton = findViewById(R.id.flashlight_button);
        flashlightButton.setVisibility(View.GONE);

        if (checkPermissions()) {
            startCamera();
        } else {
            requestPermissions();
        }

        scanButton.setOnClickListener(v -> takePicture());
        flashlightButton.setOnClickListener(v -> toggleFlashlight());
    }

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
            if (checkPermissions()) startCamera();
            else Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        imageCapture = new ImageCapture.Builder().build();

        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        if (camera.getCameraInfo().hasFlashUnit()) {
            flashlightButton.setVisibility(View.VISIBLE);
            flashlightButton.setChecked(false);
            camera.getCameraControl().enableTorch(false);
        } else {
            flashlightButton.setVisibility(View.GONE);
        }
    }

    private void takePicture() {
        if (imageCapture == null) return;

        File photoFile = getOutputMediaFile();
        if (photoFile == null) {
            Toast.makeText(this, "Failed to create file.", Toast.LENGTH_SHORT).show();
            return;
        }

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                recognizeLicensePlate(photoFile);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                exception.printStackTrace();
                Toast.makeText(MainActivity.this, "Image capture failed: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void recognizeLicensePlate(File imageFile) {
        OkHttpClient client = new OkHttpClient();

        RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("upload", imageFile.getName(),
                        RequestBody.create(imageFile, MediaType.get("image/jpeg")))
                .build();

        Request request = new Request.Builder()
                .url("https://api.platerecognizer.com/v1/plate-reader/")
                .addHeader("Authorization", "Token " + API_KEY)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error recognizing license plate.", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String plate = "Not detected";
                String body = response.body() != null ? response.body().string() : "";

                if (!body.isEmpty()) {
                    try {
                        JSONObject json = new JSONObject(body);
                        JSONArray results = json.getJSONArray("results");
                        if (results.length() > 0) {
                            plate = results.getJSONObject(0).getString("plate");
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

                final String finalPlate = plate;
                runOnUiThread(() -> {
                    logLicensePlate(finalPlate);
                    if ("Not detected".equals(finalPlate)) {
                        Toast.makeText(MainActivity.this, finalPlate, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "LP number " + finalPlate, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void logLicensePlate(String licensePlate) {
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String logEntry = timeStamp + " - " + licensePlate + "\n";

        File logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "license_plates.txt");
        try {
            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write(logEntry.getBytes());
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error logging license plate.", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFlashlight() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(flashlightButton.isChecked());
        }
    }

    private File getOutputMediaFile() {
        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "LicensePlateScanner");
        if (!mediaStorageDir.exists() && !mediaStorageDir.mkdirs()) return null;

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return new File(mediaStorageDir.getPath() + File.separator + "IMG_" + timeStamp + ".jpg");
    }
}

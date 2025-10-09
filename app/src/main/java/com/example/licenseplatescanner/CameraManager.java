package com.example.licenseplatescanner;

import android.content.Context;
import android.util.Log;
import android.util.Size;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public class CameraManager {

    private static final String TAG = "LPR_CameraManager";
    private Camera camera;
    private final Context context;
    private final ExecutorService cameraExecutor;
    private final LifecycleOwner lifecycleOwner;


    public CameraManager(Context context, LifecycleOwner lifecycleOwner, ExecutorService executor) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        this.cameraExecutor = executor;
    }

    private ProcessCameraProvider cameraProvider;

    public void startCamera(PreviewView previewView, ImageAnalysis.Analyzer analyzer) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(previewView, analyzer);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting the camera", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void bindCameraUseCases(PreviewView previewView, ImageAnalysis.Analyzer analyzer) {
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, analyzer);

        try {
            cameraProvider.unbindAll();

            if (previewView != null) {
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis);
            } else {
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis);
            }
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    public void stopCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    public void toggleFlashlight(boolean isChecked) {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(isChecked);
        }
    }
}
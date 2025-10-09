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

    public CameraManager(Context context, ExecutorService executor) {
        this.context = context;
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

        cameraProvider.unbindAll();

        if (previewView != null) {
            Preview preview = new Preview.Builder().build();
            imageAnalysis.setTargetRotation(previewView.getDisplay().getRotation());
            camera = cameraProvider.bindToLifecycle((LifecycleOwner) context, cameraSelector, preview, imageAnalysis);
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
        } else {
            // If there is no preview, we are in a service, bind only image analysis
            camera = cameraProvider.bindToLifecycle((LifecycleOwner) context, cameraSelector, imageAnalysis);
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
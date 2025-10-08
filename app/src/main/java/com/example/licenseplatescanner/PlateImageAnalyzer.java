package com.example.licenseplatescanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class PlateImageAnalyzer implements ImageAnalysis.Analyzer {

    private static final String TAG = "LPR_Analyzer";

    public interface ScanListener {
        void onImageReady(byte[] jpegBytes);
    }

    private final ScanListener listener;
    private volatile boolean scanRequested = false;

    public PlateImageAnalyzer(ScanListener listener) {
        this.listener = listener;
    }

    public void requestScan() {
        scanRequested = true;
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        if (scanRequested) {
            scanRequested = false;
            byte[] imageBytes = imageProxyToJpegByteArray(imageProxy);
            if (imageBytes != null) {
                listener.onImageReady(imageBytes);
            }
        }
        imageProxy.close();
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private byte[] imageProxyToJpegByteArray(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) return null;

        if (imageProxy.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Invalid format of image: " + imageProxy.getFormat());
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
        int offset = ySize;
        for (int i = 0; i < vSize; i++) {
            nv21[offset++] = vBuffer.get(i);
        }
        for (int i = 0; i < uSize; i++) {
            nv21[offset++] = uBuffer.get(i);
        }

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
}
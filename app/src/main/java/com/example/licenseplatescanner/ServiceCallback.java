package com.example.licenseplatescanner;

public interface ServiceCallback {
    void onPlateRecognized(String plate);
    void onScanStarted();
    void onScanStopped();
    void onScanError(String message);
    void onRemainingScansUpdated(int remainingScans);
}
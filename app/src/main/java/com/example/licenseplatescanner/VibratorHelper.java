package com.example.licenseplatescanner;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.Build;

public class VibratorHelper {
    public static void vibrate(Context context, long durationMs) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(durationMs);
            }
        }
    }
}

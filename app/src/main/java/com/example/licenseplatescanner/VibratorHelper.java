package com.example.licenseplatescanner;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

public class VibratorHelper {

    public static void vibrate(Context context, long durationMs) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return; // Нет вибромотора или сервис недоступен
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = VibrationEffect.createOneShot(
                        durationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE
                );
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(durationMs);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }
}

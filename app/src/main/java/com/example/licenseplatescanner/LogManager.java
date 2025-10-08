package com.example.licenseplatescanner;

import android.content.Context;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class LogManager {

    private static final String TAG = "LPR_LogManager";
    private static final String LOG_FILE_NAME = "license_plates.txt";

    private final Context context;
    private String lastPlate = ""; // Stores the last logged plate to prevent duplicates

    public LogManager(Context context) {
        this.context = context;
    }

    /**
     * Logs the recognized license plate to the external documents file.
     * Prevents logging the same plate consecutively.
     * @param licensePlate The plate string to log.
     */
    public void logLicensePlate(String licensePlate) {
        // Only log if the plate is different from the last one
        if (!Objects.equals(lastPlate, licensePlate)) {
            String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String logEntry = timeStamp + ", " + licensePlate + "\n";

            // Get the public Documents directory
            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (documentsDir != null && !documentsDir.exists()) {
                documentsDir.mkdirs();
            }

            File logFile = new File(documentsDir, LOG_FILE_NAME);

            // Use FileWriter with 'true' to append to the file
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.append(logEntry);
                vibrateShort(); // Give haptic feedback upon successful logging
            } catch (IOException e) {
                Log.e(TAG, "Error saving license plate to file.", e);
                // Note: Consider showing a Toast for file write error if it's critical
            }
            lastPlate = licensePlate;
        }
    }

    /**
     * Clears the content of the license plate log file.
     */
    public void resetLogFile() {
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File logFile = new File(documentsDir, LOG_FILE_NAME);

        if (logFile.exists()) {
            // Use FileWriter with 'false' to overwrite (clear) the file content
            try (FileWriter writer = new FileWriter(logFile, false)) {
                writer.write(""); // Clear the content
                Toast.makeText(context, "Log file cleared successfully", Toast.LENGTH_SHORT).show();
                lastPlate = ""; // Reset the last plate tracker
            } catch (IOException e) {
                Log.e(TAG, "Error clearing log file", e);
                Toast.makeText(context, "Error clearing log file", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(context, "Log file not found", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Triggers a short haptic feedback (vibration).
     */
    private void vibrateShort() {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // Use the modern API for devices Android O and above
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                // Use the deprecated API for older devices
                vibrator.vibrate(150);
            }
        }
    }
}
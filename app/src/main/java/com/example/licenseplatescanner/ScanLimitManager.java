package com.example.licenseplatescanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Calendar;
import java.util.Locale;

public class ScanLimitManager {

    private static final String TAG = "LPR_ScanLimitManager";
    private static final String PREFS_NAME = "ScanPrefs";
    private static final String KEY_REMAINING_SCANS = "remainingScans";
    private static final String KEY_LAST_RESET_DAY = "lastResetDayOfMonth"; // <-- Новое поле
    private static final int INITIAL_LIMIT = 2500;
    private static final int RESET_DAY = 23; // День месяца для сброса

    private final SharedPreferences sharedPreferences;
    private int remainingScans;

    public ScanLimitManager(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 1. Загрузка данных

        remainingScans = sharedPreferences.getInt(KEY_REMAINING_SCANS, INITIAL_LIMIT);
        int lastResetDay = sharedPreferences.getInt(KEY_LAST_RESET_DAY, 0);

        // 2. Проверка даты сброса
        checkAndPerformMonthlyReset(lastResetDay);

        Log.i(TAG, "Loaded remaining scans: " + remainingScans);
    }

    /**
     * Проверяет, прошло ли 23-е число месяца с момента последнего сброса.
     * Если да, сбрасывает счетчик до INITIAL_LIMIT (2500).
     */
    private void checkAndPerformMonthlyReset(int lastResetDay) {
        Calendar calendar = Calendar.getInstance(Locale.getDefault());
        int currentDay = calendar.get(Calendar.DAY_OF_MONTH);

        // Условие сброса:
        // 1. Сегодня 23-е (RESET_DAY) ИЛИ прошло 23-е число в новом месяце.
        // 2. Последний сброс был НЕ в текущем месяце.
        //    (Проверяем, что день последнего сброса меньше текущего дня,
        //     если мы уже прошли 23-е число, или что последний сброс был в прошлом месяце)

        boolean needsReset = false;

        // Сброс нужен, если сегодня 23-е, а в сохраненном значении не 23.
        if (currentDay == RESET_DAY && lastResetDay != RESET_DAY) {
            needsReset = true;
        }

        // Сброс нужен, если мы уже прошли 23-е число в этом месяце (т.е., currentDay > RESET_DAY),
        // но в памяти все еще записана дата сброса, меньшая 23 (значит, сброс не происходил).
        // НО: Самый простой и надежный способ — это просто проверять текущую дату и дату в памяти.

        // Более надежная проверка:
        // Если сегодня 23-е или позже, и последнее сохраненное число - прошлое (т.е. меньше 23-го).
        if (currentDay >= RESET_DAY && lastResetDay < RESET_DAY) {
            needsReset = true;
        }

        // ИЛИ (для случая, когда мы перешли через месяц):
        // Если сегодня до 23-го (например, 5-е), а последнее сохраненное число - 23-е.
        // Этот случай не требует немедленного сброса, он произойдет 23-го.

        if (needsReset) {
            remainingScans = INITIAL_LIMIT;

            // Сохраняем текущий день как день последнего сброса
            sharedPreferences.edit()
                    .putInt(KEY_REMAINING_SCANS, remainingScans)
                    .putInt(KEY_LAST_RESET_DAY, currentDay)
                    .apply();

            Log.i(TAG, "Monthly limit reset performed on day " + currentDay);
        }
    }

    /**
     * Decrements the remaining scan count by 1 and saves the new value.
     */
    public void decrementScanCount() {
        if (remainingScans > 0) {
            remainingScans--;
            sharedPreferences.edit().putInt(KEY_REMAINING_SCANS, remainingScans).apply();
            Log.d(TAG, "Scan decremented. Remaining: " + remainingScans);
        } else {
            Log.w(TAG, "Scan limit reached. Cannot decrement further.");
        }
    }

    /**
     * Checks if there are any available scans remaining.
     */
    public boolean hasRemainingScans() {
        return remainingScans > 0;
    }

    /**
     * Returns the current number of remaining scans.
     */
    public int getRemainingScans() {
        return remainingScans;
    }

    /**
     * (Optional) Resets the counter to the initial limit (2500) manually.
     */
    public void resetLimitManually() {
        remainingScans = INITIAL_LIMIT;
        // При ручном сбросе также записываем текущий день, чтобы избежать автосброса в тот же день
        Calendar calendar = Calendar.getInstance(Locale.getDefault());
        int currentDay = calendar.get(Calendar.DAY_OF_MONTH);

        sharedPreferences.edit()
                .putInt(KEY_REMAINING_SCANS, INITIAL_LIMIT)
                .putInt(KEY_LAST_RESET_DAY, currentDay)
                .apply();
        Log.i(TAG, "Scan limit manually reset to " + INITIAL_LIMIT);
    }
}
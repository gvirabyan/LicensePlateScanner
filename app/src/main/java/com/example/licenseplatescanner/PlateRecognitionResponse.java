package com.example.licenseplatescanner;

import com.google.gson.annotations.SerializedName;

// Упрощенная модель для получения только номера
public class PlateRecognitionResponse {
    @SerializedName("results")
    public Result[] results;

    public static class Result {
        @SerializedName("plate")
        public String plate;

        // Можете добавить другие поля (например, confidence, region, box), если нужно
    }
}
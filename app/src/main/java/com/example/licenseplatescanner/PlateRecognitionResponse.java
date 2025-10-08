package com.example.licenseplatescanner;

import com.google.gson.annotations.SerializedName;

public class PlateRecognitionResponse {
    @SerializedName("results")
    public Result[] results;

    public static class Result {
        @SerializedName("plate")
        public String plate;

    }
}
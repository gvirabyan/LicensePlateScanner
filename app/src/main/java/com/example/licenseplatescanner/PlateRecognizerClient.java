package com.example.licenseplatescanner;

import androidx.annotation.NonNull;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class PlateRecognizerClient {

    private static final String BASE_URL = "https://api.platerecognizer.com/v1/";
    private final PlateRecognizerService apiService;

    public PlateRecognizerClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        apiService = retrofit.create(PlateRecognizerService.class);
    }

    public void recognizePlate(byte[] imageBytes, @NonNull Callback<PlateRecognitionResponse> callback) {
        RequestBody imageBody = RequestBody.create(MediaType.parse("image/jpeg"), imageBytes);
        MultipartBody.Part imagePart = MultipartBody.Part.createFormData("upload", "frame.jpg", imageBody);

        RequestBody regions = RequestBody.create(MediaType.parse("text/plain"), "us");

        String authToken = "Token " + PlateRecognizerService.API_KEY;

        apiService.uploadImage(authToken, imagePart, regions, "true").enqueue(callback);
    }
}
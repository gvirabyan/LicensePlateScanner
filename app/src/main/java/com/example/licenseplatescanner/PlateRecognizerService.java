package com.example.licenseplatescanner;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Query;

public interface PlateRecognizerService {
    String API_KEY = "f33976fb5fd347e31a11fa808efbed2b85cc31f1"; // !!! ЗАМЕНИТЕ НА ВАШ КЛЮЧ !!!

    @Multipart
    @POST("plate-reader/")
    Call<PlateRecognitionResponse> uploadImage(
            @Header("Authorization") String authHeader,
            @Part MultipartBody.Part image,
            @Part("regions") RequestBody regions,
            @Query("config") String config // Для дополнительных настроек
    );
}
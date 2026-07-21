package com.whyfi.app.data.remote

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface WhyfiApiService {
    @POST("scan-sessions/")
    suspend fun uploadScanSession(
        @Header("Authorization") authorization: String,
        @Body payload: ScanSessionUploadRequest,
    ): Response<ScanSessionResponse>
}

object ApiClientFactory {
    fun create(backendBaseUrl: String): WhyfiApiService {
        val normalized = if (backendBaseUrl.endsWith("/")) backendBaseUrl else "$backendBaseUrl/"
        return Retrofit.Builder()
            .baseUrl("${normalized}api/v1/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WhyfiApiService::class.java)
    }
}

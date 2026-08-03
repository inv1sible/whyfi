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

    /** One round trip for remote control: the body is what this device is
     * doing, the response is what it should be doing. Combined into a single
     * call because the poll runs continuously — two would double the request
     * count and open a read-modify-write window for no benefit. */
    @POST("sensors/me/heartbeat/")
    suspend fun sensorHeartbeat(
        @Header("Authorization") authorization: String,
        @Body report: SensorHeartbeatRequest,
    ): Response<ScanPolicyResponse>

    /** Manual, one-off — see ui/SettingsScreen.kt's Diagnostics section.
     * Deliberately not part of UploadWorker's durable outbox/retry pipeline:
     * this is a rare, user-triggered action, and the source (the crash log
     * file) isn't lost if the send fails, so there's nothing a retry queue
     * would protect here that a "tap the button again" doesn't already. */
    @POST("crash-reports/")
    suspend fun uploadCrashReport(
        @Header("Authorization") authorization: String,
        @Body report: CrashReportRequest,
    ): Response<CrashReportResponse>
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

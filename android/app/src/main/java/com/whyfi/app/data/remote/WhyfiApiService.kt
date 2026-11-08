package com.whyfi.app.data.remote

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface WhyfiApiService {
    @POST("scan-sessions/")
    suspend fun uploadScanSession(
        @Header("Authorization") authorization: String,
        @Body payload: ScanSessionUploadRequest,
    ): Response<ScanSessionResponse>

    /** Latest scan-session summaries (newest first), for backfilling the
     * Dashboard on a fresh install. Uses the same sensor-token auth as
     * ingest — see ScanSessionViewSet.get_authenticators(), which routes
     * list/retrieve to session auth for the PWA but also accepts sensor
     * tokens (the action-aware split only swaps *which* authenticator is
     * primary, and TokenAuthentication is in the DRF default chain). */
    @GET("scan-sessions/")
    suspend fun listScanSessions(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 1,
    ): Response<ScanSessionListResponse>

    // Per-radio observation fetches for a single session — the detail actions
    // on ScanSessionViewSet (url_path="wifi-observations" etc.). The response
    // is a bare JSON array of observation objects, not paginated.

    @GET("scan-sessions/{id}/wifi-observations/")
    suspend fun getWifiObservations(
        @Header("Authorization") authorization: String,
        @Path("id") sessionId: String,
    ): Response<List<WifiObservationResponseDto>>

    @GET("scan-sessions/{id}/cell-observations/")
    suspend fun getCellObservations(
        @Header("Authorization") authorization: String,
        @Path("id") sessionId: String,
    ): Response<List<CellObservationResponseDto>>

    @GET("scan-sessions/{id}/ble-observations/")
    suspend fun getBleObservations(
        @Header("Authorization") authorization: String,
        @Path("id") sessionId: String,
    ): Response<List<BleObservationResponseDto>>

    @GET("scan-sessions/{id}/satellite-observations/")
    suspend fun getSatelliteObservations(
        @Header("Authorization") authorization: String,
        @Path("id") sessionId: String,
    ): Response<List<SatelliteObservationResponseDto>>

    @GET("scan-sessions/{id}/lan-observations/")
    suspend fun getLanObservations(
        @Header("Authorization") authorization: String,
        @Path("id") sessionId: String,
    ): Response<List<LanObservationResponseDto>>

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

    /** Feeds the Mission view — see mission/MissionController.kt. The first
     * GET this service has ever needed; every call above is a POST. */
    @GET("mission/wifi-observations/")
    suspend fun missionWifiObservations(
        @Header("Authorization") authorization: String,
        @Query("ssid_exact") ssidExact: String,
        @Query("near_lat") nearLat: Double,
        @Query("near_lng") nearLng: Double,
        @Query("near_radius_m") nearRadiusM: Double,
    ): Response<MissionWifiObservationsResponse>

    @GET("mission/ble-observations/")
    suspend fun missionBleObservations(
        @Header("Authorization") authorization: String,
        @Query("device_key_exact") deviceKeyExact: String,
        @Query("near_lat") nearLat: Double,
        @Query("near_lng") nearLng: Double,
        @Query("near_radius_m") nearRadiusM: Double,
    ): Response<MissionBleObservationsResponse>

    @GET("mission/cell-observations/")
    suspend fun missionCellObservations(
        @Header("Authorization") authorization: String,
        @Query("tower_key_exact") towerKeyExact: String,
        @Query("near_lat") nearLat: Double,
        @Query("near_lng") nearLng: Double,
        @Query("near_radius_m") nearRadiusM: Double,
    ): Response<MissionCellObservationsResponse>
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

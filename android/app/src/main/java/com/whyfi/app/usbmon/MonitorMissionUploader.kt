package com.whyfi.app.usbmon

import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

private data class UploadReceipt(@SerializedName("id") val id: String)

private interface MonitorUploadApi {
    @Multipart
    @POST("monitor-missions/upload/")
    suspend fun upload(
        @Header("Authorization") authorization: String,
        @Part("id") id: okhttp3.RequestBody,
        @Part report: MultipartBody.Part,
        @Part log: MultipartBody.Part,
        @Part pcap: MultipartBody.Part?,
    ): Response<UploadReceipt>
}

/** The upload is explicit and keeps all local files even after success. */
object MonitorMissionUploader {
    suspend fun upload(mission: MonitorMission.Stored, backendUrl: String, token: String): String =
        withContext(Dispatchers.IO) {
            if (!mission.complete) return@withContext "Wait for the report and log to finish."
            if (backendUrl.isBlank() || token.isBlank()) return@withContext "Configure backend URL and sensor token in Settings."
            val base = backendUrl.trimEnd('/') + "/api/v1/"
            try {
                val client = OkHttpClient.Builder()
                    .writeTimeout(15, TimeUnit.MINUTES)
                    .readTimeout(2, TimeUnit.MINUTES)
                    .build()
                val api = Retrofit.Builder().baseUrl(base).client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build().create(MonitorUploadApi::class.java)
                fun part(name: String, file: java.io.File, mediaType: String) =
                    MultipartBody.Part.createFormData(name, file.name, RequestBody.create(MediaType.parse(mediaType), file))
                val response = api.upload(
                    "Token $token",
                    RequestBody.create(MediaType.parse("text/plain"), mission.directory.name),
                    part("report", mission.report, "application/pdf"),
                    part("log", mission.log, "application/x-ndjson"),
                    mission.pcap?.let { part("pcap", it, "application/vnd.tcpdump.pcap") },
                )
                if (response.isSuccessful && response.body()?.id == mission.directory.name) {
                    MonitorMission.markUploaded(mission)
                    "Uploaded. Local files retained."
                } else "Upload failed (HTTP ${response.code()})."
            } catch (e: Exception) {
                "Upload failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
}

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
        @Part report: MultipartBody.Part?,
        @Part log: MultipartBody.Part,
        @Part pcap: MultipartBody.Part?,
    ): Response<UploadReceipt>
}

/**
 * Uploads one mission: the log always (the server imports its AP readings),
 * the PDF if one was made, the PCAP only when [includePcap] (Wi‑Fi). Open
 * missions too; uploading again replaces the server copy. Local files stay.
 */
object MonitorMissionUploader {
    /** The server's one-sentence reason, e.g. which file is too large (413). */
    fun serverDetail(response: Response<*>): String? = runCatching {
        val text = response.errorBody()?.string().orEmpty().take(2_000)
        com.google.gson.JsonParser.parseString(text).asJsonObject.get("detail").asString.trim().take(200).takeIf { it.isNotEmpty() }
    }.getOrNull()

    suspend fun upload(mission: MonitorMission.Stored, backendUrl: String, token: String, includePcap: Boolean = true): String =
        withContext(Dispatchers.IO) {
            if (!mission.log.isFile) return@withContext "No mission log to upload."
            if (backendUrl.isBlank() || token.isBlank()) return@withContext "Configure backend URL and sensor token in Settings."
            val base = backendUrl.trimEnd('/') + "/api/v1/"
            val logCopy = java.io.File.createTempFile("mission-upload", ".jsonl")
            try {
                val logBytes = MonitorMission.copyLog(mission.log, logCopy)
                val client = OkHttpClient.Builder()
                    .writeTimeout(15, TimeUnit.MINUTES)
                    .readTimeout(2, TimeUnit.MINUTES)
                    .build()
                val api = Retrofit.Builder().baseUrl(base).client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build().create(MonitorUploadApi::class.java)
                fun part(name: String, file: java.io.File, fileName: String, mediaType: String) =
                    MultipartBody.Part.createFormData(name, fileName, RequestBody.create(MediaType.parse(mediaType), file))
                val response = api.upload(
                    "Token $token",
                    RequestBody.create(MediaType.parse("text/plain"), mission.directory.name),
                    mission.report.takeIf { it.isFile }?.let { part("report", it, "report.pdf", "application/pdf") },
                    part("log", logCopy, "mission.jsonl", "application/x-ndjson"),
                    mission.pcap?.takeIf { includePcap && it.isFile }?.let { part("pcap", it, "capture.pcap", "application/vnd.tcpdump.pcap") },
                )
                if (response.isSuccessful && response.body()?.id == mission.directory.name) {
                    MonitorMission.markUploaded(mission, logBytes)
                    "Uploaded" + (if (mission.pcap != null && !includePcap) " (PCAP waits for Wi‑Fi)" else "") + ". Local files retained."
                } else "Upload failed (HTTP ${response.code()})." + (serverDetail(response)?.let { " $it" } ?: "")
            } catch (e: Exception) {
                "Upload failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                logCopy.delete()
            }
        }
}

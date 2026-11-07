package com.whyfi.app.diagnostics

import android.os.Build
import com.whyfi.app.BuildConfig
import com.whyfi.app.data.remote.ApiClientFactory
import com.whyfi.app.data.remote.CrashReportRequest
import java.io.IOException

/**
 * Uploads one report to POST /crash-reports/. Shared by the Settings
 * screen's crash log and by diagnostic reports (e.g. the USB monitor test),
 * which reuse the same endpoint: the backend stores free text plus device
 * facts, and the web UI's Crash Reports page is where both get read.
 */
object CrashReportSender {

    /** Label for a crash logged before versions were recorded: the crash
     * came from this build or an older one. */
    fun unknownVersionLabel(sender: String = BuildConfig.VERSION_NAME): String = "≤$sender (unrecorded)".take(32)

    /** A user-facing result line, never an exception — callers are screens. */
    /** @param appVersion the version that produced [text]; defaults to this
     * build, which is right for reports made just now (e.g. diagnostics). */
    suspend fun send(
        backendUrl: String,
        token: String,
        occurredAt: String,
        text: String,
        appVersion: String = BuildConfig.VERSION_NAME,
    ): String {
        if (backendUrl.isBlank() || token.isBlank()) {
            return "Configure a backend URL and sensor token in Settings first."
        }
        return try {
            val response = ApiClientFactory.create(backendUrl).uploadCrashReport(
                "Token $token",
                CrashReportRequest(
                    occurredAt = occurredAt,
                    appVersion = appVersion.take(32), // backend max_length
                    deviceModel = Build.MODEL,
                    osVersion = Build.VERSION.RELEASE,
                    stackTrace = text,
                ),
            )
            if (response.isSuccessful) {
                "Sent — view it on the web UI's Crash Reports page."
            } else {
                "Send failed (HTTP ${response.code()})."
            }
        } catch (e: IOException) {
            "Could not reach the backend: ${e.message}"
        }
    }
}

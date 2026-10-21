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

    /** A user-facing result line, never an exception — callers are screens. */
    suspend fun send(backendUrl: String, token: String, occurredAt: String, text: String): String {
        if (backendUrl.isBlank() || token.isBlank()) {
            return "Configure a backend URL and sensor token in Settings first."
        }
        return try {
            val response = ApiClientFactory.create(backendUrl).uploadCrashReport(
                "Token $token",
                CrashReportRequest(
                    occurredAt = occurredAt,
                    appVersion = BuildConfig.VERSION_NAME,
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

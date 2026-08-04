package com.whyfi.app.scan

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import java.io.File

/**
 * Persists the last completed scan pass (and only that) to a small JSON file
 * in the app's private storage, so the Dashboard isn't blank after a force-stop
 * or reboot.
 *
 * Deliberately NOT a Room table — AGENT.md is explicit: the outbox is
 * write-then-delete, the full survey belongs on the backend, and the phone
 * keeps only the last two passes in memory. This file is the minimal survival
 * of just `latestPass` + `completedScanCount` across process death; everything
 * else (SurveyStats, previousPass, the outbox) stays session-scoped.
 */
object LastScanStore {

    private const val FILE_NAME = "last_scan.json"
    private val gson = Gson()

    /** Wrapper so we persist the count alongside the pass in one file. */
    private data class PersistedPass(
        @SerializedName("completed_scan_count") val completedScanCount: Int,
        @SerializedName("latest_pass") val latestPass: ScanSessionUploadRequest?,
    )

    fun save(context: Context, completedScanCount: Int, latestPass: ScanSessionUploadRequest?) {
        if (latestPass == null) return
        val data = PersistedPass(completedScanCount, latestPass)
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(gson.toJson(data))
        }
    }

    /** Returns (completedScanCount, latestPass) or null if the file is absent
     * or unreadable. On a successful read the count is whatever was persisted;
     * the caller may choose to treat it as a floor rather than exact. */
    fun load(context: Context): Pair<Int, ScanSessionUploadRequest>? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            val data = gson.fromJson(file.readText(), PersistedPass::class.java)
            if (data?.latestPass != null) {
                data.completedScanCount to data.latestPass
            } else null
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}

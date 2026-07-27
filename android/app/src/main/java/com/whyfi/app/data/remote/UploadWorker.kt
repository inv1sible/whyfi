package com.whyfi.app.data.remote

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.local.WhyfiDatabase
import java.io.IOException

/** Drains the Room outbox to the backend. Retries (via WorkManager) on
 * network failure; deletes each entry once the backend accepts it — or once
 * the backend has made clear it will never accept it (see [isPermanentReject]). */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        val backendUrl = settings.backendUrl
        val token = settings.sensorToken
        if (backendUrl.isNullOrBlank() || token.isNullOrBlank()) {
            return Result.failure()
        }

        val dao = WhyfiDatabase.getInstance(applicationContext).pendingScanDao()
        val pending = dao.getAll()
        if (pending.isEmpty()) return Result.success()

        val api = ApiClientFactory.create(backendUrl)
        val gson = Gson()
        var anyFailure = false

        for (entity in pending) {
            try {
                val payload = gson.fromJson(entity.payloadJson, ScanSessionUploadRequest::class.java)
                val response = api.uploadScanSession("Token $token", payload)
                when {
                    response.isSuccessful -> dao.delete(entity.clientScanId)
                    isPermanentReject(response.code()) -> {
                        // Retrying this forever would wedge the whole queue
                        // behind it: every later scan stays stuck, the phone
                        // keeps scanning, the map stays empty, and nothing
                        // anywhere says why. Drop the one bad payload instead.
                        dao.delete(entity.clientScanId)
                    }
                    else -> anyFailure = true
                }
            } catch (e: IOException) {
                anyFailure = true
            } catch (e: JsonSyntaxException) {
                // Unparseable row (truncated write, or an old schema this
                // build no longer understands) — it can never succeed.
                dao.delete(entity.clientScanId)
            }
        }

        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        /** 4xx means the backend understood and refused, so a retry sends the
         * identical bytes to the identical rejection. The exceptions are the
         * ones that aren't really about the payload:
         *  - 401/403: wrong or revoked sensor token. Fixable in Settings, and
         *    the scan data is still good — keep it queued.
         *  - 408/429: timeout and rate-limit, both explicitly "try again".
         */
        private fun isPermanentReject(code: Int): Boolean =
            code in 400..499 && code !in setOf(401, 403, 408, 429)

        private const val UNIQUE_WORK_NAME = "whyfi-outbox-drain"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // Unique + APPEND_OR_REPLACE rather than a plain enqueue: under
            // continuous scanning this fires once per pass, and unnamed
            // requests would pile up into many parallel workers all draining
            // the same outbox — redundant uploads of identical payloads
            // (harmless, since ingest is idempotent on client_scan_id, but a
            // waste of radio and battery). Appending keeps them serialized
            // while still guaranteeing a drain attempt after every scan.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

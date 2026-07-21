package com.whyfi.app.data.remote

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.local.WhyfiDatabase
import java.io.IOException

/** Drains the Room outbox to the backend. Retries (via WorkManager) on
 * network failure; deletes each entry once the backend accepts it. */
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
                if (response.isSuccessful) {
                    dao.delete(entity.clientScanId)
                } else {
                    anyFailure = true
                }
            } catch (e: IOException) {
                anyFailure = true
            }
        }

        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

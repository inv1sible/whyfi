package com.whyfi.app.usbmon

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.whyfi.app.data.SettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Automatic USB mission upload (user, 2026-11-11: "upload everything").
 * After every Stop the log goes up on any network; the PCAP follows on an
 * unmetered one. WorkManager retries with backoff, so a dead spot just delays it.
 */
class MonitorUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        if (!settings.isConfigured) return Result.failure()
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val includePcap = inputData.getBoolean(KEY_PCAP, false)
        val directory = File(applicationContext.getExternalFilesDir(null), "monitor-missions/$id")
        val mission = MonitorMission.listIn(directory.parentFile ?: return Result.failure())
            .firstOrNull { it.directory.name == id } ?: return Result.success() // deleted meanwhile
        if (!includePcap && MonitorMission.isUploaded(directory)) return Result.success()
        val message = MonitorMissionUploader.upload(mission, settings.backendUrl.orEmpty(), settings.sensorToken.orEmpty(), includePcap)
        return when {
            message.startsWith("Uploaded") -> Result.success()
            message.contains("HTTP 4") && !message.contains("HTTP 408") && !message.contains("HTTP 429") -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        private const val KEY_ID = "mission_id"
        private const val KEY_PCAP = "include_pcap"

        /** Queues the log now (any network) and, if there is a PCAP, log + PCAP on Wi‑Fi. */
        fun enqueue(context: Context, missionId: String, hasPcap: Boolean) {
            val work = WorkManager.getInstance(context)
            fun request(pcap: Boolean, network: NetworkType) = OneTimeWorkRequestBuilder<MonitorUploadWorker>()
                .setInputData(workDataOf(KEY_ID to missionId, KEY_PCAP to pcap))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            work.enqueueUniqueWork("monitor-upload-$missionId", ExistingWorkPolicy.REPLACE, request(false, NetworkType.CONNECTED))
            if (hasPcap) work.enqueueUniqueWork("monitor-upload-pcap-$missionId", ExistingWorkPolicy.REPLACE,
                request(true, NetworkType.UNMETERED))
        }
    }
}

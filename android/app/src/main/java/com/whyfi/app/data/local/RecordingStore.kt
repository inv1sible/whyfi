package com.whyfi.app.data.local

import android.content.Context
import com.google.gson.Gson
import com.whyfi.app.data.SettingsRepository
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.UploadWorker
import java.util.UUID

/**
 * The phone's scan history: recordings and their passes in the local
 * database, kept whether or not a server is set up (see RecordingEntity).
 * With a server, passes of recordings allowed to sync are uploaded by
 * UploadWorker and stay here marked uploaded; the storage limit then removes
 * uploaded recordings first (StoragePolicy).
 */
class RecordingStore(private val context: Context) {
    private val db get() = WhyfiDatabase.getInstance(context)
    private val settings = SettingsRepository(context)
    private val gson = Gson()

    suspend fun begin(kind: String, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        db.recordingDao().insert(RecordingEntity(id, kind, now, syncAllowed = settings.isConfigured))
        return id
    }

    suspend fun end(id: String, publicIp: String?, now: Long = System.currentTimeMillis()) {
        // Stopped before a pass finished: nothing worth listing.
        if (db.recordingDao().passCount(id) == 0) {
            db.recordingDao().delete(listOf(id))
            return
        }
        db.recordingDao().end(id, now, publicIp)
        enforceLimit()
    }

    /** Stores a pass in [recordingId], or in a one-pass recording of [kind] when null. */
    suspend fun store(payload: ScanSessionUploadRequest, recordingId: String?, kind: String) {
        val now = System.currentTimeMillis()
        val id = recordingId ?: begin(kind, now)
        db.pendingScanDao().insert(
            PendingScanEntity(payload.clientScanId, gson.toJson(payload), now, recordingId = id),
        )
        if (recordingId == null) db.recordingDao().end(id, now, null)
        enforceLimit()
        if (settings.isConfigured) UploadWorker.enqueue(context)
    }

    suspend fun summaries(): List<RecordingSummary> = db.recordingDao().summaries()

    suspend fun passes(id: String): List<ScanSessionUploadRequest> =
        db.recordingDao().payloads(id).mapNotNull { runCatching { gson.fromJson(it, ScanSessionUploadRequest::class.java) }.getOrNull() }

    suspend fun rawJson(id: String): String = "[" + db.recordingDao().payloads(id).joinToString(",\n") + "]"

    suspend fun delete(ids: List<String>) = db.recordingDao().delete(ids)

    /** Lets these recordings go to the server (after ask on connect, or one by one). */
    suspend fun allowSync(ids: List<String>) {
        if (ids.isEmpty()) return
        db.recordingDao().allowSync(ids)
        if (settings.isConfigured) UploadWorker.enqueue(context)
    }

    /** Closes recordings a killed process left open, except [keepOpen]. */
    suspend fun closeStale(keepOpen: List<String>) = db.recordingDao().closeStale(keepOpen.ifEmpty { listOf("") })

    data class Usage(val bytes: Long, val recordings: Int, val waiting: Int, val localOnly: Int, val overBytes: Long)

    suspend fun usage(): Usage {
        val summaries = summaries()
        val loose = db.pendingScanDao().looseSizes()
        val plan = StoragePolicy.plan(items(summaries, loose), settings.outboxQuotaBytes)
        return Usage(
            bytes = summaries.sumOf { it.bytes } + loose.sumOf { it.bytes },
            recordings = summaries.size,
            waiting = db.pendingScanDao().uploadableCount(),
            localOnly = summaries.count { !it.syncAllowed },
            overBytes = plan.overBytes,
        )
    }

    /** Applies the storage limit: removes uploaded recordings, oldest first. */
    suspend fun enforceLimit(): StoragePolicy.Plan {
        val summaries = summaries()
        val loose = db.pendingScanDao().looseSizes()
        val plan = StoragePolicy.plan(items(summaries, loose), settings.outboxQuotaBytes)
        val looseKeys = loose.map { LOOSE + it.clientScanId }.toSet()
        db.recordingDao().delete(plan.remove.filter { it !in looseKeys })
        plan.remove.filter { it in looseKeys }.map { it.removePrefix(LOOSE) }.takeIf { it.isNotEmpty() }
            ?.let { db.pendingScanDao().deleteAll(it) }
        return plan
    }

    private fun items(summaries: List<RecordingSummary>, loose: List<LooseScanSize>) =
        summaries.map(StoragePolicy::item) +
            loose.map { StoragePolicy.Item(LOOSE + it.clientScanId, it.bytes, it.createdAtEpochMs, removable = it.syncState != PendingScanEntity.SYNC_WAITING) }

    private companion object {
        const val LOOSE = "scan:"
    }
}

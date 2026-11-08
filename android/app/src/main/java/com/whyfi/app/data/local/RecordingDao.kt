package com.whyfi.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(recording: RecordingEntity)

    @Query("UPDATE recordings SET endedAtMs = :endedAtMs, publicIp = COALESCE(:publicIp, publicIp) WHERE id = :id")
    suspend fun end(id: String, endedAtMs: Long, publicIp: String?)

    @Query("SELECT COUNT(*) FROM pending_scans WHERE recordingId = :id")
    suspend fun passCount(id: String): Int

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: String): RecordingEntity?

    /** CAST to BLOB: LENGTH() on TEXT counts characters, not bytes. */
    @Query(
        """
        SELECT r.id, r.kind, r.startedAtMs, r.endedAtMs, r.syncAllowed, r.publicIp,
               COUNT(p.clientScanId) AS passes,
               COALESCE(SUM(LENGTH(CAST(p.payloadJson AS BLOB))), 0) AS bytes,
               COALESCE(SUM(p.syncState = 1), 0) AS uploaded,
               COALESCE(SUM(p.syncState = 2), 0) AS rejected
        FROM recordings r LEFT JOIN pending_scans p ON p.recordingId = r.id
        GROUP BY r.id ORDER BY r.startedAtMs DESC
        """,
    )
    suspend fun summaries(): List<RecordingSummary>

    @Query("SELECT payloadJson FROM pending_scans WHERE recordingId = :id ORDER BY createdAtEpochMs ASC")
    suspend fun payloads(id: String): List<String>

    @Query("UPDATE recordings SET syncAllowed = 1 WHERE id IN (:ids)")
    suspend fun allowSync(ids: List<String>)

    @Query("DELETE FROM pending_scans WHERE recordingId IN (:ids)")
    suspend fun deletePasses(ids: List<String>)

    @Query("DELETE FROM recordings WHERE id IN (:ids)")
    suspend fun deleteRecordings(ids: List<String>)

    @Transaction
    suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        deletePasses(ids)
        deleteRecordings(ids)
    }

    /** Recordings that never went to a server, oldest first (ask on connect). */
    @Query("SELECT * FROM recordings WHERE syncAllowed = 0 AND endedAtMs IS NOT NULL ORDER BY startedAtMs ASC")
    suspend fun localOnly(): List<RecordingEntity>

    /** A recording left open by a killed process: closed at its last pass. */
    @Query(
        """
        UPDATE recordings SET endedAtMs = COALESCE(
            (SELECT MAX(createdAtEpochMs) FROM pending_scans WHERE recordingId = recordings.id), startedAtMs)
        WHERE endedAtMs IS NULL AND id NOT IN (:keepOpen)
        """,
    )
    suspend fun closeStale(keepOpen: List<String>)
}

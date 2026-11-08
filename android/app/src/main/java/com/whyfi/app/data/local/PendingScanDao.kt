package com.whyfi.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Just the id and on-disk size of one queued scan — enough to enforce the
 * outbox quota without loading every payload into memory. */
data class PendingScanSize(val clientScanId: String, val bytes: Long)

/** A pass stored before recordings existed (database v1). */
data class LooseScanSize(val clientScanId: String, val bytes: Long, val syncState: Int, val createdAtEpochMs: Long)

@Dao
interface PendingScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingScanEntity)

    @Query("SELECT * FROM pending_scans ORDER BY createdAtEpochMs ASC")
    suspend fun getAll(): List<PendingScanEntity>

    /** Passes waiting for the server: not uploaded yet, and either from v1
     * (no recording) or in a recording allowed to sync. */
    @Query(
        """
        SELECT * FROM pending_scans WHERE syncState = 0 AND (recordingId IS NULL OR recordingId IN
            (SELECT id FROM recordings WHERE syncAllowed = 1)) ORDER BY createdAtEpochMs ASC
        """,
    )
    suspend fun uploadable(): List<PendingScanEntity>

    @Query("UPDATE pending_scans SET syncState = :state WHERE clientScanId = :clientScanId")
    suspend fun setSyncState(clientScanId: String, state: Int)

    @Query(
        """
        SELECT COUNT(*) FROM pending_scans WHERE syncState = 0 AND (recordingId IS NULL OR recordingId IN
            (SELECT id FROM recordings WHERE syncAllowed = 1))
        """,
    )
    suspend fun uploadableCount(): Int

    /** v1 rows (no recording) with their size and state, for the storage limit. */
    @Query("SELECT clientScanId, LENGTH(CAST(payloadJson AS BLOB)) AS bytes, syncState, createdAtEpochMs FROM pending_scans WHERE recordingId IS NULL")
    suspend fun looseSizes(): List<LooseScanSize>

    @Query("DELETE FROM pending_scans WHERE clientScanId = :clientScanId")
    suspend fun delete(clientScanId: String)

    @Query("SELECT COUNT(*) FROM pending_scans")
    suspend fun count(): Int

    /** CAST to BLOB matters: SQLite's LENGTH() on TEXT counts characters,
     * not bytes, and scan payloads contain plenty of non-ASCII (SSIDs,
     * device names). Returns null when the table is empty. */
    @Query("SELECT SUM(LENGTH(CAST(payloadJson AS BLOB))) FROM pending_scans")
    suspend fun totalBytes(): Long?

    @Query("SELECT clientScanId, LENGTH(CAST(payloadJson AS BLOB)) AS bytes FROM pending_scans ORDER BY createdAtEpochMs ASC")
    suspend fun sizesOldestFirst(): List<PendingScanSize>

    @Query("DELETE FROM pending_scans WHERE clientScanId IN (:clientScanIds)")
    suspend fun deleteAll(clientScanIds: List<String>)
}

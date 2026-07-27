package com.whyfi.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Just the id and on-disk size of one queued scan — enough to enforce the
 * outbox quota without loading every payload into memory. */
data class PendingScanSize(val clientScanId: String, val bytes: Long)

@Dao
interface PendingScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingScanEntity)

    @Query("SELECT * FROM pending_scans ORDER BY createdAtEpochMs ASC")
    suspend fun getAll(): List<PendingScanEntity>

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

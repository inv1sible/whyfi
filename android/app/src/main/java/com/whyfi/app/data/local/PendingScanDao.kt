package com.whyfi.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingScanEntity)

    @Query("SELECT * FROM pending_scans ORDER BY createdAtEpochMs ASC")
    suspend fun getAll(): List<PendingScanEntity>

    @Query("DELETE FROM pending_scans WHERE clientScanId = :clientScanId")
    suspend fun delete(clientScanId: String)
}

package com.whyfi.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The offline outbox: a completed scan pass is written here immediately,
 * then [com.whyfi.app.data.remote.UploadWorker] drains it to the backend.
 * Scanning keeps working with no connectivity; nothing is lost.
 */
@Entity(tableName = "pending_scans")
data class PendingScanEntity(
    @PrimaryKey val clientScanId: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
)

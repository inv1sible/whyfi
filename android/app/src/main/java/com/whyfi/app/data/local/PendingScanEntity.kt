package com.whyfi.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One stored scan pass, in exactly the upload format (payloadJson is a
 * ScanSessionUploadRequest). Since database v2 this is the phone's own scan
 * history, not just an outbox: a pass belongs to a [RecordingEntity] and stays
 * after upload ([syncState] UPLOADED) until the storage limit removes its
 * recording (see StoragePolicy). Rows from v1 have no recording.
 */
@Entity(tableName = "pending_scans")
data class PendingScanEntity(
    @PrimaryKey val clientScanId: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
    val recordingId: String? = null,
    @ColumnInfo(defaultValue = "0") val syncState: Int = SYNC_WAITING,
) {
    companion object {
        /** Not uploaded: waiting for a server, or local only (see RecordingEntity.syncAllowed). */
        const val SYNC_WAITING = 0
        const val SYNC_UPLOADED = 1
        /** The server refused it for good (a 4xx about the payload); kept, not retried. */
        const val SYNC_REJECTED = 2
    }
}

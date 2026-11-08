package com.whyfi.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One recording on the phone: a continuous scan from Start to Stop, a single
 * scan, or a LAN sweep / range taken outside a scan. Its passes are the
 * [PendingScanEntity] rows with this [id].
 *
 * [syncAllowed] decides whether its passes go to a server. Recordings made
 * while a server is set up get true; standalone ones stay false until the
 * user agrees to upload them after connecting (ask on connect).
 */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val syncAllowed: Boolean,
    val publicIp: String? = null,
) {
    companion object {
        const val KIND_SCAN = "scan"
        const val KIND_LAN = "lan"
        const val KIND_RANGE = "range"
    }
}

/** A recording with its pass count, size and upload progress, for lists and the storage limit. */
data class RecordingSummary(
    val id: String,
    val kind: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val syncAllowed: Boolean,
    val publicIp: String?,
    val passes: Int,
    val bytes: Long,
    val uploaded: Int,
    val rejected: Int,
) {
    val waiting get() = passes - uploaded - rejected
    val isOpen get() = endedAtMs == null
}

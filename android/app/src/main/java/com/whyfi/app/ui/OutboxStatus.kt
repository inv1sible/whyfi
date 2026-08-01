package com.whyfi.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.whyfi.app.data.local.WhyfiDatabase
import java.util.Locale

/** How much is queued for upload right now. Null until the first read lands. */
data class OutboxStatus(val bytes: Long, val count: Int)

/**
 * Reads the outbox size once per change of [refreshKey].
 *
 * Deliberately not self-polling: the outbox only changes when a scan
 * completes or an upload drains, and both screens that show it already have
 * a natural key to hang it on (the storage quota on Settings, the completed
 * pass count on the Dashboard). A timer here would wake the DB for nothing
 * on an idle phone.
 */
@Composable
fun rememberOutboxStatus(refreshKey: Any?): OutboxStatus? {
    val context = LocalContext.current
    val status by produceState<OutboxStatus?>(initialValue = null, refreshKey) {
        val dao = WhyfiDatabase.getInstance(context).pendingScanDao()
        value = OutboxStatus(bytes = dao.totalBytes() ?: 0L, count = dao.count())
    }
    return status
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

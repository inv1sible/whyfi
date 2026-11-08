package com.whyfi.app.scan

import com.whyfi.app.data.local.RecordingEntity
import com.whyfi.app.data.local.RecordingSummary
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Labels for the Recordings list. Pure, JVM-tested (RecordingFormatTest). */
object RecordingFormat {
    fun kind(kind: String) = when (kind) {
        RecordingEntity.KIND_SCAN -> "Scan"
        RecordingEntity.KIND_LAN -> "LAN sweep"
        RecordingEntity.KIND_RANGE -> "Range"
        else -> kind
    }

    /** "12 min", "1 h 05 min", "under 1 min", or "recording…" while open. */
    fun duration(startMs: Long, endMs: Long?): String {
        endMs ?: return "recording…"
        val minutes = ((endMs - startMs).coerceAtLeast(0) / 60_000).toInt()
        return when {
            minutes < 1 -> "under 1 min"
            minutes < 60 -> "$minutes min"
            else -> String.format(Locale.ROOT, "%d h %02d min", minutes / 60, minutes % 60)
        }
    }

    fun bytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Where the recording stands with a server. */
    fun sync(r: RecordingSummary): String = when {
        !r.syncAllowed -> "on this phone only"
        r.waiting > 0 -> "${r.waiting} of ${r.passes} waiting to upload"
        r.rejected > 0 -> "uploaded · ${r.rejected} refused by the server"
        r.passes == 0 -> "empty"
        else -> "uploaded"
    }

    fun summary(r: RecordingSummary): String =
        "${kind(r.kind)} · ${r.passes} ${if (r.passes == 1) "pass" else "passes"} · ${bytes(r.bytes)} · ${sync(r)}"

    /** ISO-8601 UTC as the passes store it ("2026-11-08T13:02:11Z", optional millis), or null. */
    fun epochMs(iso: String): Long? {
        val formats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")
        return formats.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)?.time
            }.getOrNull()
        }
    }
}

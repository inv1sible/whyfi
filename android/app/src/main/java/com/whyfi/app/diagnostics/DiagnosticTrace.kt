package com.whyfi.app.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Append-only step log ("breadcrumbs") for risky work whose failure mode can
 * be a native crash — the USB monitor-mode driver above all. A SIGSEGV in
 * native code kills the process without ever reaching WhyfiApplication's
 * uncaught-exception handler, so the only way to learn *which step* died is
 * to have written each step to disk before attempting it. Crash entries
 * attach the tail of this file (see CrashLogReader.appendEntry callers).
 *
 * Like CrashLogReader, the file handling takes a plain java.io.File so it is
 * unit-testable on the JVM; the Context overloads only resolve the path.
 */
object DiagnosticTrace {
    const val TRACE_FILENAME = "diagnostic_trace.txt"

    /** Keeps the file from growing without bound across many test runs. */
    private const val MAX_BYTES = 64 * 1024

    fun traceFile(context: Context): File = File(context.filesDir, TRACE_FILENAME)

    /** Appends one timestamped line. Never throws: a diagnostic aid must not
     * become a new way to crash. */
    fun log(file: File, message: String, now: Date = Date()) {
        runCatching {
            if (file.exists() && file.length() > MAX_BYTES) {
                // Keep the newer half so the steps right before a crash survive.
                val kept = file.readText().takeLast(MAX_BYTES / 2)
                file.writeText(kept.substringAfter('\n'))
            }
            file.appendText("${isoUtc(now)} $message\n")
        }
    }

    fun log(context: Context, message: String) = log(traceFile(context), message)

    /** Last [maxLines] lines, or null when there is no trace. */
    fun tail(file: File, maxLines: Int = 40): String? {
        if (!file.exists()) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.isEmpty()) return null
        return lines.takeLast(maxLines).joinToString("\n")
    }

    fun tail(context: Context, maxLines: Int = 40): String? = tail(traceFile(context), maxLines)

    /** The tail only if the trace was written within [windowMs] before
     * [atMs] — steps from an unrelated earlier session would mislead. */
    fun tailIfRecent(file: File, atMs: Long, windowMs: Long = RECENT_WINDOW_MS, maxLines: Int = 40): String? {
        if (!file.exists()) return null
        val age = atMs - file.lastModified()
        return if (age in -windowMs..windowMs) tail(file, maxLines) else null
    }

    fun tailIfRecent(context: Context, atMs: Long): String? = tailIfRecent(traceFile(context), atMs)

    const val RECENT_WINDOW_MS = 10 * 60 * 1000L

    fun clear(file: File): Boolean = !file.exists() || file.delete()

    fun clear(context: Context): Boolean = clear(traceFile(context))

    /** UTC + 'Z' — same convention as WhyfiApplication.writeCrashLog and
     * ScanCoordinator.isoNow(); the backend's TIME_ZONE is UTC. */
    fun isoUtc(date: Date): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(date)
    }
}

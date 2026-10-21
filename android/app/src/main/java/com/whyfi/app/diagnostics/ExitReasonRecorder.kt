package com.whyfi.app.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Date

/**
 * Native crashes and ANRs never reach WhyfiApplication's uncaught-exception
 * handler — the process is killed by a signal, or by the system, before any
 * Kotlin code runs. Android 11+ keeps a history of why each process died, so
 * on the next start we copy any such exit we haven't seen yet into the same
 * crash log the Settings screen already shows and sends. Java crashes
 * (REASON_CRASH) are skipped: our own handler recorded those already.
 *
 * Below API 30 there is no exit history and this is a no-op.
 */
object ExitReasonRecorder {
    private const val PREFS = "diagnostics"
    private const val KEY_LAST_EXIT_MS = "last_recorded_exit_ms"

    fun recordMissedExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { record(context) }
            .onFailure { DiagnosticTrace.log(context, "ExitReasonRecorder failed: $it") }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun record(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_MS, 0L)
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = activityManager.getHistoricalProcessExitReasons(null, 0, 10)
            .filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
        if (exits.isEmpty()) return

        val crashLog = CrashLogReader.crashLogFile(context)
        for (exit in exits) {
            val kind = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                ApplicationExitInfo.REASON_ANR -> "App not responding (ANR)"
                else -> null
            } ?: continue
            val body = ExitReportFormatter.format(
                kind = kind,
                description = exit.description,
                processName = exit.processName,
                trace = readTrace(exit),
                breadcrumbs = DiagnosticTrace.tailIfRecent(context, exit.timestamp),
            )
            CrashLogReader.appendEntry(crashLog, DiagnosticTrace.isoUtc(Date(exit.timestamp)), body)
        }
        prefs.edit().putLong(KEY_LAST_EXIT_MS, exits.last().timestamp).apply()
    }

    /** ANR traces are text; native traces are a binary tombstone (API 31+),
     * reduced to its readable strings. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(exit: ApplicationExitInfo): String? = runCatching {
        exit.traceInputStream?.use { stream ->
            val bytes = stream.readBytes()
            if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                String(bytes, Charsets.UTF_8).take(ExitReportFormatter.MAX_TRACE_CHARS)
            } else {
                ExitReportFormatter.printableStrings(bytes)
            }
        }
    }.getOrNull()
}

package com.whyfi.app.diagnostics

import android.content.Context
import java.io.File

/**
 * Reads/clears/parses the crash log written by WhyfiApplication's uncaught
 * exception handler. Split out from WhyfiApplication so the actual file
 * handling — given a plain java.io.File — has no Android Context/Application
 * dependency and is unit-testable in the plain JVM test source set, the same
 * way RadioFormat/ScanDiff are.
 */
object CrashLogReader {
    const val CRASH_LOG_FILENAME = "last_crash.txt"

    /** Single source of truth for the path, shared by the writer
     * (WhyfiApplication) and every reader, so the two can't drift onto
     * different filenames. */
    fun crashLogFile(context: Context): File = File(context.filesDir, CRASH_LOG_FILENAME)

    /** Null if no crash has ever been recorded, or if the file exists but
     * couldn't be read (corrupted/partial/out of space) — either way,
     * "nothing to show" is the correct and safe fallback; this must never
     * throw into the caller, since the caller is a screen whose whole job
     * is telling the user why the app crashed. */
    fun readCrashLog(file: File): String? =
        if (!file.exists()) null else runCatching { file.readText() }.getOrNull()

    fun readCrashLog(context: Context): String? = readCrashLog(crashLogFile(context))

    /** True if there's nothing left on disk afterward (already-absent
     * counts as success); false only on a genuine delete failure. */
    fun clearCrashLog(file: File): Boolean = !file.exists() || file.delete()

    fun clearCrashLog(context: Context): Boolean = clearCrashLog(crashLogFile(context))

    data class ParsedCrashLog(val occurredAt: String, val stackTrace: String)

    /** Splits the raw "timestamp\nstack trace" text WhyfiApplication writes
     * back into its two parts, so the Settings screen can send them to the
     * backend as separate fields without re-deriving this format elsewhere.
     * Null for anything that doesn't look like a crash log this app wrote
     * (e.g. an empty or single-line file). */
    fun parse(raw: String): ParsedCrashLog? {
        val newline = raw.indexOf('\n')
        if (newline < 0) return null
        return ParsedCrashLog(occurredAt = raw.substring(0, newline), stackTrace = raw.substring(newline + 1))
    }
}

package com.whyfi.app

import android.app.Application
import android.util.Log
import com.whyfi.app.diagnostics.CrashLogReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class WhyfiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    /** Getting today's crash log out of this app required walking a user
     * through adb + Logcat from scratch — there was nothing else to point
     * them at. This doesn't change what happens on a crash (the previous
     * handler still runs afterward, so the OS's normal "app keeps stopping"
     * behavior is unaffected); it just leaves a plain-text trace in the
     * app's own files so a future crash can be read (and sent to the
     * backend — see ui/SettingsScreen.kt's Diagnostics section) without
     * needing a live debugging session at the moment it happens. */
    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(throwable) }
            Log.e("WhyfiApplication", "Uncaught exception on ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(throwable: Throwable) {
        // UTC + 'Z', matching ScanCoordinator.isoNow() — the backend's
        // TIME_ZONE is UTC, and a naive local-time string here would get
        // silently treated as UTC on ingest, which is wrong for any device
        // not in that zone. This is the same class of bug as the timestamps
        // already sent for scan sessions, fixed the same way.
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = format.format(Date())
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        CrashLogReader.crashLogFile(this).writeText("$timestamp\n$stackTrace")
    }
}

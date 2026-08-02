package com.whyfi.app

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
     * app's own files so a future crash can be read directly off the
     * device (e.g. via a file manager, or `adb pull`) without needing a
     * live debugging session at the moment it happens. */
    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(throwable) }
            Log.e("WhyfiApplication", "Uncaught exception on ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        File(filesDir, "last_crash.txt").writeText("$timestamp\n$stackTrace")
    }
}

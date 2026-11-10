package com.whyfi.app.diagnostics

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager

/**
 * One line on how the phone is coping, written once a minute during a USB
 * capture (mission log "health" event and the diagnostic trace), so a capture
 * that stalls or stops on a long drive shows whether memory ran out or
 * Android throttled a hot phone (user, 2026-11-10: capture stopped after
 * 5–6 min while driving, the phone hinted it was throttled).
 */
object DeviceHealth {
    fun line(context: Context): String {
        val runtime = Runtime.getRuntime()
        val heapMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val heapMaxMb = runtime.maxMemory() / MB
        val nativeMb = Debug.getNativeHeapAllocatedSize() / MB
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            runCatching { thermalLabel(context.getSystemService(PowerManager::class.java)!!.currentThermalStatus) }.getOrDefault("?")
        else "?"
        val battery = runCatching {
            val manager = context.getSystemService(BatteryManager::class.java)!!
            "${manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" + if (manager.isCharging) " charging" else ""
        }.getOrDefault("?")
        return "heap=${heapMb}/${heapMaxMb}MB native=${nativeMb}MB thermal=$thermal battery=$battery"
    }

    /** PowerManager.THERMAL_STATUS_* as words; "moderate" and up means
     * Android is slowing the phone down. */
    fun thermalLabel(status: Int): String = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "status$status"
    }

    private const val MB = 1024L * 1024L
}

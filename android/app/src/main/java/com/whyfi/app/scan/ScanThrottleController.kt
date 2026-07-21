package com.whyfi.app.scan

/**
 * Android throttles foreground WiFi scan requests to roughly 4 per 2
 * minutes since Android 9 (API 28) — `WifiManager.startScan()` silently
 * returns false when throttled rather than throwing. This tracks a local
 * sliding window so the UI can show a countdown instead of a scan that
 * silently does nothing.
 */
class ScanThrottleController(
    private val maxScansPerWindow: Int = 4,
    private val windowMs: Long = 2 * 60 * 1000,
) {
    private val scanTimestamps = ArrayDeque<Long>()

    @Synchronized
    fun canScanNow(): Boolean {
        prune()
        return scanTimestamps.size < maxScansPerWindow
    }

    @Synchronized
    fun recordScanAttempt() {
        prune()
        scanTimestamps.addLast(System.currentTimeMillis())
    }

    /** Epoch millis at which the next scan will be allowed; 0 if allowed now. */
    @Synchronized
    fun nextAllowedScanAtMs(): Long {
        prune()
        return if (scanTimestamps.size < maxScansPerWindow) 0L else scanTimestamps.first() + windowMs
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - windowMs
        while (scanTimestamps.isNotEmpty() && scanTimestamps.first() < cutoff) {
            scanTimestamps.removeFirst()
        }
    }
}

package com.whyfi.app.scan

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Live location while scanning, so each pass gets a fresh fix instead of
 * whatever getLastKnownLocation() holds (which is what made scan positions
 * jump: see docs/superpowers/plans/2026-11-07-live-scan-location.md).
 *
 * Same subscription as Fieldwatch's startLocationUpdates: GPS every second
 * with no distance floor (sparser requests left straight chords between
 * fixes), network every 4 s, plus the platform FUSED_PROVIDER on API 31+
 * (no Play Services, see MEMORY.md). Every fix goes through [selector].
 *
 * Reference-counted: a continuous scan and a one-shot action (LAN sweep,
 * FTM range) can overlap, and the one finishing first must not switch the
 * GPS off under the other. Runs inside ScanForegroundService, which is a
 * `location` foreground service.
 */
class LiveLocationTracker(private val context: Context) {
    val selector = FixSelector()

    @Volatile
    private var latestFused: Location? = null
    private var users = 0
    private var listener: LocationListener? = null

    @Synchronized
    fun acquire() {
        users++
        // Every acquire retries a start that couldn't happen yet (location
        // permission granted mid-session), not just the first.
        if (listener == null) start()
    }

    @Synchronized
    fun release() {
        if (users == 0) return
        users--
        if (users == 0) stop()
    }

    @Synchronized
    fun isRunning(): Boolean = listener != null

    /** True during the first minute after updates started: the only time
     * it's worth making a pass wait for a first fix. Indoors with no fix at
     * all, later passes shouldn't each pay for the wait. */
    @Synchronized
    fun warmingUp(nowBootMs: Long): Boolean = listener != null && nowBootMs - startedAtBootMs < WARM_UP_MS

    private var startedAtBootMs = 0L

    /** The freshest platform fused fix, kept for the backend's fused comparison. */
    fun latestFused(): Location? = latestFused

    @SuppressLint("MissingPermission")
    private fun start() {
        if (listener != null || !LocationSnapshot.hasLocationPermission(context)) return
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // A just-cached fix (another app, or the last pass) counts if it
        // passes the same gates; the selector drops anything stale.
        LocationSnapshot.lastKnownCandidates(context).sortedBy { it.elapsedRealtimeNanos }.forEach { offer(it) }
        val newListener = LocationListener { location -> offer(location) }
        val requests = buildList {
            add(LocationManager.GPS_PROVIDER to 1_000L)
            add(LocationManager.NETWORK_PROVIDER to 4_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER to 2_000L)
        }
        val registered = requests.count { (provider, intervalMs) ->
            runCatching {
                locationManager.requestLocationUpdates(provider, intervalMs, 0f, newListener, Looper.getMainLooper())
            }.onFailure { Log.w(TAG, "No live updates from $provider", it) }.isSuccess
        }
        if (registered > 0) {
            listener = newListener
            startedAtBootMs = SystemClock.elapsedRealtime()
        }
    }

    private fun stop() {
        val current = listener ?: return
        runCatching {
            (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(current)
        }
        listener = null
    }

    private fun offer(location: Location) {
        if (location.provider == FUSED) latestFused = location
        selector.offer(location.toFix(), SystemClock.elapsedRealtime())
    }

    companion object {
        private const val TAG = "LiveLocationTracker"
        private const val FUSED = "fused"
        private const val WARM_UP_MS = 60_000L
    }
}

fun Location.toFix() = LocationFix(
    provider = provider ?: "unknown",
    bootMs = elapsedRealtimeNanos / 1_000_000L,
    lat = latitude,
    lng = longitude,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    speedMps = if (hasSpeed() && speed.isFinite() && speed >= 0f) speed.toDouble() else null,
)

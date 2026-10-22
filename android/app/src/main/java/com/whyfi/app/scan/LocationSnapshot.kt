package com.whyfi.app.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/** One-shot "roughly where am I right now" reads, shared by [ScanCoordinator]
 * (which additionally supports a fused-vs-GPS comparison for scan uploads —
 * see [com.whyfi.app.data.LocationSourcePreference]) and the Mission view,
 * which only ever needs the plain GPS/network pick — plus, for Mission
 * view's live "which way to walk" tracking, a continuous-updates variant. */
object LocationSnapshot {

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** All available platform fixes for Monitor's accuracy/age selection. */
    @SuppressLint("MissingPermission")
    fun lastKnownCandidates(context: Context): List<Location> {
        if (!hasLocationPermission(context)) return emptyList()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return providers(includeFused = true).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }
    }

    private fun providers(includeFused: Boolean): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) +
            if (includeFused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                listOf(LocationManager.FUSED_PROVIDER) else emptyList()

    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Registers for live position updates on every enabled GPS/network
     * provider, delivering each fix to [onLocation]. Returns null (no
     * subscription made) without the location permission or with every
     * provider disabled. Callers must [AutoCloseable.close] the result to
     * stop listening — see MissionController's tracking lifecycle. */
    @SuppressLint("MissingPermission")
    fun requestUpdates(
        context: Context,
        minTimeMs: Long = 3000L,
        minDistanceM: Float = 3f,
        includeFused: Boolean = false,
        onLocation: (Location) -> Unit,
    ): AutoCloseable? {
        if (!hasLocationPermission(context)) {
            return null
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = providers(includeFused)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        val listener = LocationListener { location -> onLocation(location) }
        providers.forEach { provider ->
            runCatching { locationManager.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener) }
        }
        return AutoCloseable { locationManager.removeUpdates(listener) }
    }
}

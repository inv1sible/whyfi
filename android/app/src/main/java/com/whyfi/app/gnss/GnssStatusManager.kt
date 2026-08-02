package com.whyfi.app.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.whyfi.app.data.remote.SatelliteObservationDto
import kotlinx.coroutines.delay

class GnssStatusManager(private val context: Context) {

    /** Non-null only when GNSS scanning can't proceed — mirrors WifiScanManager/
     * CellularManager/BleDeviceScanner's unavailableReason(). This device class
     * of bug (an unguarded hardware/feature check) is exactly what took the
     * app down on a WiFi-only tablet's cellular path; GNSS was the one radio
     * with no equivalent check or explanation at all, so a device with no GPS
     * chip (or location services off) silently came back with zero satellites
     * and no indication why. */
    fun unavailableReason(): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) {
            return "This device has no GPS hardware."
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
        if (!gpsEnabled) return "Location services (GPS) are off."
        return null
    }

    @SuppressLint("MissingPermission")
    suspend fun captureSnapshot(durationMs: Long = 5000): List<SatelliteObservationDto> {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var latest: List<SatelliteObservationDto> = emptyList()

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                latest = (0 until status.satelliteCount).map { i ->
                    SatelliteObservationDto(
                        constellation = constellationName(status.getConstellationType(i)),
                        svid = status.getSvid(i),
                        cn0DbHz = status.getCn0DbHz(i).toDouble(),
                        elevationDegrees = status.getElevationDegrees(i).toDouble(),
                        azimuthDegrees = status.getAzimuthDegrees(i).toDouble(),
                        usedInFix = status.usedInFix(i),
                        carrierFrequencyHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i).toDouble() else null,
                        hasEphemerisData = status.hasEphemerisData(i),
                        hasAlmanacData = status.hasAlmanacData(i),
                    )
                }
            }
        }

        val handler = Handler(Looper.getMainLooper())
        locationManager.registerGnssStatusCallback(callback, handler)
        delay(durationMs)
        locationManager.unregisterGnssStatusCallback(callback)

        return latest
    }

    private fun constellationName(constellationType: Int): String = when (constellationType) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_BEIDOU -> "BEIDOU"
        GnssStatus.CONSTELLATION_GALILEO -> "GALILEO"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> "GPS"
    }
}

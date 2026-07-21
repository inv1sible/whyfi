package com.whyfi.app.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.whyfi.app.data.remote.SatelliteObservationDto
import kotlinx.coroutines.delay

class GnssStatusManager(private val context: Context) {

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

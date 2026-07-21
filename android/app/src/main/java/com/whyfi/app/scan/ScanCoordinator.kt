package com.whyfi.app.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.whyfi.app.ble.BleDeviceScanner
import com.whyfi.app.cellular.CellularManager
import com.whyfi.app.data.local.PendingScanEntity
import com.whyfi.app.data.local.WhyfiDatabase
import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.UploadWorker
import com.whyfi.app.gnss.GnssStatusManager
import com.whyfi.app.lan.LanScanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class ScanPhase { WIFI, CELLULAR, BLE, GNSS, UPLOADING, DONE }

data class ScanOptions(
    val includeWifi: Boolean = true,
    val includeCellular: Boolean = true,
    val includeBle: Boolean = true,
    val includeGnss: Boolean = true,
)

/** Ties WiFi/cellular/BLE/GNSS together into one "Scan Now" pass, matching
 * the backend's single-endpoint, multi-radio ingest contract (see
 * backend/scans/serializers.py's ScanSessionIngestSerializer). */
class ScanCoordinator(private val context: Context) {

    val wifiScanManager = WifiScanManager(context)
    val cellularManager = CellularManager(context)
    val bleDeviceScanner = BleDeviceScanner(context)
    private val gnssStatusManager = GnssStatusManager(context)
    private val lanScanner = LanScanner(context)
    private val gson = Gson()

    suspend fun runScan(
        options: ScanOptions = ScanOptions(),
        onPhaseChange: (ScanPhase) -> Unit = {},
        // Fired right after each radio's results are in, before moving to
        // the next one — lets the UI fill counts in as the scan progresses
        // instead of only showing anything once the whole pass is done.
        onPartialResult: (ScanPhase, Int) -> Unit = { _, _ -> },
    ): ScanSessionUploadRequest {
        val startedAt = isoNow()
        val location = lastKnownLocation()

        val wifiObservations = if (options.includeWifi) {
            onPhaseChange(ScanPhase.WIFI)
            wifiScanManager.scan().also { onPartialResult(ScanPhase.WIFI, it.size) }
        } else {
            emptyList()
        }

        val cellObservations = if (options.includeCellular) {
            onPhaseChange(ScanPhase.CELLULAR)
            cellularManager.readCellObservations().also { onPartialResult(ScanPhase.CELLULAR, it.size) }
        } else {
            emptyList()
        }

        val bleObservations = if (options.includeBle) {
            onPhaseChange(ScanPhase.BLE)
            bleDeviceScanner.scan().also { onPartialResult(ScanPhase.BLE, it.size) }
        } else {
            emptyList()
        }

        val satelliteObservations = if (options.includeGnss) {
            onPhaseChange(ScanPhase.GNSS)
            gnssStatusManager.captureSnapshot().also { onPartialResult(ScanPhase.GNSS, it.size) }
        } else {
            emptyList()
        }

        onPhaseChange(ScanPhase.UPLOADING)
        val payload = ScanSessionUploadRequest(
            clientScanId = UUID.randomUUID().toString(),
            startedAt = startedAt,
            completedAt = isoNow(),
            latitude = location?.latitude,
            longitude = location?.longitude,
            locationAccuracyMeters = location?.accuracy,
            locationProvider = location?.provider ?: "",
            wifiObservations = wifiObservations,
            cellObservations = cellObservations,
            bleObservations = bleObservations,
            satelliteObservations = satelliteObservations,
        )

        enqueueForUpload(payload)
        onPhaseChange(ScanPhase.DONE)
        return payload
    }

    /** A subnet sweep + port scan takes much longer than the batch radio
     * pass above, so it's its own explicit action with its own lightweight
     * session — same pattern the removed NFC tap flow used. */
    suspend fun runLanScan(
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
        onDeviceFound: (LanObservationDto) -> Unit = {},
    ): ScanSessionUploadRequest {
        val startedAt = isoNow()
        val location = lastKnownLocation()
        val devices = lanScanner.scan(onProgress, onDeviceFound)

        val payload = ScanSessionUploadRequest(
            clientScanId = UUID.randomUUID().toString(),
            startedAt = startedAt,
            completedAt = isoNow(),
            latitude = location?.latitude,
            longitude = location?.longitude,
            locationAccuracyMeters = location?.accuracy,
            locationProvider = location?.provider ?: "",
            lanObservations = devices,
        )

        enqueueForUpload(payload)
        return payload
    }

    private suspend fun enqueueForUpload(payload: ScanSessionUploadRequest) {
        val dao = WhyfiDatabase.getInstance(context).pendingScanDao()
        dao.insert(
            PendingScanEntity(
                clientScanId = payload.clientScanId,
                payloadJson = gson.toJson(payload),
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
        UploadWorker.enqueue(context)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
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

    private fun isoNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}

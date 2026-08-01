package com.whyfi.app.scan

import com.whyfi.app.data.remote.ScanSessionUploadRequest

/**
 * What the Dashboard shows: an immutable snapshot of everything this phone
 * has heard since the scanner started.
 *
 * Immutable on purpose. [SurveyTally] holds mutable maps, and putting one of
 * those inside a `StateFlow` value would leave Compose comparing a reference
 * to itself and never recomposing — the counts would silently stop moving.
 */
data class SurveyStats(
    val passCount: Int = 0,
    val startedAtMs: Long = 0L,
    val uniqueWifi: Int = 0,
    val uniqueCellular: Int = 0,
    val uniqueBle: Int = 0,
    val uniqueSatellites: Int = 0,
    /** Band name -> unique AP count. Keys are [RadioFormat]'s band constants. */
    val wifiByBand: Map<String, Int> = emptyMap(),
    val strongestWifi: StrongestSighting? = null,
    val strongestCellular: StrongestSighting? = null,
    val strongestBle: StrongestSighting? = null,
) {
    val hasData: Boolean get() = passCount > 0
}

data class StrongestSighting(val label: String, val detail: String, val signal: Int)

/**
 * Running totals across every pass in this scanning session.
 *
 * Stores one compact record per *unique device*, not one record per pass —
 * so an hour of continuous scanning costs a few hundred bytes per distinct
 * device rather than a full payload every 30 seconds. That matters: the
 * phone is otherwise a write-through pipe (see [ScanCoordinator]) and this
 * is the only place that accumulates.
 *
 * Lives in [ScanForegroundService] and therefore dies with it, which is by
 * design rather than an oversight — the full survey lives on the backend.
 * The Dashboard says so rather than letting the number look like an all-time
 * figure. Don't "fix" this with a Room table without reading AGENT.md first.
 */
class SurveyTally {

    private data class WifiSeen(val ssid: String, val band: String, val bestRssi: Int)
    private data class SignalSeen(val label: String, val detail: String, val best: Int)

    private val wifi = HashMap<String, WifiSeen>()
    private val cellular = HashMap<String, SignalSeen>()
    private val ble = HashMap<String, SignalSeen>()
    private val satellites = HashSet<String>()

    private var passCount = 0
    private var startedAtMs = 0L

    fun record(pass: ScanSessionUploadRequest) {
        if (passCount == 0) startedAtMs = System.currentTimeMillis()
        passCount++

        for (ap in pass.wifiObservations) {
            val existing = wifi[ap.bssid]
            // Keep the strongest reading ever taken of this AP, not the latest
            // — walking past a router and back shouldn't downgrade it.
            if (existing == null || ap.rssi > existing.bestRssi) {
                wifi[ap.bssid] = WifiSeen(
                    ssid = RadioFormat.ssidLabel(ap.ssid),
                    band = RadioFormat.bandFor(ap.frequencyMhz),
                    bestRssi = ap.rssi,
                )
            }
        }

        for (cell in pass.cellObservations) {
            val key = ScanDiff.cellKey(cell.mcc, cell.mnc, cell.tacOrLac, cell.cellId)
            val signal = cell.signalDbm ?: continue
            val existing = cellular[key]
            if (existing == null || signal > existing.best) {
                cellular[key] = SignalSeen(
                    label = cell.carrierName.ifBlank { "${cell.mcc}-${cell.mnc}" },
                    detail = "${cell.radioType} · ${cell.cellId}",
                    best = signal,
                )
            }
        }

        for (device in pass.bleObservations) {
            val existing = ble[device.bleMac]
            if (existing == null || device.rssi > existing.best) {
                ble[device.bleMac] = SignalSeen(
                    label = device.deviceName.ifBlank { "(unnamed)" },
                    detail = device.deviceTypeGuess,
                    best = device.rssi,
                )
            }
        }

        for (sat in pass.satelliteObservations) {
            satellites += "${sat.constellation}-${sat.svid}"
        }
    }

    fun reset() {
        wifi.clear()
        cellular.clear()
        ble.clear()
        satellites.clear()
        passCount = 0
        startedAtMs = 0L
    }

    fun snapshot(): SurveyStats = SurveyStats(
        passCount = passCount,
        startedAtMs = startedAtMs,
        uniqueWifi = wifi.size,
        uniqueCellular = cellular.size,
        uniqueBle = ble.size,
        uniqueSatellites = satellites.size,
        wifiByBand = wifi.values.groupingBy { it.band }.eachCount(),
        strongestWifi = wifi.values.maxByOrNull { it.bestRssi }
            ?.let { StrongestSighting(it.ssid, it.band, it.bestRssi) },
        strongestCellular = cellular.values.maxByOrNull { it.best }
            ?.let { StrongestSighting(it.label, it.detail, it.best) },
        strongestBle = ble.values.maxByOrNull { it.best }
            ?.let { StrongestSighting(it.label, it.detail, it.best) },
    )
}

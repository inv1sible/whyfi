package com.whyfi.app.scan

import com.whyfi.app.data.remote.CellObservationDto
import com.whyfi.app.data.remote.SatelliteObservationDto
import com.whyfi.app.data.remote.ScanSessionUploadRequest

/** Which radio a table is showing. Separate from [ScanPhase], which is about
 * what the scanner is *doing* — these outlive a pass and are also the saved
 * navigation route (see MainActivity). */
enum class RadioKind(val label: String, val icon: String) {
    WIFI("WiFi", "📶"),
    CELLULAR("Cellular", "📡"),
    BLE("BLE", "🔵"),
    SATELLITE("Satellites", "🛰️"),
}

/**
 * How a device compares to the previous pass.
 *
 * [UNKNOWN] is not "we didn't check" laziness — it's the honest answer when
 * there is nothing to compare against. See [canCompare].
 */
enum class DeviceChange { NEW, PRESENT, GONE, UNKNOWN }

/** One row of a scan-detail table, already formatted for display. */
data class DeviceRow(
    val key: String,
    val title: String,
    val subtitle: String,
    /** Signal, pre-formatted with its unit — the unit differs per radio. */
    val metric: String,
    val columns: List<String>,
    val change: DeviceChange,
    /** Signal movement since the previous pass, in the metric's own unit.
     * Null when the device is new, gone, or had no reading to compare. */
    val signalDelta: Int? = null,
)

data class DiffSummary(
    val total: Int,
    val new: Int,
    val gone: Int,
    val comparable: Boolean,
)

object ScanDiff {

    val WIFI_COLUMNS = listOf("SSID", "BSSID", "Signal", "Channel", "Security")
    val CELLULAR_COLUMNS = listOf("Carrier", "Cell ID", "Type", "Signal", "TAC/LAC", "Serving")
    val BLE_COLUMNS = listOf("Name", "MAC", "Signal", "Type")
    val SATELLITE_COLUMNS = listOf("Constellation", "SVID", "C/N0", "Elev", "Azim", "In fix")

    fun columnsFor(kind: RadioKind): List<String> = when (kind) {
        RadioKind.WIFI -> WIFI_COLUMNS
        RadioKind.CELLULAR -> CELLULAR_COLUMNS
        RadioKind.BLE -> BLE_COLUMNS
        RadioKind.SATELLITE -> SATELLITE_COLUMNS
    }

    /**
     * Whether a NEW/GONE comparison can be made at all.
     *
     * An empty observation list is ambiguous: the radio may have been
     * unchecked in [ScanOptions], switched off at the OS level, or simply
     * heard nothing. The payload doesn't record which, so treating "previous
     * pass had none" as "everything is new" would confidently invent a
     * finding out of a radio the user had turned off. When we can't tell,
     * every row gets [DeviceChange.UNKNOWN] and the UI draws no badges.
     */
    private fun canCompare(previous: List<*>?): Boolean = !previous.isNullOrEmpty()

    fun rowsFor(
        kind: RadioKind,
        latest: ScanSessionUploadRequest?,
        previous: ScanSessionUploadRequest?,
    ): List<DeviceRow> = when (kind) {
        RadioKind.WIFI -> diffWifi(latest, previous)
        RadioKind.CELLULAR -> diffCellular(latest, previous)
        RadioKind.BLE -> diffBle(latest, previous)
        RadioKind.SATELLITE -> diffSatellite(latest, previous)
    }

    fun summarize(rows: List<DeviceRow>): DiffSummary {
        val comparable = rows.any { it.change != DeviceChange.UNKNOWN }
        return DiffSummary(
            total = rows.count { it.change != DeviceChange.GONE },
            new = rows.count { it.change == DeviceChange.NEW },
            gone = rows.count { it.change == DeviceChange.GONE },
            comparable = comparable,
        )
    }

    // --- WiFi -------------------------------------------------------------
    // Keyed on BSSID, matching the backend's AccessPoint primary key.

    private fun diffWifi(
        latest: ScanSessionUploadRequest?,
        previous: ScanSessionUploadRequest?,
    ): List<DeviceRow> {
        val current = latest?.wifiObservations.orEmpty()
        val before = previous?.wifiObservations.orEmpty()
        val compare = canCompare(before)
        val beforeByKey = before.associateBy { it.bssid }

        val rows = current.sortedByDescending { it.rssi }.map { ap ->
            val prior = beforeByKey[ap.bssid]
            DeviceRow(
                key = ap.bssid,
                title = RadioFormat.ssidLabel(ap.ssid),
                subtitle = ap.bssid,
                metric = "${ap.rssi} dBm",
                columns = listOf(
                    RadioFormat.ssidLabel(ap.ssid),
                    ap.bssid,
                    "${ap.rssi} dBm",
                    RadioFormat.channelLabel(ap.frequencyMhz),
                    RadioFormat.securityLabel(ap.capabilities),
                ),
                change = changeFor(compare, prior != null),
                signalDelta = if (prior != null) ap.rssi - prior.rssi else null,
            )
        }

        val gone = if (!compare) emptyList() else before
            .filter { old -> current.none { it.bssid == old.bssid } }
            .map { old ->
                DeviceRow(
                    key = old.bssid,
                    title = RadioFormat.ssidLabel(old.ssid),
                    subtitle = old.bssid,
                    metric = "${old.rssi} dBm",
                    columns = listOf(
                        RadioFormat.ssidLabel(old.ssid),
                        old.bssid,
                        "${old.rssi} dBm",
                        RadioFormat.channelLabel(old.frequencyMhz),
                        RadioFormat.securityLabel(old.capabilities),
                    ),
                    change = DeviceChange.GONE,
                )
            }

        return rows + gone
    }

    // --- Cellular ---------------------------------------------------------
    // Keyed exactly like the backend's CellTower.tower_key
    // (serializers.py: f"{mcc}-{mnc}-{tac_or_lac}-{cell_id}") so a row here
    // and a tower on the PWA mean the same physical thing.

    fun cellKey(mcc: String, mnc: String, tacOrLac: String, cellId: String): String =
        "$mcc-$mnc-$tacOrLac-$cellId"

    /**
     * Whether a cell can be told apart from its neighbours at all.
     *
     * `CellularManager.validOrBlank` maps the platform's "unavailable"
     * sentinel to `""`, and neighbour cells very often report no cell ID —
     * so several genuinely different cells arrive sharing one [cellKey].
     * Matching those across passes would pair up unrelated cells, and
     * showing them under one key would collide in the results table.
     */
    private fun isIdentifiable(cell: CellObservationDto): Boolean = cell.cellId.isNotBlank()

    private fun diffCellular(
        latest: ScanSessionUploadRequest?,
        previous: ScanSessionUploadRequest?,
    ): List<DeviceRow> {
        val current = latest?.cellObservations.orEmpty()
        val before = previous?.cellObservations.orEmpty()
        val compare = canCompare(before)
        val keyOf = { c: CellObservationDto ->
            cellKey(c.mcc, c.mnc, c.tacOrLac, c.cellId)
        }
        // Only identifiable cells take part in matching.
        val beforeByKey = before.filter(::isIdentifiable).associateBy(keyOf)

        // Serving cell first — it's the one actually carrying traffic, and
        // burying it among neighbours by signal order hides the useful row.
        val rows = current
            .sortedWith(compareByDescending<CellObservationDto> { it.isServingCell }
                .thenByDescending { it.signalDbm ?: Int.MIN_VALUE })
            .mapIndexed { index, cell ->
                val identifiable = isIdentifiable(cell)
                val prior = if (identifiable) beforeByKey[keyOf(cell)] else null
                DeviceRow(
                    // Anonymous neighbours get a positional suffix so they stay
                    // distinct rows; identifiable cells keep the bare tower key.
                    key = if (identifiable) keyOf(cell) else "${keyOf(cell)}#$index",
                    title = cell.carrierName.ifBlank { "${cell.mcc}-${cell.mnc}" },
                    subtitle = cell.cellId,
                    metric = cell.signalDbm?.let { "$it dBm" } ?: "—",
                    columns = cellColumns(cell),
                    // An unidentifiable cell can't be said to be new or not.
                    change = if (identifiable) changeFor(compare, prior != null) else DeviceChange.UNKNOWN,
                    signalDelta = deltaOf(cell.signalDbm, prior?.signalDbm),
                )
            }

        val gone = if (!compare) emptyList() else before
            .filter(::isIdentifiable)
            .filter { old -> current.none { isIdentifiable(it) && keyOf(it) == keyOf(old) } }
            .map { old ->
                DeviceRow(
                    key = keyOf(old),
                    title = old.carrierName.ifBlank { "${old.mcc}-${old.mnc}" },
                    subtitle = old.cellId,
                    metric = old.signalDbm?.let { "$it dBm" } ?: "—",
                    columns = cellColumns(old),
                    change = DeviceChange.GONE,
                )
            }

        return rows + gone
    }

    private fun cellColumns(cell: CellObservationDto): List<String> = listOf(
        cell.carrierName.ifBlank { "${cell.mcc}-${cell.mnc}" },
        cell.cellId.ifBlank { "—" },
        cell.radioType,
        cell.signalDbm?.let { "$it dBm" } ?: "—",
        cell.tacOrLac.ifBlank { "—" },
        if (cell.isServingCell) "Serving" else "Neighbour",
    )

    // --- BLE --------------------------------------------------------------
    // Keyed on MAC, matching the backend's BLEDevice.
    //
    // Modern devices rotate their advertised MAC roughly every 15 minutes for
    // privacy, so a device can appear GONE and NEW in consecutive passes
    // without having moved. The UI says so. We deliberately do not try to
    // re-link a rotated address to the device it came from — correlating
    // rotating identifiers is tracker-following, which this project doesn't do.

    private fun diffBle(
        latest: ScanSessionUploadRequest?,
        previous: ScanSessionUploadRequest?,
    ): List<DeviceRow> {
        val current = latest?.bleObservations.orEmpty()
        val before = previous?.bleObservations.orEmpty()
        val compare = canCompare(before)
        val beforeByKey = before.associateBy { it.bleMac }

        val rows = current.sortedByDescending { it.rssi }.map { device ->
            val prior = beforeByKey[device.bleMac]
            DeviceRow(
                key = device.bleMac,
                title = device.deviceName.ifBlank { "(unnamed)" },
                subtitle = device.bleMac,
                metric = "${device.rssi} dBm",
                columns = listOf(
                    device.deviceName.ifBlank { "(unnamed)" },
                    device.bleMac,
                    "${device.rssi} dBm",
                    device.deviceTypeGuess,
                ),
                change = changeFor(compare, prior != null),
                signalDelta = if (prior != null) device.rssi - prior.rssi else null,
            )
        }

        val gone = if (!compare) emptyList() else before
            .filter { old -> current.none { it.bleMac == old.bleMac } }
            .map { old ->
                DeviceRow(
                    key = old.bleMac,
                    title = old.deviceName.ifBlank { "(unnamed)" },
                    subtitle = old.bleMac,
                    metric = "${old.rssi} dBm",
                    columns = listOf(
                        old.deviceName.ifBlank { "(unnamed)" },
                        old.bleMac,
                        "${old.rssi} dBm",
                        old.deviceTypeGuess,
                    ),
                    change = DeviceChange.GONE,
                )
            }

        return rows + gone
    }

    // --- Satellites -------------------------------------------------------
    // Keyed on constellation + SVID: an SVID is only unique within its
    // constellation, so GPS 12 and Galileo 12 are different satellites.

    private fun diffSatellite(
        latest: ScanSessionUploadRequest?,
        previous: ScanSessionUploadRequest?,
    ): List<DeviceRow> {
        val current = latest?.satelliteObservations.orEmpty()
        val before = previous?.satelliteObservations.orEmpty()
        val compare = canCompare(before)
        val keyOf = { s: SatelliteObservationDto ->
            "${s.constellation}-${s.svid}"
        }
        val beforeByKey = before.associateBy(keyOf)

        val rows = current.sortedByDescending { it.cn0DbHz }.map { sat ->
            val prior = beforeByKey[keyOf(sat)]
            DeviceRow(
                key = keyOf(sat),
                title = "${sat.constellation} ${sat.svid}",
                subtitle = if (sat.usedInFix) "used in fix" else "visible",
                metric = "${sat.cn0DbHz.toInt()} dB-Hz",
                columns = satelliteColumns(sat),
                change = changeFor(compare, prior != null),
                signalDelta = if (prior != null) (sat.cn0DbHz - prior.cn0DbHz).toInt() else null,
            )
        }

        val gone = if (!compare) emptyList() else before
            .filter { old -> current.none { keyOf(it) == keyOf(old) } }
            .map { old ->
                DeviceRow(
                    key = keyOf(old),
                    title = "${old.constellation} ${old.svid}",
                    subtitle = "visible",
                    metric = "${old.cn0DbHz.toInt()} dB-Hz",
                    columns = satelliteColumns(old),
                    change = DeviceChange.GONE,
                )
            }

        return rows + gone
    }

    private fun satelliteColumns(sat: SatelliteObservationDto): List<String> = listOf(
        sat.constellation,
        sat.svid.toString(),
        "${sat.cn0DbHz.toInt()} dB-Hz",
        sat.elevationDegrees?.let { "${it.toInt()}°" } ?: "—",
        sat.azimuthDegrees?.let { "${it.toInt()}°" } ?: "—",
        if (sat.usedInFix) "Yes" else "No",
    )

    // --- Shared -----------------------------------------------------------

    private fun changeFor(comparable: Boolean, seenBefore: Boolean): DeviceChange = when {
        !comparable -> DeviceChange.UNKNOWN
        seenBefore -> DeviceChange.PRESENT
        else -> DeviceChange.NEW
    }

    private fun deltaOf(now: Int?, before: Int?): Int? =
        if (now != null && before != null) now - before else null
}

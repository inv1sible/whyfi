package com.whyfi.app.report

import com.whyfi.app.data.remote.LanObservationDto
import com.whyfi.app.lan.LanRange
import com.whyfi.app.lan.LanWebLinks
import com.whyfi.app.lan.lanDeviceTypeLabel
import com.whyfi.app.scan.DeviceChange
import com.whyfi.app.scan.DeviceRow
import com.whyfi.app.scan.LocationTrack
import com.whyfi.app.scan.RadioFormat
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.scan.StrongestSighting
import com.whyfi.app.scan.SurveyStats
import com.whyfi.app.scan.TrackPoint
import java.util.Locale

/**
 * Table and tile contents for the phone's LAN, GPS and Overview reports.
 * Pure (no Android types) so it is JVM-testable (PhoneReportContentTest);
 * PhoneReports draws them with ReportPdf.
 */
object LanReportContent {

    fun stats(devices: List<LanObservationDto>, addressesSwept: Int): List<ReportStat> = listOf(
        ReportStat(ReportFormat.number(devices.size.toLong()), "devices answered"),
        ReportStat(ReportFormat.number(addressesSwept.toLong()), "addresses swept"),
        ReportStat(ReportFormat.number(devices.count { LanWebLinks.links(it.ipAddress, it.openPorts).isNotEmpty() }.toLong()), "with a web interface"),
        ReportStat(ReportFormat.number(devices.sumOf { it.openPorts.size }.toLong()), "open ports"),
        ReportStat(ReportFormat.number(devices.count { it.deviceTypeGuess == "CAMERA" }.toLong()), "cameras (ONVIF or ports)"),
        ReportStat(ReportFormat.number(devices.count { it.macAddress.isNotBlank() }.toLong()), "with a MAC address"),
    )

    private fun ipOrder(device: LanObservationDto) = LanRange.ip(device.ipAddress) ?: Long.MAX_VALUE

    fun devicesTable(devices: List<LanObservationDto>): ReportTable = ReportTable(
        columns = listOf(
            ReportColumn("IP address", 1.15f, CellKind.MONO), ReportColumn("Hostname", 1.5f), ReportColumn("Type", 1.1f),
            ReportColumn("Open ports", 1.5f), ReportColumn("Web", 1.5f, CellKind.MONO),
            ReportColumn("Response", 0.75f, CellKind.NUMBER), ReportColumn("MAC", 1.25f, CellKind.MONO),
        ),
        rows = devices.sortedBy(::ipOrder).map { d ->
            listOf(
                d.ipAddress, d.hostname.ifBlank { "–" }, lanDeviceTypeLabel(d.deviceTypeGuess),
                d.openPorts.sorted().joinToString(", ").ifBlank { "–" },
                LanWebLinks.links(d.ipAddress, d.openPorts).joinToString("\n") { it.url }.ifBlank { "–" },
                d.responseTimeMs?.let { String.format(Locale.US, "%.0f ms", it) } ?: "–",
                d.macAddress.ifBlank { "–" },
            )
        },
        empty = "No device answered the sweep.",
    )

    /** Appendix: banners and ONVIF fields, only for devices that have them. */
    fun detailsTable(devices: List<LanObservationDto>): ReportTable = ReportTable(
        columns = listOf(ReportColumn("IP address", 1.1f, CellKind.MONO), ReportColumn("Banner", 2.6f), ReportColumn("ONVIF", 2.6f)),
        rows = devices.sortedBy(::ipOrder).filter { it.banner.isNotBlank() || it.onvif.isNotEmpty() }.map { d ->
            listOf(
                d.ipAddress, d.banner.ifBlank { "–" },
                d.onvif.entries.filter { it.value.isNotBlank() }.joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "–" },
            )
        },
        empty = "No device returned a banner or ONVIF details.",
    )

    fun interfacesTable(lines: List<String>): ReportTable = ReportTable(
        columns = listOf(ReportColumn("Interface, address and whether it could be swept", 1f, CellKind.MONO)),
        rows = lines.map { listOf(it) },
        empty = "No interface list was recorded.",
    )
}

object GpsReportContent {
    const val MAX_TRACK_ROWS = 200

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s >= 3600 -> String.format(Locale.US, "%d h %02d min", s / 3600, (s / 60) % 60)
            s >= 60 -> String.format(Locale.US, "%d min %02d s", s / 60, s % 60)
            else -> "$s s"
        }
    }

    fun distance(m: Double): String = if (m >= 1000) String.format(Locale.US, "%.2f km", m / 1000) else ReportFormat.metres(m)

    fun stats(track: List<TrackPoint>, satellites: List<DeviceRow>): List<ReportStat> {
        val seen = satellites.filter { it.change != DeviceChange.GONE }
        return listOf(
            ReportStat(ReportFormat.number(track.size.toLong()), "positions on the path"),
            ReportStat(distance(LocationTrack.lengthM(track)), "path length"),
            ReportStat(if (track.size > 1) duration(track.last().atMs - track.first().atMs) else "–", "recorded for"),
            ReportStat(track.mapNotNull { it.accuracyM }.minOrNull()?.let { "±" + ReportFormat.metres(it) } ?: "–", "best accuracy"),
            ReportStat(LocationTrack.medianAccuracyM(track)?.let { "±" + ReportFormat.metres(it) } ?: "–", "median accuracy"),
            ReportStat("${seen.count { it.subtitle == "used in fix" }} / ${seen.size}", "satellites used / visible"),
        )
    }

    /** Every n-th point (plus the last) so a long walk fits a few pages. */
    fun trackTable(track: List<TrackPoint>, time: (Long) -> String): ReportTable {
        val step = ((track.size + MAX_TRACK_ROWS - 1) / MAX_TRACK_ROWS).coerceAtLeast(1)
        val shown = track.filterIndexed { i, _ -> i % step == 0 }.let { if (track.isNotEmpty() && it.last() != track.last()) it + track.last() else it }
        return ReportTable(
            columns = listOf(
                ReportColumn("Time", 1f, CellKind.MONO), ReportColumn("Latitude", 1.1f, CellKind.NUMBER),
                ReportColumn("Longitude", 1.1f, CellKind.NUMBER), ReportColumn("Accuracy", 0.9f, CellKind.NUMBER), ReportColumn("Source", 0.8f),
            ),
            rows = shown.map {
                listOf(time(it.atMs), ReportFormat.coord(it.lat), ReportFormat.coord(it.lng),
                    it.accuracyM?.let { a -> "±" + ReportFormat.metres(a) } ?: "–", it.provider)
            },
            empty = "No position was recorded. Open the GPS screen or run a scan with Location on.",
            note = if (step > 1) "Every ${step}th of ${track.size} positions." else null,
        )
    }

    fun providersTable(track: List<TrackPoint>): ReportTable = ReportTable(
        columns = listOf(
            ReportColumn("Source", 1f), ReportColumn("Positions", 0.9f, CellKind.NUMBER),
            ReportColumn("Median accuracy", 1.2f, CellKind.NUMBER), ReportColumn("Share", 3f, CellKind.BAR),
        ),
        rows = track.groupBy { it.provider }.entries.sortedByDescending { it.value.size }.map { (provider, points) ->
            listOf(provider, ReportFormat.number(points.size.toLong()),
                LocationTrack.medianAccuracyM(points)?.let { "±" + ReportFormat.metres(it) } ?: "–",
                ReportFormat.fraction(points.size.toDouble() / track.size))
        },
        empty = "No positions.",
    )
}

object OverviewReportContent {

    fun stats(survey: SurveyStats, lanDevices: Int?): List<ReportStat> = listOf(
        ReportStat(ReportFormat.number(survey.passCount.toLong()), "scan passes"),
        ReportStat(ReportFormat.number(survey.uniqueWifi.toLong()), "WiFi access points"),
        ReportStat(ReportFormat.number(survey.uniqueCellular.toLong()), "cell towers"),
        ReportStat(ReportFormat.number(survey.uniqueBle.toLong()), "Bluetooth devices"),
        ReportStat(ReportFormat.number(survey.uniqueSatellites.toLong()), "satellites"),
        ReportStat(lanDevices?.let { ReportFormat.number(it.toLong()) } ?: "–", "LAN devices (last sweep)"),
    )

    fun bandsTable(survey: SurveyStats): ReportTable {
        val bands = listOf(RadioFormat.BAND_24, RadioFormat.BAND_5, RadioFormat.BAND_6)
        val total = survey.wifiByBand.values.sum().coerceAtLeast(1)
        return ReportTable(
            columns = listOf(ReportColumn("Band", 1f), ReportColumn("Access points", 1f, CellKind.NUMBER), ReportColumn("Share", 3.5f, CellKind.BAR)),
            rows = bands.filter { (survey.wifiByBand[it] ?: 0) > 0 }.map { band ->
                val count = survey.wifiByBand[band] ?: 0
                listOf(band, ReportFormat.number(count.toLong()), ReportFormat.fraction(count.toDouble() / total))
            },
            empty = "No WiFi access points this session.",
        )
    }

    fun strongestTable(survey: SurveyStats): ReportTable {
        fun row(radio: String, s: StrongestSighting?) = s?.let { listOf(radio, it.label, it.detail.ifBlank { "–" }, "${it.signal} dBm") }
        return ReportTable(
            columns = listOf(ReportColumn("Radio", 0.9f), ReportColumn("Strongest", 2f), ReportColumn("Detail", 2f), ReportColumn("Signal", 0.8f, CellKind.NUMBER)),
            rows = listOfNotNull(row("WiFi", survey.strongestWifi), row("Cellular", survey.strongestCellular), row("Bluetooth", survey.strongestBle)),
            empty = "Nothing heard yet this session.",
        )
    }

    /** Seen / new / gone per radio between the last two passes. */
    fun changesTable(rowsByKind: Map<RadioKind, List<DeviceRow>>): ReportTable = ReportTable(
        columns = listOf(ReportColumn("Radio", 1.2f), ReportColumn("In last pass", 1f, CellKind.NUMBER),
            ReportColumn("New", 0.8f, CellKind.NUMBER), ReportColumn("Gone", 0.8f, CellKind.NUMBER)),
        rows = RadioKind.entries.mapNotNull { kind ->
            val rows = rowsByKind[kind] ?: return@mapNotNull null
            val comparable = rows.any { it.change != DeviceChange.UNKNOWN }
            listOf(kind.label, "${rows.count { it.change != DeviceChange.GONE }}",
                if (comparable) "${rows.count { it.change == DeviceChange.NEW }}" else "–",
                if (comparable) "${rows.count { it.change == DeviceChange.GONE }}" else "–")
        },
        empty = "No completed pass yet.",
        note = "“–” means there was no previous pass to compare with.",
    )

    fun headers(kind: RadioKind): List<String> = when (kind) {
        RadioKind.WIFI -> listOf("SSID", "BSSID", "Signal", "Channel", "Security")
        RadioKind.CELLULAR -> listOf("Carrier", "Cell ID", "Type", "Signal", "TAC/LAC", "Serving")
        RadioKind.BLE -> listOf("Name", "MAC", "Signal", "Type")
        RadioKind.SATELLITE -> listOf("Constellation", "SVID", "C/N0", "Elev", "Azim", "In fix")
    }

    /** Appendix: the last pass's devices for one radio, as on the detail screen. */
    fun passTable(kind: RadioKind, rows: List<DeviceRow>): ReportTable {
        val headers = headers(kind)
        val mono = setOf("BSSID", "MAC", "Cell ID")
        return ReportTable(
            columns = headers.map { ReportColumn(it, if (it in mono || it == "SSID" || it == "Name" || it == "Carrier") 1.5f else 0.9f,
                if (it in mono) CellKind.MONO else CellKind.TEXT) } + ReportColumn("Change", 0.8f),
            rows = rows.map { row ->
                headers.indices.map { i -> row.columns.getOrElse(i) { "" }.ifBlank { "–" } } +
                    when (row.change) {
                        DeviceChange.NEW -> "new"
                        DeviceChange.GONE -> "gone"
                        DeviceChange.PRESENT -> "seen before"
                        DeviceChange.UNKNOWN -> "–"
                    }
            },
            empty = "Nothing in the last pass.",
        )
    }
}

/** The per-radio report from the detail screen (WiFi, cellular, BLE). */
object RadioReportContent {

    private fun signal(row: DeviceRow): Int? = Regex("-?\\d+").find(row.metric)?.value?.toIntOrNull()

    fun stats(kind: RadioKind, rows: List<DeviceRow>, vendors: Int?): List<ReportStat> {
        val present = rows.filter { it.change != DeviceChange.GONE }
        val comparable = rows.any { it.change != DeviceChange.UNKNOWN }
        val signals = present.mapNotNull(::signal).sorted()
        return listOfNotNull(
            ReportStat(ReportFormat.number(present.size.toLong()), "${kind.label} devices in the last pass"),
            ReportStat(if (comparable) "${rows.count { it.change == DeviceChange.NEW }}" else "–", "new since the previous pass"),
            ReportStat(if (comparable) "${rows.count { it.change == DeviceChange.GONE }}" else "–", "gone since the previous pass"),
            ReportStat(signals.lastOrNull()?.let { "$it dBm" } ?: "–", "strongest signal"),
            ReportStat(signals.getOrNull(signals.size / 2)?.let { "$it dBm" } ?: "–", "median signal"),
            vendors?.let { ReportStat(ReportFormat.number(it.toLong()), "vendors identified") },
        )
    }

    /** BLE main table: the useful subset of the detail screen's columns. */
    fun bleTable(rows: List<DeviceRow>, info: Map<String, BleDeviceSummary>): ReportTable = ReportTable(
        columns = listOf(
            ReportColumn("Name", 1.3f), ReportColumn("MAC", 1.3f, CellKind.MONO), ReportColumn("Signal", 0.7f, CellKind.NUMBER),
            ReportColumn("Vendor", 1.5f), ReportColumn("Type", 0.9f), ReportColumn("Address", 1.1f), ReportColumn("Change", 0.7f),
        ),
        rows = rows.map { row ->
            val i = info[row.key]
            listOf(row.columns.getOrElse(0) { "–" }, row.key, row.metric, i?.vendor ?: "–",
                row.columns.getOrElse(3) { "–" }.ifBlank { "–" }, i?.addressType ?: "–", changeLabel(row.change))
        },
        empty = "No Bluetooth device in the last pass.",
    )

    fun vendorsTable(info: Collection<BleDeviceSummary>): ReportTable {
        val known = info.filter { it.vendor != "–" }
        return ReportTable(
            columns = listOf(ReportColumn("Vendor", 2f), ReportColumn("Devices", 0.8f, CellKind.NUMBER), ReportColumn("Share", 3f, CellKind.BAR)),
            rows = known.groupBy { it.vendor }.entries.sortedByDescending { it.value.size }.map { (vendor, list) ->
                listOf(vendor, "${list.size}", ReportFormat.fraction(list.size.toDouble() / info.size.coerceAtLeast(1)))
            },
            empty = "No vendor could be identified.",
            note = "Share of all ${info.size} devices; ${info.size - known.size} have no identifiable vendor.",
        )
    }

    fun bleDetailsTable(info: Map<String, BleDeviceSummary>): ReportTable = ReportTable(
        columns = listOf(ReportColumn("MAC", 1.2f, CellKind.MONO), ReportColumn("Vendor source", 1.2f), ReportColumn("Services", 2f),
            ReportColumn("Connectable", 0.8f), ReportColumn("TX power", 0.7f, CellKind.NUMBER), ReportColumn("Advertisement", 2.2f, CellKind.MONO)),
        rows = info.entries.sortedBy { it.key }.map { (mac, i) ->
            listOf(mac, i.vendorSource.ifBlank { "–" }, i.services.ifBlank { "–" }, i.connectable, i.txPower, i.raw.ifBlank { "–" })
        },
        empty = "No Bluetooth device in the last pass.",
    )

    fun changeLabel(change: DeviceChange) = when (change) {
        DeviceChange.NEW -> "new"
        DeviceChange.GONE -> "gone"
        DeviceChange.PRESENT -> "seen before"
        DeviceChange.UNKNOWN -> "–"
    }
}

/** BleDeviceInfo flattened to report strings (kept here so the report
 * content stays independent of the BLE package's types). */
data class BleDeviceSummary(
    val vendor: String,
    val vendorSource: String,
    val services: String,
    val addressType: String,
    val connectable: String,
    val txPower: String,
    /** Manufacturer records and service data as advertised. */
    val raw: String,
)

/** The session report written when scanning stops. */
object ScanReportContent {
    /** One table per radio of every unique device this session, strongest reading first. */
    fun sessionTable(kind: RadioKind, devices: List<com.whyfi.app.scan.SurveyTally.SeenDevice>): ReportTable {
        val (label, id, detail) = when (kind) {
            RadioKind.WIFI -> Triple("SSID", "BSSID", "Band")
            RadioKind.CELLULAR -> Triple("Carrier", "Cell", "Type · cell ID")
            else -> Triple("Name", "MAC", "Type")
        }
        return ReportTable(
            columns = listOf(ReportColumn(label, 1.6f), ReportColumn(id, 1.6f, CellKind.MONO), ReportColumn(detail, 1.3f),
                ReportColumn("Best signal", 0.8f, CellKind.NUMBER)),
            rows = devices.map { listOf(it.label.ifBlank { "–" }, it.id, it.detail.ifBlank { "–" }, "${it.best} dBm") },
            empty = "Nothing heard this session.",
        )
    }

    fun stats(survey: SurveyStats, publicIp: String?): List<ReportStat> = listOf(
        ReportStat(ReportFormat.number(survey.passCount.toLong()), "scan passes"),
        ReportStat(ReportFormat.number(survey.uniqueWifi.toLong()), "WiFi access points"),
        ReportStat(ReportFormat.number(survey.uniqueCellular.toLong()), "cell towers"),
        ReportStat(ReportFormat.number(survey.uniqueBle.toLong()), "Bluetooth devices"),
        ReportStat(ReportFormat.number(survey.uniqueSatellites.toLong()), "satellites"),
        ReportStat(publicIp ?: "–", "public IP"),
    )
}

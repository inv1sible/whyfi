package com.whyfi.app.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.whyfi.app.ble.BleDeviceInfo
import com.whyfi.app.scan.RadioKind
import com.whyfi.app.scan.ScanDiff
import com.whyfi.app.scan.ScanUiState
import com.whyfi.app.scan.TrackPoint
import com.whyfi.app.usbmon.MonitorReportContent
import com.whyfi.app.usbmon.MonitorTileMap
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's LAN, GPS and Overview PDF reports, laid out like the mission
 * report: cover with key figures and contents, a map, tables, appendix.
 * Written under the app's external files dir (reports/), shared through
 * the FileProvider and opened in the default PDF viewer.
 */
object PhoneReports {
    private const val AUTHORITY_SUFFIX = ".monitorfiles"

    private fun file(context: Context, kind: String): File {
        val dir = File(context.getExternalFilesDir(null) ?: throw IOException("No app storage available"), "reports")
        dir.mkdirs()
        // Keep the folder small: older reports of the same kind are replaced.
        dir.listFiles { f -> f.name.startsWith("whyfi-$kind-") }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return File(dir, "whyfi-$kind-$stamp.pdf")
    }

    private fun write(doc: ReportPdf, target: File): File {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            temporary.outputStream().use { doc.writeTo(it) }
            if (!temporary.renameTo(target)) throw IOException("Could not save the PDF report")
        } finally {
            doc.close()
            temporary.delete()
        }
        return target
    }

    private fun now() = DateFormat.getDateTimeInstance().format(Date())
    private val time get() = DateFormat.getTimeInstance(DateFormat.MEDIUM)
    private fun route(track: List<TrackPoint>) = track.map { it.lat to it.lng }

    suspend fun lan(context: Context, state: ScanUiState): File = withContext(Dispatchers.IO) {
        val generated = now()
        val devices = state.lanDevices
        val doc = ReportPdf("LAN scan report", state.lanTargetLabel ?: "Local network", "whyfi · LAN scan · generated $generated")
        doc.keyValues(listOf(
            "Swept" to (state.lanTargetLabel ?: "unknown range"),
            "Finished" to if (state.lanFinishedAtMs > 0) DateFormat.getDateTimeInstance().format(Date(state.lanFinishedAtMs)) else "still running",
            "Location" to (state.lanLatitude?.let { "%.6f, %.6f".format(Locale.US, it, state.lanLongitude ?: 0.0) +
                (state.lanAccuracyM?.let { a -> " · ±%.0f m".format(Locale.US, a) } ?: "") } ?: "no fix"),
            "Report generated" to generated,
        ))
        doc.statTiles(LanReportContent.stats(devices, state.lanTotal))
        doc.subheading("Contents")
        doc.bullets(listOf("1  Location", "2  Devices", "Appendix A  Banners and ONVIF", "Appendix B  Network interfaces",
            "Appendix C  Method"), muted = false)

        doc.heading("1", "Location")
        val lat = state.lanLatitude
        val lng = state.lanLongitude
        if (lat == null || lng == null) {
            doc.paragraph("No location fix was available when the sweep finished.", muted = true)
        } else {
            val marker = MonitorReportContent.MapMarker(1, "Sweep", lat, lng, (state.lanAccuracyM ?: 0f).toDouble())
            doc.figure(260f, "Where the phone was when the sweep finished; the ring is the fix's accuracy.") { canvas, area ->
                MonitorTileMap.drawOverview(context, canvas, area, emptyList(), listOf(marker))
            }
        }
        doc.heading("2", "Devices")
        doc.table(LanReportContent.devicesTable(devices))

        doc.pageBreak()
        doc.heading("A", "Appendix A · Banners and ONVIF")
        doc.table(LanReportContent.detailsTable(devices))
        doc.heading("B", "Appendix B · Network interfaces")
        doc.table(LanReportContent.interfacesTable(state.lanNetworkReport))
        doc.heading("C", "Appendix C · Method")
        doc.bullets(listOf(
            "Each address gets a short TCP connect on common service ports; no root and no ICMP ping are needed.",
            "A device that answers on none of them is not listed, so a quiet device can be missing.",
            "ONVIF cameras are found by a WS-Discovery multicast probe on the local network.",
            "Device types are guesses from open ports, banners and ONVIF replies.",
            "Modern Android hides the ARP table from apps, so MAC addresses are usually unavailable.",
            "Map tiles © OpenStreetMap contributors.",
        ), muted = false)
        write(doc, file(context, "lan"))
    }

    suspend fun gps(context: Context, state: ScanUiState, track: List<TrackPoint>): File = withContext(Dispatchers.IO) {
        val generated = now()
        val satellites = ScanDiff.rowsFor(RadioKind.SATELLITE, state.latestPass, state.previousPass)
        val doc = ReportPdf("GPS report", "Location path and accuracy", "whyfi · GPS · generated $generated")
        doc.keyValues(listOf(
            "Path" to if (track.isEmpty()) "no positions recorded" else
                "${DateFormat.getDateTimeInstance().format(Date(track.first().atMs))} – ${time.format(Date(track.last().atMs))}",
            "Last position" to (track.lastOrNull()?.let { "%.6f, %.6f".format(Locale.US, it.lat, it.lng) +
                (it.accuracyM?.let { a -> " · ±%.0f m · ${it.provider}".format(Locale.US, a) } ?: "") } ?: "–"),
            "Report generated" to generated,
        ))
        doc.statTiles(GpsReportContent.stats(track, satellites))
        doc.subheading("Contents")
        doc.bullets(listOf("1  Path map", "2  Accuracy by source", "3  Positions", "Appendix A  Satellites in the last pass",
            "Appendix B  Method"), muted = false)

        doc.heading("1", "Path map")
        if (track.isEmpty()) doc.paragraph("No position was recorded this session.", muted = true)
        else {
            val last = track.last()
            val marker = MonitorReportContent.MapMarker(1, "Last fix", last.lat, last.lng, last.accuracyM ?: 0.0)
            doc.figure(340f, "The phone's path (blue) from ${track.size} positions; marker 1 is the last fix, its ring the reported accuracy.") { canvas, area ->
                MonitorTileMap.drawOverview(context, canvas, area, route(track), listOf(marker))
            }
        }
        doc.heading("2", "Accuracy by source")
        doc.table(GpsReportContent.providersTable(track))
        doc.heading("3", "Positions")
        doc.table(GpsReportContent.trackTable(track) { time.format(Date(it)) })

        doc.pageBreak()
        doc.heading("A", "Appendix A · Satellites in the last pass")
        doc.table(OverviewReportContent.passTable(RadioKind.SATELLITE, satellites))
        doc.heading("B", "Appendix B · Method")
        doc.bullets(listOf(
            "Positions come from Android's GPS, fused and network providers while scanning or while the GPS screen is open.",
            "GPS fixes are always kept; fused only after 5 s without GPS, network only after 15 s without either, so the path doesn't zigzag between sources.",
            "Fixes worse than ±500 m and repeats within 1 m are dropped; the path keeps the last 5,000 positions.",
            "Accuracy is Android's 68 % radius, not a guaranteed bound.",
            "Map tiles © OpenStreetMap contributors.",
        ), muted = false)
        write(doc, file(context, "gps"))
    }

    suspend fun overview(context: Context, state: ScanUiState, track: List<TrackPoint>): File = withContext(Dispatchers.IO) {
        val generated = now()
        val survey = state.survey
        val rowsByKind = if (state.latestPass == null) emptyMap()
        else RadioKind.entries.associateWith { ScanDiff.rowsFor(it, state.latestPass, state.previousPass) }
        val doc = ReportPdf("Overview report", "Scanning session", "whyfi · overview · generated $generated")
        doc.keyValues(listOf(
            "Session started" to if (survey.startedAtMs > 0) DateFormat.getDateTimeInstance().format(Date(survey.startedAtMs)) else "–",
            "Last pass" to (state.latestPass?.startedAt ?: "none"),
            "Report generated" to generated,
        ))
        doc.statTiles(OverviewReportContent.stats(survey, state.lanDeviceCount))
        doc.subheading("Contents")
        doc.bullets(listOf("1  Map", "2  Changes since the previous pass", "3  WiFi by band", "4  Strongest heard",
            "Appendix A–D  Last pass: WiFi, cellular, Bluetooth, satellites", "Appendix E  Notes"), muted = false)

        doc.heading("1", "Map")
        val pass = state.latestPass
        val passMarker = pass?.latitude?.let { lat -> pass.longitude?.let { lng ->
            MonitorReportContent.MapMarker(1, "Last pass", lat, lng, (pass.locationAccuracyMeters ?: 0f).toDouble())
        } }
        if (track.isEmpty() && passMarker == null) doc.paragraph("No position was recorded this session.", muted = true)
        else doc.figure(320f, "The phone's path this session (blue); marker 1 is where the last pass was taken, its ring the fix accuracy.") { canvas, area ->
            MonitorTileMap.drawOverview(context, canvas, area, route(track), listOfNotNull(passMarker))
        }
        doc.heading("2", "Changes since the previous pass")
        doc.table(OverviewReportContent.changesTable(rowsByKind))
        doc.heading("3", "WiFi by band")
        doc.table(OverviewReportContent.bandsTable(survey))
        doc.heading("4", "Strongest heard")
        doc.table(OverviewReportContent.strongestTable(survey))

        listOf(RadioKind.WIFI to "A", RadioKind.CELLULAR to "B", RadioKind.BLE to "C", RadioKind.SATELLITE to "D").forEachIndexed { i, (kind, letter) ->
            if (i == 0) doc.pageBreak()
            doc.heading(letter, "Appendix $letter · Last pass: ${kind.label}")
            doc.table(OverviewReportContent.passTable(kind, rowsByKind[kind].orEmpty()))
        }
        doc.heading("E", "Appendix E · Notes")
        doc.bullets(listOf(
            "Totals cover this scanning session only and reset when the scanner stops; the web app has every uploaded scan.",
            "Bluetooth devices rotate their MAC address, so some new and gone entries are the same device.",
            "Signal levels are what this phone heard; they depend on its antenna, orientation and surroundings.",
            "Map tiles © OpenStreetMap contributors.",
        ), muted = false)
        write(doc, file(context, "overview"))
    }

    /** WiFi, cellular or BLE: the last pass from the detail screen. */
    suspend fun radio(
        context: Context,
        kind: RadioKind,
        state: ScanUiState,
        track: List<TrackPoint>,
        bleInfo: Map<String, BleDeviceInfo.Info>,
    ): File = withContext(Dispatchers.IO) {
        val generated = now()
        val rows = ScanDiff.rowsFor(kind, state.latestPass, state.previousPass)
        val pass = state.latestPass
        val raw = (state.previousPass?.bleObservations.orEmpty() + pass?.bleObservations.orEmpty()).associateBy { it.bleMac }
        val summaries = if (kind != RadioKind.BLE) emptyMap() else rows.associate { row ->
            val i = bleInfo[row.key]
            val dto = raw[row.key]
            row.key to BleDeviceSummary(
                vendor = i?.vendor ?: "–", vendorSource = i?.vendorSource.orEmpty(),
                services = i?.services?.joinToString(", ").orEmpty(), addressType = i?.addressType ?: "–",
                connectable = if (i?.connectable == true) "yes" else "no", txPower = i?.txPower?.let { "$it dBm" } ?: "–",
                raw = dto?.let { (it.manufacturerRecords.ifEmpty { listOfNotNull(it.manufacturerData.ifBlank { null }) } + it.serviceData).joinToString("\n") }.orEmpty(),
            )
        }
        val title = "${kind.label} report"
        val doc = ReportPdf(title, "Last scan pass", "whyfi · ${kind.label} · generated $generated")
        doc.keyValues(listOf(
            "Pass started" to (pass?.startedAt ?: "none"),
            "Location" to (pass?.latitude?.let { "%.6f, %.6f".format(Locale.US, it, pass.longitude ?: 0.0) +
                (pass.locationAccuracyMeters?.let { a -> " · ±%.0f m".format(Locale.US, a) } ?: "") } ?: "no fix"),
            "Report generated" to generated,
        ))
        doc.statTiles(RadioReportContent.stats(kind, rows,
            if (kind == RadioKind.BLE) summaries.values.map { it.vendor }.filter { it != "–" }.distinct().size else null))
        doc.subheading("Contents")
        doc.bullets(listOfNotNull("1  Map", "2  Devices", if (kind == RadioKind.BLE) "3  Vendors" else null,
            if (kind == RadioKind.BLE) "Appendix A  Advertisement details" else null, "Appendix ${if (kind == RadioKind.BLE) "B" else "A"}  Notes"), muted = false)

        doc.heading("1", "Map")
        val marker = pass?.latitude?.let { lat -> pass.longitude?.let { lng ->
            MonitorReportContent.MapMarker(1, "Pass", lat, lng, (pass.locationAccuracyMeters ?: 0f).toDouble())
        } }
        if (marker == null && track.isEmpty()) doc.paragraph("No location fix was recorded for this pass.", muted = true)
        else doc.figure(300f, "Marker 1 is where the pass was taken, its ring the fix accuracy; blue is the phone's path this session.") { canvas, area ->
            MonitorTileMap.drawOverview(context, canvas, area, route(track), listOfNotNull(marker))
        }

        doc.heading("2", "Devices")
        doc.table(if (kind == RadioKind.BLE) RadioReportContent.bleTable(rows, summaries) else OverviewReportContent.passTable(kind, rows))
        var notes = "A"
        if (kind == RadioKind.BLE) {
            doc.heading("3", "Vendors")
            doc.table(RadioReportContent.vendorsTable(summaries.values))
            doc.pageBreak()
            doc.heading("A", "Appendix A · Advertisement details")
            doc.table(RadioReportContent.bleDetailsTable(summaries))
            notes = "B"
        }
        doc.heading(notes, "Appendix $notes · Notes")
        doc.bullets(when (kind) {
            RadioKind.BLE -> listOf(
                "Vendors come from the Bluetooth SIG company identifier in manufacturer data, else a vendor's registered service UUID, else the MAC prefix (only meaningful for a public address).",
                "Most phones and wearables use rotating private addresses, so one device can appear under several MACs.",
                "Android does not report whether an address is public or random; the address column is read from the MAC's top bits.",
            )
            RadioKind.CELLULAR -> listOf(
                "Neighbouring cells often report only partial identity; those rows show what the modem gave.",
                "Signal is what this phone's modem measured; it depends on the band and the phone.",
            )
            else -> listOf(
                "Hidden networks are listed by BSSID; one access point often broadcasts several BSSIDs.",
                "Signal is what this phone heard; it depends on its antenna, orientation and surroundings.",
            )
        } + "Map tiles © OpenStreetMap contributors.", muted = false)
        write(doc, file(context, kind.name.lowercase(Locale.ROOT)))
    }

    /** Opens [file] in the default PDF viewer; returns an error message, or null. */
    fun open(context: Context, file: File): String? = try {
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: android.content.ActivityNotFoundException) {
        "No PDF viewer installed."
    } catch (e: Exception) {
        "Could not open the report: ${e.message}"
    }
}

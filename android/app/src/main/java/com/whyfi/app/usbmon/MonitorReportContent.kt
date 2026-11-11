package com.whyfi.app.usbmon

import com.whyfi.app.report.CellKind
import com.whyfi.app.report.ReportColumn
import com.whyfi.app.report.ReportStat
import com.whyfi.app.report.ReportTable
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * What goes into a mission PDF's tables, map legend and appendix, kept apart
 * from the drawing (report/ReportPdf) so it is plain data and JVM-testable
 * (MonitorReportContentTest).
 */
internal object MonitorReportContent {

    data class MapMarker(val number: Int, val label: String, val lat: Double, val lng: Double, val uncertaintyM: Double)

    data class Bounds(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double) {
        val centerLat get() = (minLat + maxLat) / 2
        val centerLng get() = (minLng + maxLng) / 2

        /** The larger of the north–south and east–west extent, in metres. */
        val spanM: Double get() = max(
            (maxLat - minLat) * METERS_PER_DEGREE,
            (maxLng - minLng) * METERS_PER_DEGREE * max(cos(centerLat * PI / 180), 0.01),
        )
    }

    /** Mission events worth a line in the timeline appendix. User taps and
     * device snapshots are left out: there are thousands of them. */
    val TIMELINE_EVENTS = setOf(
        "mission_continued_from", "monitor_started", "mission_resumed", "monitor_finished",
        "mission_target", "ap_hidden", "move_suggested", "capture_summary", "co_travel_alert",
    )
    const val MAX_TIMELINE_ROWS = 150
    private const val METERS_PER_DEGREE = 111_320.0

    fun number(value: Long): String = String.format(Locale.US, "%,d", value)
    private fun coord(value: Double) = String.format(Locale.US, "%.6f", value)
    private fun metres(value: Double) = String.format(Locale.US, "≥%.0f m", value)

    fun stats(frames: Long, aps: Int, clients: Int, probingOnly: Int, eapolKeyFrames: Long, pinned: Int): List<ReportStat> = listOf(
        ReportStat(number(frames), "frames received"),
        ReportStat(number(aps.toLong()), "access points"),
        ReportStat(number(clients.toLong()), "clients"),
        ReportStat(number(probingOnly.toLong()), "probing, no association seen"),
        ReportStat(number(eapolKeyFrames), "EAPOL-Key frames (latest capture)"),
        ReportStat(number(pinned.toLong()), "pinned devices"),
    )

    /** Section 2: one row per starred device, numbered like the overview map. */
    fun positionsTable(targets: List<String>, estimates: Map<String, MonitorEstimate?>, names: Map<String, String>): ReportTable =
        ReportTable(
            columns = listOf(
                ReportColumn("#", 0.35f, CellKind.NUMBER), ReportColumn("Device", 1.5f), ReportColumn("MAC", 1.45f, CellKind.MONO),
                ReportColumn("Latitude", 1.05f, CellKind.NUMBER), ReportColumn("Longitude", 1.05f, CellKind.NUMBER),
                ReportColumn("Uncertainty", 0.85f, CellKind.NUMBER), ReportColumn("Places", 0.6f, CellKind.NUMBER), ReportColumn("Status", 1f),
            ),
            rows = targets.mapIndexed { index, mac ->
                val e = estimates[mac]
                listOf(
                    "${index + 1}", names[mac] ?: "Device", mac,
                    e?.let { coord(it.lat) } ?: "–", e?.let { coord(it.lng) } ?: "–",
                    e?.let { metres(it.uncertaintyM) } ?: "–", e?.spatialGroups?.toString() ?: "–",
                    when { e == null -> "no location fix"; e.provisional -> "provisional"; else -> "estimate" },
                )
            },
            empty = "No device was pinned. Star a device in Live to collect position readings.",
        )

    /** Section 3: receiver-derived hints for every device heard while a fix was available. */
    fun observationsTable(samplesByMac: Map<String, List<MonitorGeoSample>>, names: Map<String, String>): ReportTable {
        val rows = samplesByMac.entries.sortedByDescending { it.value.size }.mapNotNull { (mac, samples) ->
            val e = MonitorLocalization.estimate(samples) ?: return@mapNotNull null
            listOf(
                names[mac] ?: "Device", mac, number(samples.size.toLong()),
                coord(e.lat), coord(e.lng), metres(e.uncertaintyM), if (e.provisional) "receiver hint" else "estimate",
            )
        }
        return ReportTable(
            columns = listOf(
                ReportColumn("Device", 1.5f), ReportColumn("MAC", 1.45f, CellKind.MONO), ReportColumn("Readings", 0.75f, CellKind.NUMBER),
                ReportColumn("Latitude", 1.05f, CellKind.NUMBER), ReportColumn("Longitude", 1.05f, CellKind.NUMBER),
                ReportColumn("Uncertainty", 0.85f, CellKind.NUMBER), ReportColumn("Kind", 0.95f),
            ),
            rows = rows,
            empty = "No device reading could be matched to a phone location fix.",
            note = "Coordinates are receiver-derived hints; one sighting cannot locate a device, and clients may move.",
        )
    }

    /** Section 4: every device in the inventory, most recently heard first. */
    fun devicesTable(
        devices: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>,
        seen: Map<String, MonitorSeenClock.Span> = emptyMap(),
        time: (Long) -> String = { it.toString() },
    ): ReportTable = ReportTable(
        columns = listOf(
            ReportColumn("MAC", 1.45f, CellKind.MONO), ReportColumn("Name / vendor", 1.75f), ReportColumn("Type", 1.05f),
            ReportColumn("First seen", 0.95f, CellKind.MONO), ReportColumn("Last seen", 0.95f, CellKind.MONO),
            ReportColumn("Best RSSI", 0.7f, CellKind.NUMBER), ReportColumn("Channels", 0.8f),
            ReportColumn("Frames", 0.7f, CellKind.NUMBER), ReportColumn("Probes", 0.6f, CellKind.NUMBER),
        ),
        rows = devices.sortedWith(compareByDescending<Pair<SniffedDevices.Device, SniffedDevices.Guess>> {
            seen[it.first.mac]?.lastMs ?: Long.MIN_VALUE
        }.thenByDescending { it.first.lastMs }).map { (d, g) ->
            val span = seen[d.mac]
            listOf(
                d.mac, SniffedDevices.displayName(d, g), g.kind.label,
                span?.let { time(it.firstMs) } ?: "–", span?.let { time(it.lastMs) } ?: "–",
                d.bestRssi?.let { "$it dBm" } ?: "?", d.channels.sorted().joinToString(", ").ifBlank { "–" },
                number(d.frames), number(d.probeRequests),
            )
        },
        empty = "No devices were recorded.",
    )

    /** Section 5: frames per channel with a share bar (the BAR cell holds the fraction). */
    fun channelsTable(channelFrames: Map<Int, Long>): ReportTable {
        val total = channelFrames.values.sum().coerceAtLeast(1)
        return ReportTable(
            columns = listOf(ReportColumn("Channel", 0.8f, CellKind.NUMBER), ReportColumn("Frames", 1f, CellKind.NUMBER), ReportColumn("Share", 4f, CellKind.BAR)),
            rows = channelFrames.entries.sortedBy { it.key }.map { (channel, frames) ->
                listOf("$channel", number(frames), String.format(Locale.US, "%.4f", frames.toDouble() / total))
            },
            empty = "No per-channel counts were saved for the latest capture.",
        )
    }

    /** Appendix A: the long per-device fields that would swamp the main table. */
    fun deviceDetailsTable(devices: List<Pair<SniffedDevices.Device, SniffedDevices.Guess>>): ReportTable = ReportTable(
        columns = listOf(
            ReportColumn("MAC", 1.3f, CellKind.MONO), ReportColumn("Associated APs", 1.5f), ReportColumn("Probed networks", 1.6f),
            ReportColumn("Security / WPS", 2f), ReportColumn("Type reasons", 1.4f),
        ),
        rows = devices.sortedBy { it.first.mac }.mapNotNull { (d, g) ->
            val security = listOf(d.securityDetails, d.wpsDetails).filter { it.isNotBlank() }.joinToString(" · ")
            val probed = (d.probed + if (d.wildcardProbe) listOf("(wildcard)") else emptyList()).joinToString(", ")
            val row = listOf(d.mac, d.bssids.joinToString(", "), probed, security, g.reasons.joinToString("; "))
            row.takeIf { it.drop(1).any { cell -> cell.isNotBlank() } }?.map { it.ifBlank { "–" } }
        },
        empty = "No device carried association, probe, security or WPS details.",
    )

    /** Appendix C: the mission's key events in time order. */
    fun timelineTable(events: List<MonitorMission.ReplayEvent>, time: (Long) -> String): ReportTable {
        val shown = events.filter { it.event in TIMELINE_EVENTS }.sortedBy { it.atMs }
        return ReportTable(
            columns = listOf(ReportColumn("Time", 1.1f, CellKind.MONO), ReportColumn("Event", 1.3f), ReportColumn("Details", 3.6f)),
            rows = shown.take(MAX_TIMELINE_ROWS).map { listOf(time(it.atMs), it.event.replace('_', ' '), it.details.ifBlank { "–" }) },
            empty = "No mission events were logged.",
            note = if (shown.size > MAX_TIMELINE_ROWS) "First $MAX_TIMELINE_ROWS of ${shown.size} events; the mission log has all of them." else null,
        )
    }

    /** Numbered markers for the overview map, matching positionsTable's # column. */
    fun markers(targets: List<String>, estimates: Map<String, MonitorEstimate?>, names: Map<String, String>): List<MapMarker> =
        targets.mapIndexedNotNull { index, mac ->
            estimates[mac]?.let { MapMarker(index + 1, names[mac] ?: mac, it.lat, it.lng, it.uncertaintyM) }
        }

    /** The box the overview map has to show, or null with nothing to show. */
    fun bounds(route: List<Pair<Double, Double>>, markers: List<MapMarker>): Bounds? {
        val points = route + markers.map { it.lat to it.lng }
        if (points.isEmpty()) return null
        return Bounds(points.minOf { it.first }, points.maxOf { it.first }, points.minOf { it.second }, points.maxOf { it.second })
    }

    /** Every [step]-th route point plus the last, so a long walk stays a light polyline. */
    fun thinRoute(route: List<Pair<Double, Double>>, maxPoints: Int = 1500): List<Pair<Double, Double>> {
        if (route.size <= maxPoints) return route
        val step = (route.size + maxPoints - 1) / maxPoints
        return route.filterIndexed { i, _ -> i % step == 0 } + route.last()
    }
}

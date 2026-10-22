package com.whyfi.app.usbmon

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import com.whyfi.app.scan.MonitorUiState
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import org.json.JSONObject

/** One immutable local mission directory; a later capture always gets another. */
object MonitorMission {
    private val logLock = Any()
    data class Files(val directory: File, val pcap: File?, val report: File, val log: File)
    data class Stored(
        val directory: File,
        val report: File,
        val log: File,
        val pcap: File?,
        val uploaded: Boolean,
    ) {
        val complete get() = report.isFile && log.isFile
    }

    fun list(context: Context): List<Stored> {
        val root = File(context.getExternalFilesDir(null), "monitor-missions")
        return root.listFiles()?.filter { it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }
            ?.map { directory ->
                val pcap = File(directory, "capture.pcap").takeIf { it.isFile }
                Stored(directory, File(directory, "report.pdf"), File(directory, "mission.jsonl"),
                    pcap, File(directory, ".uploaded").isFile)
            }
            ?.sortedByDescending { it.log.lastModified() }
            .orEmpty()
    }

    fun markUploaded(mission: Stored) {
        File(mission.directory, ".uploaded").writeText(System.currentTimeMillis().toString())
    }

    fun create(context: Context, recordPcap: Boolean): Files {
        val directory = File(context.getExternalFilesDir(null), "monitor-missions/${UUID.randomUUID()}").apply { mkdirs() }
        return Files(directory, if (recordPcap) File(directory, "capture.pcap") else null, File(directory, "report.pdf"), File(directory, "mission.jsonl"))
    }

    /** Append-only so replay has the operator's actual sequence of actions. */
    fun append(files: Files, event: String, details: String = "") {
        synchronized(logLock) {
            files.log.appendText(JSONObject().put("at_ms", System.currentTimeMillis()).put("event", event)
                .put("details", details).toString() + "\n")
        }
    }

    fun appendSamples(files: Files, samples: List<MonitorGeoSample>) {
        if (samples.isEmpty()) return
        synchronized(logLock) {
            files.log.appendText(samples.joinToString("", "", "") { sample ->
                JSONObject().put("event", "geo_sample").put("at_ms", sample.atMs).put("mac", sample.mac)
                    .put("lat", sample.lat).put("lng", sample.lng).put("gps_accuracy_m", sample.gpsAccuracyM)
                    .put("rssi", sample.rssi).put("channel", sample.channel).toString() + "\n"
            })
        }
    }

    fun readSamples(log: File): List<MonitorGeoSample> = log.useLines { lines ->
        lines.mapNotNull { line ->
            runCatching {
                val row = JSONObject(line)
                if (row.optString("event") != "geo_sample") return@runCatching null
                MonitorGeoSample(row.getString("mac"), row.getLong("at_ms"), row.getDouble("lat"),
                    row.getDouble("lng"), row.getDouble("gps_accuracy_m"), row.getInt("rssi"), row.getInt("channel"))
            }.getOrNull()
        }.toList()
    }

    fun targets(log: File): Set<String> = log.useLines { lines ->
        lines.mapNotNull { line ->
            runCatching { JSONObject(line).takeIf { it.optString("event") == "mission_target" }
                ?.optString("details")?.takeIf(String::isNotBlank) }.getOrNull()
        }.toSet()
    }

    fun writeReport(context: Context, files: Files, state: MonitorUiState, favorites: Set<String>) {
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val ink = Color.rgb(24, 43, 51)
        val muted = Color.rgb(89, 109, 117)
        val teal = Color.rgb(0, 153, 139)
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun finishPage() {
            page?.let {
                paint.color = muted
                paint.typeface = Typeface.DEFAULT
                paint.textSize = 9f
                it.canvas.drawText("whyfi  /  local monitor mission", 36f, 816f, paint)
                it.canvas.drawText("$pageNumber", 550f, 816f, paint)
                pdf.finishPage(it)
            }
        }
        fun nextPage() {
            finishPage()
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            val canvas = page!!.canvas
            paint.color = ink
            canvas.drawRect(0f, 0f, 595f, 116f, paint)
            paint.color = teal
            canvas.drawRect(0f, 112f, 595f, 116f, paint)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 13f
            canvas.drawText("whyfi", 36f, 43f, paint)
            paint.color = Color.WHITE
            paint.textSize = 23f
            canvas.drawText("Monitor mission", 36f, 80f, paint)
            y = 145f
        }
        fun line(value: String, indent: Float = 0f, color: Int = ink) {
            if (page == null || y > 790f) nextPage()
            paint.color = color
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 10f
            val fit = paint.breakText(value, true, 523f - indent, null)
            val shown = if (fit < value.length) value.take((fit - 3).coerceAtLeast(0)) + "..." else value
            page!!.canvas.drawText(shown, 36f + indent, y, paint)
            y += 16f
        }
        fun section(title: String) {
            if (page == null || y > 730f) nextPage()
            y += 12f
            paint.color = teal
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 12f
            page!!.canvas.drawText(title, 36f, y, paint)
            y += 9f
            paint.color = Color.rgb(219, 231, 232)
            page!!.canvas.drawRect(36f, y, 559f, y + 1f, paint)
            y += 18f
        }
        val aps = state.devices.count { it.first.isAp }
        val clients = state.devices.count { it.first.isClient }
        val unassociated = state.devices.count { it.first.isClient && it.first.probeRequests > 0 && it.first.bssids.isEmpty() }
        nextPage()
        line("Mission ID  ${files.directory.name}", color = muted)
        line("Completed  ${DateFormat.getDateTimeInstance().format(Date())}", color = muted)
        section("Overview")
        line("${state.frames} frames     $aps access points     $clients clients     $unassociated probing without observed association")
        line("EAPOL-Key frames observed: ${state.eapolKeyFrames}")
        line("Key-frame count does not establish a complete WPA handshake or usable hash.", color = muted)
        line("Status: ${state.status}")
        line("Adapter: ${state.device.ifBlank { "unknown" }}")
        section("Evidence files")
        line("PCAP: ${files.pcap?.name ?: "not recorded"}  (${state.pcapBytes} bytes)" +
            if (state.pcapTruncated) " - 200 MB cap reached" else "")
        line("Mission log: ${files.log.name}")
        section("Channels / received frames")
        state.channelFrames.entries.sortedBy { it.key }.chunked(5).forEach { group ->
            line(group.joinToString("   ") { "ch ${it.key}: ${it.value}" })
        }
        section("Observed devices (${state.devices.size})")
        state.devices.sortedByDescending { it.first.lastMs }.forEach { (device, guess) ->
            line("${device.mac}  ${device.ssid.ifBlank { guess.vendor ?: guess.kind.label }}  ${device.bestRssi ?: "?"} dBm  ch ${device.channels.joinToString()}")
            line("${guess.kind.label}; frames ${device.frames}; probes ${device.probeRequests}; APs ${device.bssids.joinToString().ifBlank { "none observed" }}", 12f, muted)
            if (device.probed.isNotEmpty()) line("Probed: ${device.probed.joinToString().take(90)}", 12f, muted)
        }
        val geoSamples = readSamples(files.log)
        section("Favorite position estimates (${favorites.size})")
        if (favorites.isEmpty()) line("Star devices in Nearby during capture to include position estimates.", color = muted)
        favorites.sorted().forEach { mac ->
            if (y > 355f) nextPage()
            val device = state.devices.firstOrNull { it.first.mac == mac }?.first
            section("${device?.ssid?.takeIf { it.isNotBlank() } ?: "Device"}  $mac")
            val samples = geoSamples.filter { it.mac == mac }
            val estimate = MonitorLocalization.estimate(samples)
            if (estimate == null) {
                line("No GPS-tagged RSSI readings while this device was pinned.", color = muted)
                line("Keep it pinned, enable precise location, and walk to several positions.", color = muted)
            } else {
                line("Estimated position: %.6f, %.6f  |  uncertainty at least %.0f m".format(
                    estimate.lat, estimate.lng, estimate.uncertaintyM))
                line("${estimate.spatialGroups} independent places  |  %.0f m route span  |  ${estimate.band}".format(
                    estimate.receiverSpreadM), color = muted)
                if (estimate.provisional) line("Provisional estimate: geometry or sample count is weak.", color = muted)
                line("RSSI centroid baseline: %.6f, %.6f".format(estimate.baselineLat, estimate.baselineLng), color = muted)
                val map = RectF(36f, y + 6f, 559f, y + 306f)
                MonitorTileMap.draw(context, page!!.canvas, map, estimate, samples)
                y = map.bottom + 15f
                estimate.advice.forEach { line(it, color = muted) }
                line("Heat colors show relative model fit, not a calibrated probability.", color = muted)
            }
        }
        section("Interpretation")
        line("Device types and associations are inferences from sampled traffic.", color = muted)
        line("No observed association does not prove a client is unassociated.", color = muted)
        line("A device may move; receiver position and RSSI do not prove its physical location.", color = muted)
        finishPage()
        val temporary = File(files.directory, "report.pdf.tmp")
        try {
            temporary.outputStream().use { pdf.writeTo(it) }
            if (!temporary.renameTo(files.report)) throw IOException("Could not save PDF report")
        } finally {
            pdf.close()
            temporary.delete()
        }
    }
}

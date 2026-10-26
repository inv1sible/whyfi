package com.whyfi.app.usbmon

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import android.os.SystemClock
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
    data class ReplayEvent(val atMs: Long, val event: String, val details: String)
    data class DeviceSnapshot(val atMs: Long, val clockMs: Long, val tsv: String)

    fun displayTarget(mac: String, name: String?): String =
        "${name?.trim()?.take(24)?.takeIf { it.isNotBlank() } ?: "Device"} · ${mac.uppercase()}"

    fun deviceName(device: SniffedDevices.Device, guess: SniffedDevices.Guess): String =
        device.ssid.takeIf { device.isAp && it.isNotBlank() }
            ?: device.wpsName.takeIf { it.isNotBlank() }
            ?: guess.vendor?.takeIf { it.isNotBlank() }
            ?: guess.kind.label

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

    /** Deletes only one UUID-named local mission; an active capture is never removable. */
    fun delete(context: Context, mission: Stored, activeLogPath: String?): Boolean =
        deleteDirectory(File(context.getExternalFilesDir(null), "monitor-missions"), mission.directory, activeLogPath)

    internal fun deleteDirectory(root: File, directory: File, activeLogPath: String?): Boolean =
        runCatching {
            val resolved = directory.canonicalFile
            if (resolved.parentFile != root.canonicalFile ||
                runCatching { UUID.fromString(resolved.name) }.isFailure ||
                activeLogPath?.let { File(it).canonicalFile == File(resolved, "mission.jsonl") } == true ||
                !resolved.isDirectory) false
            else resolved.deleteRecursively()
        }.getOrDefault(false)

    fun create(context: Context, recordPcap: Boolean): Files {
        val directory = File(context.getExternalFilesDir(null), "monitor-missions/${UUID.randomUUID()}").apply { mkdirs() }
        return Files(directory, if (recordPcap) File(directory, "capture.pcap") else null, File(directory, "report.pdf"), File(directory, "mission.jsonl"))
    }

    /** Resume only the app's own UUID mission directory; never overwrite a completed upload. */
    fun resume(context: Context, id: String?, recordPcap: Boolean): Files? {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        val directory = File(context.getExternalFilesDir(null), "monitor-missions/$uuid")
        if (!directory.isDirectory || !File(directory, "mission.jsonl").isFile ||
            File(directory, ".uploaded").exists()) return null
        val pcap = File(directory, "capture.pcap").takeIf { recordPcap || it.isFile }
        return Files(directory, pcap, File(directory, "report.pdf"), File(directory, "mission.jsonl"))
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
                    .put("rssi", sample.rssi).put("channel", sample.channel)
                    .put("location_source", sample.locationSource).put("fix_age_ms", sample.fixAgeMs)
                    .put("speed_mps", sample.speedMps).toString() + "\n"
            })
        }
    }

    fun appendReceiverFixes(files: Files, fixes: List<MonitorReceiverFix>) {
        if (fixes.isEmpty()) return
        synchronized(logLock) {
            files.log.appendText(fixes.joinToString("") { fix ->
                JSONObject().put("event", "receiver_fix").put("at_ms", fix.atMs)
                    .put("boot_ms", fix.bootMs).put("provider", fix.provider)
                    .put("lat", fix.lat).put("lng", fix.lng).put("accuracy_m", fix.accuracyM)
                    .put("speed_mps", fix.speedMps).toString() + "\n"
            })
        }
    }

    /** Persist a readable name alongside the MAC so saved missions keep their labels. */
    fun appendIdentity(files: Files, mac: String, name: String) {
        synchronized(logLock) {
            files.log.appendText(JSONObject().put("at_ms", System.currentTimeMillis())
                .put("event", "device_identity").put("mac", mac).put("name", name).toString() + "\n")
        }
    }

    /** Periodic full metadata snapshots make all device tabs replayable without PCAP decoding. */
    fun appendDeviceSnapshot(files: Files, tsv: String) {
        synchronized(logLock) {
            files.log.appendText(JSONObject().put("at_ms", System.currentTimeMillis())
                .put("clock_ms", SystemClock.elapsedRealtime())
                .put("event", "devices_snapshot").put("tsv", tsv).toString() + "\n")
        }
    }

    fun readDeviceSnapshots(log: File): List<DeviceSnapshot> = log.useLines { lines ->
        lines.mapNotNull { line ->
            runCatching {
                val row = JSONObject(line)
                if (row.optString("event") != "devices_snapshot") return@runCatching null
                DeviceSnapshot(row.optLong("at_ms"), row.optLong("clock_ms"), row.optString("tsv"))
                    .takeIf { it.atMs > 0 }
            }.getOrNull()
        }.sortedBy { it.atMs }.toList()
    }

    fun readInitialFavorites(log: File): Set<String> = log.useLines { lines ->
        val initial = mutableSetOf<String>()
        var started = false
        for (line in lines) {
            val row = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when (row.optString("event")) {
                "monitor_started" -> started = true
                "mission_target" -> if (started) initial += row.optString("details")
                else -> if (started) break
            }
        }
        initial.filter(String::isNotBlank).toSet()
    }

    /** Restore the operator's most recent pin selection when reopening a local mission. */
    fun currentFavorites(log: File): Set<String> =
        favoritesAfter(readInitialFavorites(log), readReplayEvents(log), Long.MAX_VALUE)

    internal fun favoritesAfter(initial: Set<String>, events: List<ReplayEvent>, cutoffMs: Long): Set<String> {
        val selected = initial.toMutableSet()
        events.filter { it.atMs <= cutoffMs }.forEach { event ->
            when (event.event) {
                "mission_resumed" -> selected.clear()
                "mission_resumed_target" -> selected += event.details
                "favorite" -> {
                    val mac = event.details.substringBeforeLast(':')
                    if (event.details.endsWith(":true")) selected += mac
                    else if (event.details.endsWith(":false")) selected -= mac
                }
            }
        }
        return selected.filter(String::isNotBlank).toSet()
    }

    /** Bounded preview; metadata snapshots can make a log too large for readLines(). */
    fun previewLog(log: File): String = log.useLines { lines ->
        val tail = ArrayDeque<String>()
        lines.forEach { line ->
            if (!line.contains("\"devices_snapshot\"")) {
                if (tail.size == 8) tail.removeFirst()
                tail.addLast(line.take(400))
            }
        }
        tail.joinToString("\n")
    }

    fun readLabels(log: File): Map<String, String> = log.useLines { lines ->
        lines.mapNotNull { line ->
            runCatching {
                val row = JSONObject(line)
                if (row.optString("event") != "device_identity") return@runCatching null
                val mac = row.optString("mac")
                val name = row.optString("name")
                if (mac.isBlank() || name.isBlank()) null else mac to name
            }.getOrNull()
        }.toMap()
    }

    fun readReplayEvents(log: File): List<ReplayEvent> = log.useLines { lines ->
        lines.mapNotNull { line ->
            runCatching {
                val row = JSONObject(line)
                val event = row.optString("event")
                if (event == "geo_sample" || event == "receiver_fix" || event == "device_identity" || event == "mission_target" ||
                    event == "devices_snapshot")
                    return@runCatching null
                ReplayEvent(row.optLong("at_ms"), event, row.optString("details"))
                    .takeIf { it.atMs > 0 && it.event.isNotBlank() }
            }.getOrNull()
        }.sortedBy { it.atMs }.toList()
    }

    fun readReceiverFixes(log: File): List<MonitorReceiverFix> = log.useLines { lines ->
        lines.mapNotNull { line ->
            runCatching {
                val row = JSONObject(line)
                if (row.optString("event") != "receiver_fix") return@runCatching null
                MonitorReceiverFix(row.getLong("at_ms"), row.getLong("boot_ms"),
                    row.getString("provider"), row.getDouble("lat"), row.getDouble("lng"),
                    row.getDouble("accuracy_m"), row.optDouble("speed_mps", 15.0))
            }.getOrNull()
        }.sortedBy { it.atMs }.toList()
    }

    fun readSamples(log: File): List<MonitorGeoSample> {
        val saved = log.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    val row = JSONObject(line)
                    if (row.optString("event") != "geo_sample") return@runCatching null
                    MonitorGeoSample(row.getString("mac"), row.getLong("at_ms"), row.getDouble("lat"),
                        row.getDouble("lng"), row.getDouble("gps_accuracy_m"), row.getInt("rssi"), row.getInt("channel"),
                        row.optString("location_source"), row.optLong("fix_age_ms", 0),
                        row.optDouble("speed_mps", 0.0))
                }.getOrNull()
            }.toList()
        }
        val fixes = readReceiverFixes(log)
        if (fixes.isEmpty()) return saved
        return MonitorSampleReconciler.reconcile(saved, fixes, readDeviceSnapshots(log),
            readInitialFavorites(log), readReplayEvents(log))
    }

    /** Derived, not written over the raw evidence: AP observations need no favorite flag. */
    fun readAccessPointSamples(log: File): List<MonitorGeoSample> =
        MonitorSampleReconciler.accessPoints(readReceiverFixes(log), readDeviceSnapshots(log))

    fun targets(log: File): Set<String> = log.useLines { lines ->
        lines.mapNotNull { line ->
            runCatching {
                val row = JSONObject(line)
                when (row.optString("event")) {
                    "mission_target", "mission_resumed_target" -> row.optString("details")
                    "geo_sample" -> row.optString("mac")
                    "favorite" -> row.optString("details").takeIf { it.endsWith(":true") }
                        ?.substringBeforeLast(':')
                    else -> null
                }?.takeIf(String::isNotBlank)
            }.getOrNull()
        }.toSet()
    }

    fun writeReport(context: Context, files: Files, state: MonitorUiState, favorites: Set<String>) {
        val geoSamples = readSamples(files.log)
        val apSamples = readAccessPointSamples(files.log)
        val receiverFixes = readReceiverFixes(files.log)
        val samplesByMac = geoSamples.groupBy { it.mac }
        val apSamplesByMac = apSamples.groupBy { it.mac }
        val targetMacs = (favorites + samplesByMac.keys).sorted()
        val estimates = targetMacs.associateWith { MonitorLocalization.estimate(samplesByMac[it].orEmpty()) }
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
        val savedDevices = readDeviceSnapshots(files.log).asSequence()
            .flatMap { SniffedDevices.parse(it.tsv).asSequence() }
            .groupBy { it.mac }.values.map { sightings -> sightings.maxBy { it.lastMs } }
        val allDevices = savedDevices.ifEmpty { state.devices.map { it.first } }
        val currentGuesses = state.devices.associate { it.first.mac to it.second }
        val reportDevices = allDevices.map { device ->
            device to (currentGuesses[device.mac] ?: SniffedDevices.guess(device) { null })
        }
        val aps = allDevices.count { it.isAp }
        val clients = allDevices.count { it.isClient }
        val unassociated = allDevices.count { it.isClient && it.probeRequests > 0 && it.bssids.isEmpty() }
        val earlierFrames = readReplayEvents(files.log).filter { it.event == "monitor_finished" }
            .sumOf { it.details.substringAfter("frames=", "0").substringBefore(' ').toLongOrNull() ?: 0L }
        nextPage()
        line("Mission ID  ${files.directory.name}", color = muted)
        line("Completed  ${DateFormat.getDateTimeInstance().format(Date())}", color = muted)
        section("Overview")
        line("${earlierFrames + state.frames} frames across captures")
        line("$aps access points     $clients clients     $unassociated probing without observed association")
        line("EAPOL-Key frames in latest capture: ${state.eapolKeyFrames}")
        line("Key-frame count does not establish a complete WPA handshake or usable hash.", color = muted)
        line("Status: ${state.status}")
        line("Adapter: ${state.device.ifBlank { "unknown" }}")
        section("Pinned device positions (${targetMacs.size})")
        line("${geoSamples.size} location-tagged radio readings · GPS, network or fused source", color = muted)
        line("${receiverFixes.size} Android receiver fixes recorded for the mission route", color = muted)
        if (receiverFixes.isEmpty() && geoSamples.isEmpty())
            line("No Android fix was recorded; PCAP alone cannot recover the phone's route.", color = muted)
        if (targetMacs.isEmpty()) line("Star a device in Nearby to collect position readings.", color = muted)
        targetMacs.forEach { mac ->
            val estimate = estimates[mac]
            if (estimate == null) line("$mac  No matching location fix; keep it starred and enable Location.", color = muted)
            else line("$mac  %.6f, %.6f  uncertainty >=%.0f m%s".format(
                estimate.lat, estimate.lng, estimate.uncertaintyM, if (estimate.provisional) " provisional" else ""))
        }
        section("Access point observations (${apSamplesByMac.size})")
        line("${apSamples.size} AP signal readings matched to Android receiver fixes, including non-favorites.", color = muted)
        line("Coordinates below are receiver-derived hints; one sighting cannot locate an AP.", color = muted)
        apSamplesByMac.entries.sortedByDescending { it.value.size }.forEach { (mac, samples) ->
            val estimate = MonitorLocalization.estimate(samples) ?: return@forEach
            val name = allDevices.firstOrNull { it.mac == mac }?.ssid
                ?.takeIf { it.isNotBlank() }?.take(18) ?: "AP"
            line("$name $mac  ${samples.size} readings  %.5f, %.5f  >=%.0f m%s".format(
                estimate.lat, estimate.lng, estimate.uncertaintyM,
                if (estimate.provisional) " receiver hint" else " estimate"))
        }
        section("Evidence files")
        line("PCAP: ${files.pcap?.name ?: "not recorded"}  (${files.pcap?.length() ?: 0} bytes)" +
            if (state.pcapTruncated) " - 200 MB cap reached" else "")
        line("Mission log: ${files.log.name}")
        section("Channels / received frames in latest capture")
        state.channelFrames.entries.sortedBy { it.key }.chunked(5).forEach { group ->
            line(group.joinToString("   ") { "ch ${it.key}: ${it.value}" })
        }
        section("Observed devices (${reportDevices.size})")
        reportDevices.sortedByDescending { it.first.lastMs }.forEach { (device, guess) ->
            line("${device.mac}  ${device.ssid.ifBlank { guess.vendor ?: guess.kind.label }}  ${device.bestRssi ?: "?"} dBm  ch ${device.channels.joinToString()}")
            line("${guess.kind.label}; frames ${device.frames}; probes ${device.probeRequests}; APs ${device.bssids.joinToString().ifBlank { "none observed" }}", 12f, muted)
            if (device.probed.isNotEmpty()) line("Probed: ${device.probed.joinToString().take(90)}", 12f, muted)
            device.securityDetails.split(" · ").filter { it.isNotBlank() }.forEach { line("Security: $it", 12f, muted) }
            device.wpsDetails.split(" · ").filter { it.isNotBlank() }.forEach { line("WPS: $it", 12f, muted) }
        }
        section("Position maps (${targetMacs.size})")
        targetMacs.forEach { mac ->
            if (y > 355f) nextPage()
            val device = allDevices.firstOrNull { it.mac == mac }
            section("${device?.ssid?.takeIf { it.isNotBlank() } ?: "Device"}  $mac")
            val samples = samplesByMac[mac].orEmpty()
            val estimate = estimates[mac]
            if (estimate == null) {
                line("No location-tagged RSSI readings while this device was pinned.", color = muted)
                line("Keep it pinned, enable Location, and walk to several positions.", color = muted)
            } else {
                line("Estimated position: %.6f, %.6f  |  uncertainty at least %.0f m".format(
                    estimate.lat, estimate.lng, estimate.uncertaintyM))
                line("${estimate.spatialGroups} independent places  |  %.0f m route span  |  ${estimate.band}".format(
                    estimate.receiverSpreadM), color = muted)
                line("Location sources: ${samples.map { it.locationSource.ifBlank { "unknown" } }.distinct().joinToString()}", color = muted)
                if (estimate.provisional) line("Provisional estimate: geometry or sample count is weak.", color = muted)
                line("RSSI centroid baseline: %.6f, %.6f".format(estimate.baselineLat, estimate.baselineLng), color = muted)
                val map = RectF(36f, y + 6f, 559f, y + 306f)
                MonitorTileMap.draw(context, page!!.canvas, map, estimate, samples)
                y = map.bottom + 15f
                estimate.advice.forEach { line(it, color = muted) }
                line("Cones run from the estimated device position toward sampled receiver positions.", color = muted)
                line("Green to orange/red shows relative RSSI; cone direction is not an antenna bearing.", color = muted)
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

package com.whyfi.app.usbmon

import android.content.Context
import android.os.SystemClock
import com.whyfi.app.report.ReportPdf
import com.whyfi.app.scan.MonitorUiState
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import com.google.gson.Gson

/** Local mission evidence; capture passes append until the operator resets or branches. */
object MonitorMission {
    private val logLock = Any()
    data class Files(val directory: File, val pcap: File?, val report: File, val log: File)
    data class Stored(
        val directory: File,
        val report: File,
        val log: File,
        val pcap: File?,
        val uploaded: Boolean,
        val name: String = readName(directory),
        val dateMs: Long = readDate(directory, log),
        // Header I/O happens once on the loader dispatcher, never during card composition.
        val modifiedMs: Long = log.lastModified(),
        val logBytes: Long = log.length(),
        val reportBytes: Long? = report.takeIf { it.isFile }?.length(),
        val pcapBytes: Long? = pcap?.length(),
        val captured: Boolean = log.isFile && (File(directory, "capture-summary.json").isFile || reportBytes != null),
    ) {
        val complete get() = report.isFile && log.isFile
        val title get() = "$name · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(Date(dateMs))}"
    }
    data class CaptureSummary(val device: String = "", val frames: Long = 0, val eapol: Long = 0,
                              val channels: Map<Int, Long> = emptyMap(), val pcapTruncated: Boolean = false,
                              val status: String = "Saved")
    data class ReplayEvent(val atMs: Long, val event: String, val details: String)
    data class DeviceSnapshot(val atMs: Long, val clockMs: Long, val tsv: String)

    private fun readName(directory: File): String = runCatching {
        File(directory, "mission-name.txt").takeIf { it.isFile }?.readText()?.trim()?.take(80)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Mission"

    private fun readDate(directory: File, log: File): Long = runCatching {
        File(directory, "mission-date.txt").readText().trim().toLong()
    }.getOrNull() ?: log.lastModified().takeIf { it > 0 } ?: directory.lastModified()

    fun rename(mission: Stored, name: String) {
        val value = name.trim().replace('\n', ' ').replace('\r', ' ').take(80)
        require(value.isNotBlank()) { "Enter a mission name" }
        File(mission.directory, "mission-date.txt").takeUnless { it.isFile }?.writeText(mission.dateMs.toString())
        File(mission.directory, "mission-name.txt").writeText(value)
        append(Files(mission.directory, mission.pcap, mission.report, mission.log), "mission_named", value)
    }

    fun displayTarget(mac: String, name: String?): String =
        "${name?.trim()?.take(24)?.takeIf { it.isNotBlank() } ?: "Device"} · ${mac.uppercase()}"

    fun deviceName(device: SniffedDevices.Device, guess: SniffedDevices.Guess): String =
        SniffedDevices.displayName(device, guess)

    fun list(context: Context, onBatch: ((List<Stored>) -> Unit)? = null): List<Stored> =
        listIn(File(context.getExternalFilesDir(null), "monitor-missions"), onBatch).also {
            MonitorMissionHeaders.write(File(context.cacheDir, "monitor-mission-headers.json"), it)
        }

    internal fun cachedList(context: Context): List<Stored> = MonitorMissionHeaders.read(
        File(context.cacheDir, "monitor-mission-headers.json"), File(context.getExternalFilesDir(null), "monitor-missions"))

    internal fun listIn(root: File, onBatch: ((List<Stored>) -> Unit)? = null): List<Stored> {
        val loaded = mutableListOf<Stored>()
        return root.listFiles()?.filter { it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }
            ?.map { directory ->
                val pcap = File(directory, "capture.pcap").takeIf { it.isFile }
                Stored(directory, File(directory, "report.pdf"), File(directory, "mission.jsonl"),
                    pcap, File(directory, ".uploaded").isFile).also {
                    loaded += it
                    if (loaded.size == 1 || loaded.size % 4 == 0) onBatch?.invoke(loaded.sortedByDescending { row -> row.modifiedMs })
                }
            }
            ?.sortedByDescending { it.modifiedMs }
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
        File(directory, "mission-date.txt").writeText(System.currentTimeMillis().toString())
        return Files(directory, if (recordPcap) File(directory, "capture.pcap") else null, File(directory, "report.pdf"), File(directory, "mission.jsonl"))
    }

    /** Copy recorded evidence into a new upload identity; the source remains untouched. */
    fun continueAsNew(context: Context, source: Stored): Files {
        val files = copyAsNewIn(File(context.getExternalFilesDir(null), "monitor-missions"), source)
        try {
            append(files, "mission_continued_from", source.directory.name)
            return files
        } catch (error: Exception) {
            files.directory.deleteRecursively()
            throw error
        }
    }

    internal fun copyAsNewIn(root: File, source: Stored): Files {
        val sourceDirectory = source.directory.canonicalFile
        require(sourceDirectory.parentFile == root.canonicalFile &&
            runCatching { UUID.fromString(sourceDirectory.name) }.isSuccess &&
            source.log.canonicalFile == File(sourceDirectory, "mission.jsonl") && source.log.isFile &&
            (source.pcap == null || source.pcap.canonicalFile == File(sourceDirectory, "capture.pcap"))) {
            "Invalid source mission"
        }
        val directory = File(root, UUID.randomUUID().toString())
        check(directory.mkdir()) { "Could not create continued mission" }
        try {
            val log = source.log.copyTo(File(directory, "mission.jsonl"))
            val pcap = source.pcap?.takeIf { it.isFile }?.copyTo(File(directory, "capture.pcap"))
            File(directory, "mission-name.txt").writeText(source.name)
            File(directory, "mission-date.txt").writeText(System.currentTimeMillis().toString())
            File(source.directory, "capture-summary.json").takeIf { it.isFile }
                ?.copyTo(File(directory, "capture-summary.json"))
            return Files(directory, pcap, File(directory, "report.pdf"), log)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
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
            files.log.appendText(Gson().toJson(mapOf("at_ms" to System.currentTimeMillis(), "event" to event,
                "details" to details)) + "\n")
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

    /** AP visibility is an operator choice; evidence and recorded snapshots stay unchanged. */
    fun currentHiddenAps(log: File): Set<String> =
        hiddenApsAfter(readReplayEvents(log))

    internal fun hiddenApsAfter(events: List<ReplayEvent>): Set<String> {
        val hidden = mutableSetOf<String>()
        events.forEach { event ->
            if (event.event != "ap_hidden") return@forEach
            val mac = event.details.substringBeforeLast(':')
            if (event.details.endsWith(":true")) hidden += mac
            else if (event.details.endsWith(":false")) hidden -= mac
        }
        return hidden.filter(String::isNotBlank).toSet()
    }

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
        var total = 0
        var snapshots = 0
        lines.forEach { line ->
            total++
            if (!line.contains("\"devices_snapshot\"")) {
                if (tail.size == 40) tail.removeFirst()
                tail.addLast(line.take(400))
            } else snapshots++
        }
        "Preview: last ${tail.size} events of $total log entries. $snapshots device snapshots omitted. " +
            "Long lines are shortened. Export full log for all recorded data.\n\n" + tail.joinToString("\n")
    }

    /** Stop persists a small statistics record; report rendering happens only on explicit request. */
    fun saveCaptureSummary(files: Files, state: MonitorUiState) {
        val summary = CaptureSummary(state.device, state.frames, state.eapolKeyFrames, state.channelFrames,
            state.pcapTruncated, state.status)
        append(files, "capture_summary", Gson().toJson(summary))
        val temporary = File(files.directory, "capture-summary.json.tmp")
        temporary.writeText(Gson().toJson(summary))
        check(temporary.renameTo(File(files.directory, "capture-summary.json"))) { "Could not save capture statistics" }
    }

    fun ensureReport(context: Context, mission: Stored, force: Boolean = false): File {
        val nameFile = File(mission.directory, "mission-name.txt")
        if (!force && mission.report.isFile && mission.report.lastModified() >= maxOf(mission.log.lastModified(), nameFile.lastModified()))
            return mission.report
        val summary = runCatching {
            Gson().fromJson(File(mission.directory, "capture-summary.json").readText(), CaptureSummary::class.java)
        }.getOrNull() ?: CaptureSummary()
        val state = MonitorUiState(status = summary.status, device = summary.device, frames = summary.frames,
            eapolKeyFrames = summary.eapol, channelFrames = summary.channels, pcapTruncated = summary.pcapTruncated)
        writeReport(context, Files(mission.directory, mission.pcap, mission.report, mission.log), state,
            MonitorEvidence.read(mission.log))
        return mission.report
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

    /**
     * The mission PDF: cover with key figures and contents, an overview map
     * of the route and pinned devices, tables (positions, observations,
     * devices, channels), one map per pinned device, then appendices with
     * per-device details, evidence files, the event timeline and method notes.
     * Table contents come from MonitorReportContent; drawing from report/ReportPdf.
     */
    private fun writeReport(context: Context, files: Files, state: MonitorUiState, evidence: MonitorEvidence) {
        val geoSamples = evidence.samples
        val deviceSamples = evidence.radioSamples
        val receiverFixes = evidence.receiverFixes
        val samplesByMac = geoSamples.groupBy { it.mac }
        val deviceSamplesByMac = deviceSamples.groupBy { it.mac }
        val targetMacs = evidence.targets.sorted()
        val estimates = targetMacs.associateWith { MonitorLocalization.estimate(samplesByMac[it].orEmpty()) }

        val savedDevices = evidence.inventoryAt()
        val allDevices = savedDevices.ifEmpty { state.devices.map { it.first } }
        val currentGuesses = state.devices.associate { it.first.mac to it.second }
        val vendors = runCatching { context.assets.open("oui.tsv").use { OuiTable.read(it) } }.getOrNull()
        val reportDevices = allDevices.map { device ->
            device to (currentGuesses[device.mac] ?: SniffedDevices.guess(device) { vendors?.vendorOf(it) })
        }
        val names = reportDevices.associate { (device, guess) -> device.mac to SniffedDevices.displayName(device, guess) } +
            evidence.labels.filterValues { it.isNotBlank() }
        val aps = allDevices.count { it.isAp }
        val clients = allDevices.count { it.isClient }
        val unassociated = allDevices.count { it.isClient && it.probeRequests > 0 && it.bssids.isEmpty() }
        val earlierFrames = evidence.events.filter { it.event == "monitor_finished" }
            .sumOf { it.details.substringAfter("frames=", "0").substringBefore(' ').toLongOrNull() ?: 0L }
        val missionName = readName(files.directory)
        val dateTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val generated = DateFormat.getDateTimeInstance().format(Date())
        val route = receiverFixes.sortedBy { it.atMs }.map { it.lat to it.lng }
        val markers = MonitorReportContent.markers(targetMacs, estimates, names)
        val timeFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM)

        val doc = ReportPdf("Monitor mission report", missionName, "whyfi · local monitor mission · generated $generated")
        try {
            // Cover
            doc.keyValues(listOf(
                "Mission" to missionName,
                "Recorded" to dateTime.format(Date(readDate(files.directory, files.log))),
                "Mission ID" to files.directory.name,
                "Adapter" to state.device.ifBlank { "unknown" },
                "Status" to state.status,
                "Report generated" to generated,
            ))
            doc.statTiles(MonitorReportContent.stats(
                frames = earlierFrames.takeIf { it > 0 } ?: state.frames,
                aps = aps, clients = clients, probingOnly = unassociated,
                eapolKeyFrames = state.eapolKeyFrames, pinned = targetMacs.size,
            ))
            doc.paragraph("A count of EAPOL-Key frames does not establish a complete WPA handshake or a usable hash.", muted = true, size = 8.5f)
            doc.subheading("Contents")
            doc.bullets(listOf(
                "1  Overview map", "2  Pinned device positions", "3  Device observations",
                "4  Observed devices", "5  Channels", "6  Position maps",
                "Appendix A  Device details", "Appendix B  Evidence files",
                "Appendix C  Mission timeline", "Appendix D  Method and interpretation",
            ), muted = false)

            doc.heading("1", "Overview map")
            if (route.isEmpty() && markers.isEmpty()) {
                doc.paragraph("No Android location fix was recorded, so there is no route to draw. " +
                    "A PCAP alone cannot recover the phone's route.", muted = true)
            } else {
                doc.figure(330f, "Phone route from ${receiverFixes.size} receiver fixes and ${markers.size} pinned-device " +
                    "estimates, numbered as in section 2. Rings show each estimate's uncertainty.") { canvas, area ->
                    MonitorTileMap.drawOverview(context, canvas, area, route, markers)
                }
            }

            doc.heading("2", "Pinned device positions")
            doc.paragraph("${geoSamples.size} location-tagged radio readings (GPS, network or fused source) for ${targetMacs.size} " +
                "pinned devices.", muted = true)
            doc.table(MonitorReportContent.positionsTable(targetMacs, estimates, names))

            doc.heading("3", "Device observations")
            doc.paragraph("${deviceSamples.size} access point and client readings matched to phone location fixes, " +
                "including devices that were not pinned.", muted = true)
            doc.table(MonitorReportContent.observationsTable(deviceSamplesByMac, names))

            doc.heading("4", "Observed devices")
            val seen = if (savedDevices.isNotEmpty()) MonitorSeenClock.fromSnapshots(evidence.parsedSnapshots)
                else MonitorSeenClock.spans(allDevices, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            val seenDays = seen.values.flatMap { listOf(it.firstMs, it.lastMs) }
                .map { it / 86_400_000 }.distinct().size
            val seenFormat = if (seenDays > 1) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) else timeFormat
            val seenDay = seen.values.firstOrNull()?.lastMs?.takeIf { seenDays == 1 }
            doc.paragraph("Most recently heard first; first and last seen in the phone's local time" +
                (seenDay?.let { " on " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "") + ".",
                muted = true)
            doc.table(MonitorReportContent.devicesTable(reportDevices, seen) { seenFormat.format(Date(it)) })

            doc.heading("5", "Channels")
            doc.paragraph("Frames received per channel in the latest capture.", muted = true)
            doc.table(MonitorReportContent.channelsTable(state.channelFrames))

            doc.heading("6", "Position maps")
            if (targetMacs.isEmpty()) doc.paragraph("No device was pinned.", muted = true)
            targetMacs.forEachIndexed { index, mac ->
                val samples = samplesByMac[mac].orEmpty()
                val estimate = estimates[mac]
                doc.ensureSpace(if (estimate == null) 80f else 430f)
                doc.subheading("${index + 1}  ${names[mac] ?: "Device"}  ·  $mac")
                if (estimate == null) {
                    doc.paragraph("No location-tagged RSSI readings while this device was pinned. " +
                        "Keep it pinned, enable Location, and walk to several positions.", muted = true)
                    return@forEachIndexed
                }
                doc.keyValues(listOf(
                    "Estimated position" to "%.6f, %.6f".format(Locale.US, estimate.lat, estimate.lng),
                    "Uncertainty" to "at least %.0f m".format(Locale.US, estimate.uncertaintyM) +
                        if (estimate.provisional) " · provisional: geometry or sample count is weak" else "",
                    "Geometry" to "${estimate.spatialGroups} independent places · %.0f m route span · ${estimate.band}"
                        .format(Locale.US, estimate.receiverSpreadM),
                    "Location sources" to samples.map { it.locationSource.ifBlank { "unknown" } }.distinct().joinToString(),
                    "RSSI centroid baseline" to "%.6f, %.6f".format(Locale.US, estimate.baselineLat, estimate.baselineLng),
                ), labelWidth = 130f)
                doc.figure(300f, "Cones run from the estimate toward sampled receiver positions; green to orange/red is " +
                    "relative RSSI. Cone direction is not an antenna bearing.") { canvas, area ->
                    MonitorTileMap.draw(context, canvas, area, estimate, samples)
                }
                if (estimate.advice.isNotEmpty()) doc.bullets(estimate.advice)
            }

            doc.pageBreak()
            doc.heading("A", "Appendix A · Device details")
            doc.paragraph("Associations, probed network names, security and WPS fields, and why each device type was guessed.", muted = true)
            doc.table(MonitorReportContent.deviceDetailsTable(reportDevices))

            doc.heading("B", "Appendix B · Evidence files")
            doc.keyValues(listOf(
                "PCAP" to (files.pcap?.let { "${it.name} · ${MonitorReportContent.number(it.length())} bytes" } ?: "not recorded") +
                    if (state.pcapTruncated) " · 200 MB cap reached" else "",
                "Mission log" to "${files.log.name} · ${MonitorReportContent.number(files.log.length())} bytes",
                "Receiver fixes" to MonitorReportContent.number(receiverFixes.size.toLong()),
                "Location-tagged readings" to MonitorReportContent.number(geoSamples.size.toLong()),
                "Device snapshots" to MonitorReportContent.number(evidence.snapshots.size.toLong()),
            ), labelWidth = 140f)

            doc.heading("C", "Appendix C · Mission timeline")
            doc.table(MonitorReportContent.timelineTable(evidence.events) { timeFormat.format(Date(it)) })

            doc.heading("D", "Appendix D · Method and interpretation")
            doc.bullets(listOf(
                "Device types and associations are inferences from sampled traffic.",
                "No observed association does not prove a client is unassociated.",
                "A device may move; receiver position and RSSI do not prove its physical location.",
                "Positions use a fixed path-loss slope with a bounded per-device transmit-power offset and Huber loss; " +
                    "readings at one place are grouped so a long stop does not dominate.",
                "The uncertainty is a lower bound from the fit, not a calibrated probability.",
                "Map tiles © OpenStreetMap contributors. Tiles are cached on the phone for seven days.",
            ), muted = false)

            val temporary = File(files.directory, "report.pdf.tmp")
            try {
                temporary.outputStream().use { doc.writeTo(it) }
                if (!temporary.renameTo(files.report)) throw IOException("Could not save PDF report")
            } finally {
                temporary.delete()
            }
        } finally {
            doc.close()
        }
    }
}

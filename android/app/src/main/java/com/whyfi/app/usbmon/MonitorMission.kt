package com.whyfi.app.usbmon

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.whyfi.app.scan.MonitorUiState
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import org.json.JSONObject

/** One immutable local mission directory; a later capture always gets another. */
object MonitorMission {
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
        files.log.appendText(JSONObject().put("at_ms", System.currentTimeMillis()).put("event", event)
            .put("details", details).toString() + "\n")
    }

    fun writeReport(files: Files, state: MonitorUiState) {
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun nextPage() {
            page?.let(pdf::finishPage)
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            y = 36f
            page!!.canvas.drawText("whyfi monitor mission · page $pageNumber", 36f, y, paint)
            y += 22f
        }
        fun line(value: String) {
            if (page == null || y > 800f) nextPage()
            page!!.canvas.drawText(value.take(100), 36f, y, paint)
            y += 16f
        }
        line("Mission: ${files.directory.name}")
        line("Completed: ${DateFormat.getDateTimeInstance().format(Date())}")
        line("Adapter: ${state.device.ifBlank { "unknown" }}")
        line("Status: ${state.status}   Frames: ${state.frames}")
        line("PCAP: ${files.pcap?.name ?: "not recorded"}  ${state.pcapBytes} bytes" +
            if (state.pcapTruncated) " (200 MB cap reached)" else "")
        line("Mission log: ${files.log.name}")
        line("Channels and received frame counts:")
        state.channelFrames.entries.sortedBy { it.key }.chunked(5).forEach { group ->
            line(group.joinToString("   ") { "ch ${it.key}: ${it.value}" })
        }
        val aps = state.devices.count { it.first.isAp }
        val clients = state.devices.count { it.first.isClient }
        val unassociated = state.devices.count { it.first.isClient && it.first.probeRequests > 0 && it.first.bssids.isEmpty() }
        line("Devices: ${state.devices.size}  APs: $aps  clients: $clients  probing without observed association: $unassociated")
        line("")
        state.devices.sortedByDescending { it.first.lastMs }.forEach { (device, guess) ->
            line("${device.mac}  ${device.ssid.ifBlank { guess.vendor ?: guess.kind.label }}  ${device.bestRssi ?: "?"} dBm  ch ${device.channels.joinToString()}")
            line("  ${guess.kind.label}; frames ${device.frames}; probes ${device.probeRequests}; APs ${device.bssids.joinToString().ifBlank { "none observed" }}")
            if (device.probed.isNotEmpty()) line("  Probed: ${device.probed.joinToString().take(90)}")
        }
        line("")
        line("Device types and associations are inferences from sampled traffic.")
        line("No observed association does not prove a client is unassociated.")
        page?.let(pdf::finishPage)
        files.report.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }
}

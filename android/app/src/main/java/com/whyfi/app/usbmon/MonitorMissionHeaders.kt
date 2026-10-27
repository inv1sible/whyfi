package com.whyfi.app.usbmon

import com.google.gson.Gson
import java.io.File
import java.util.UUID

/** Small, disposable internal-cache index; external mission evidence remains authoritative. */
internal object MonitorMissionHeaders {
    private data class Header(val id: String, val name: String, val date: Long, val modified: Long,
                              val logBytes: Long, val reportBytes: Long?, val pcapBytes: Long?,
                              val uploaded: Boolean, val captured: Boolean)

    fun read(index: File, root: File): List<MonitorMission.Stored> = runCatching {
        Gson().fromJson(index.readText(), Array<Header>::class.java).map { row ->
            require(UUID.fromString(row.id).toString() == row.id)
            val directory = File(root, row.id)
            MonitorMission.Stored(directory, File(directory, "report.pdf"), File(directory, "mission.jsonl"),
                if (row.pcapBytes == null) null else File(directory, "capture.pcap"), row.uploaded,
                row.name, row.date, row.modified, row.logBytes, row.reportBytes, row.pcapBytes, row.captured)
        }
    }.getOrDefault(emptyList())

    fun write(index: File, missions: List<MonitorMission.Stored>) {
        runCatching {
            val temporary = File(index.parentFile, index.name + ".tmp")
            temporary.writeText(Gson().toJson(missions.map { row -> Header(row.directory.name, row.name,
                row.dateMs, row.modifiedMs, row.logBytes, row.reportBytes, row.pcapBytes, row.uploaded, row.captured) }))
            check(temporary.renameTo(index))
        }
    }
}

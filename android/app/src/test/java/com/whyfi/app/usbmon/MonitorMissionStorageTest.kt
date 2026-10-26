package com.whyfi.app.usbmon

import com.whyfi.app.scan.MonitorUiState
import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class MonitorMissionStorageTest {
    @Test fun listingTwentyThreeLargeMissionsReadsOnlyHeaders() {
        val root = Files.createTempDirectory("whyfi-headers").toFile()
        try {
            repeat(23) {
                val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
                RandomAccessFile(File(directory, "mission.jsonl"), "rw").use { log -> log.setLength(8L * 1024 * 1024) }
            }
            val start = System.nanoTime()
            val missions = MonitorMission.listIn(root)
            println("23 mission headers with 8 MiB logs: ${(System.nanoTime() - start) / 1_000_000.0} ms")
            assertEquals(23, missions.size)
            assertTrue(missions.all { it.log.length() == 8L * 1024 * 1024 })
        } finally { root.deleteRecursively() }
    }

    @Test fun namesSurviveContinuationAndDatesAreNew() {
        val root = Files.createTempDirectory("whyfi-names").toFile()
        try {
            val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            File(directory, "mission.jsonl").writeText("retained evidence")
            File(directory, "mission-date.txt").writeText("1000")
            val mission = MonitorMission.listIn(root).single()
            MonitorMission.rename(mission, "Warehouse")
            val renamed = MonitorMission.listIn(root).single()
            assertEquals("Warehouse", renamed.name)
            val copied = MonitorMission.copyAsNewIn(root, renamed)
            val continued = MonitorMission.listIn(root).single { it.directory == copied.directory }
            assertEquals(renamed.name, continued.name)
            assertTrue(continued.dateMs > renamed.dateMs)
            assertTrue(renamed.log.readText().startsWith("retained evidence"))
            assertTrue(renamed.log.readText().contains("mission_named"))
        } finally { root.deleteRecursively() }
    }

    @Test fun stoppingPersistsStatisticsWithoutPdf() {
        val directory = Files.createTempDirectory("whyfi-capture").toFile()
        try {
            val log = File(directory, "mission.jsonl").apply {
                writeText("{\"event\":\"monitor_finished\",\"at_ms\":1000,\"details\":\"frames=123\"}\n")
            }
            val files = MonitorMission.Files(directory, null, File(directory, "report.pdf"), log)
            MonitorMission.saveCaptureSummary(files, MonitorUiState(frames = 123, eapolKeyFrames = 4,
                channelFrames = mapOf(6 to 123L)))
            assertTrue(File(directory, "capture-summary.json").readText().contains("123"))
            val saved = Gson().fromJson(File(directory, "capture-summary.json").readText(), MonitorMission.CaptureSummary::class.java)
            assertEquals(4L, saved.eapol)
            assertEquals(mapOf(6 to 123L), saved.channels)
            assertFalse(files.report.exists())
            assertTrue(log.readText().contains("capture_summary"))
            assertEquals(2, MonitorEvidence.read(log).events.size)
            assertTrue(MonitorMission.Stored(directory, files.report, log, null, false).captured)
        } finally { directory.deleteRecursively() }
    }

    @Test fun renamingLegacyMissionKeepsItsOriginalDate() {
        val root = Files.createTempDirectory("whyfi-legacy-name").toFile()
        try {
            val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            File(directory, "mission.jsonl").apply { writeText("{}\n"); setLastModified(1_000_000L) }
            val old = MonitorMission.listIn(root).single()
            MonitorMission.rename(old, "Renamed")
            assertEquals(old.dateMs, MonitorMission.listIn(root).single().dateMs)
        } finally { root.deleteRecursively() }
    }
}

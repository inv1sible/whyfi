package com.whyfi.app.usbmon

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorReplayMissionTest {
    @Test fun replayInventoryAdvancesAndRewindsWithoutLeakingFutureDevices() {
        fun tsv(mac: String) = listOf(mac, 1, 3, 1, 0, 0, -60, 6, "", "", 0,
            "", "", "", "", 100, 200, -62, 195000, 6).joinToString("\t")
        val first = "00:11:22:33:44:55"
        val second = "00:11:22:33:44:66"
        val mission = MonitorReplayMission(File("mission.jsonl"), emptyList(), emptySet(), emptyMap(),
            emptyList(), listOf(MonitorMission.DeviceSnapshot(1000, 100, tsv(first)),
                MonitorMission.DeviceSnapshot(2000, 200, tsv(second))), emptySet(), null)
        assertEquals(listOf(first), mission.devicesAt(1000).map { it.first.mac })
        assertEquals(setOf(first, second), mission.devicesAt(2000).map { it.first.mac }.toSet())
        assertEquals(setOf(first, second), mission.devicesAt(3000).map { it.first.mac }.toSet())
        assertEquals(listOf(first), mission.devicesAt(1500).map { it.first.mac })
        assertTrue(mission.devicesAt(999).isEmpty())
    }

    @Test fun slider_uses_only_snapshots_and_favorites_seen_by_selected_time() {
        val mac = "00:11:22:33:44:55"
        val mission = MonitorReplayMission(File("mission.jsonl"), emptyList(), setOf(mac), emptyMap(),
            listOf(MonitorMission.ReplayEvent(2000, "favorite", "$mac:false"),
                MonitorMission.ReplayEvent(3000, "favorite", "$mac:true")),
            listOf(MonitorMission.DeviceSnapshot(1000, 100, "first"),
                MonitorMission.DeviceSnapshot(2500, 1600, "second")), setOf(mac), null)

        assertEquals("first", mission.snapshotAt(1999)?.tsv)
        assertEquals("second", mission.snapshotAt(3000)?.tsv)
        assertTrue(mac in mission.favoritesAt(1999))
        assertFalse(mac in mission.favoritesAt(2500))
        assertTrue(mac in mission.favoritesAt(3000))
    }

    @Test fun deletion_is_limited_to_inactive_uuid_directory_under_mission_root() {
        val root = Files.createTempDirectory("whyfi-missions").toFile()
        try {
            val mission = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            val log = File(mission, "mission.jsonl").apply { writeText("kept") }
            val sibling = File(root.parentFile, UUID.randomUUID().toString()).apply { mkdirs() }
            assertFalse(MonitorMission.deleteDirectory(root, sibling, null))
            assertTrue(sibling.isDirectory)
            assertFalse(MonitorMission.deleteDirectory(root, mission, log.absolutePath))
            assertTrue(log.isFile)
            assertTrue(MonitorMission.deleteDirectory(root, mission, null))
            assertFalse(mission.exists())
            sibling.deleteRecursively()
        } finally { root.deleteRecursively() }
    }

    @Test fun continuingCreatesNewUploadIdentityAndPreservesSourceEvidence() {
        val root = Files.createTempDirectory("whyfi-missions").toFile()
        try {
            val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            val log = File(directory, "mission.jsonl").apply { writeText("{\"event\":\"monitor_started\"}\n") }
            val pcap = File(directory, "capture.pcap").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val report = File(directory, "report.pdf").apply { writeText("old report") }
            File(directory, ".uploaded").writeText("uploaded")
            val source = MonitorMission.Stored(directory, report, log, pcap, true)

            val continued = MonitorMission.copyAsNewIn(root, source)

            assertFalse(continued.directory == directory)
            assertEquals(pcap.readBytes().toList(), continued.pcap?.readBytes()?.toList())
            assertEquals(log.readText(), continued.log.readText())
            assertFalse(continued.report.exists())
            assertFalse(File(continued.directory, ".uploaded").exists())
            assertEquals("old report", report.readText())
            assertEquals("{\"event\":\"monitor_started\"}\n", log.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun continuedMissionRestoresOnlyItsCurrentFavorites() {
        val original = "00:11:22:33:44:55"
        val replacement = "00:11:22:33:44:66"
        val events = listOf(
            MonitorMission.ReplayEvent(1000, "favorite", "$original:false"),
            MonitorMission.ReplayEvent(2000, "mission_resumed", ""),
            MonitorMission.ReplayEvent(2001, "mission_resumed_target", replacement),
        )
        assertEquals(setOf(replacement), MonitorMission.favoritesAfter(setOf(original), events, Long.MAX_VALUE))
        assertEquals(emptySet<String>(), MonitorMission.favoritesAfter(setOf(original), events, 2000))
    }

    @Test fun hiddenApsRemainMissionScopedAndCanBeRestored() {
        val first = "00:11:22:33:44:55"
        val second = "00:11:22:33:44:66"
        val events = listOf(
            MonitorMission.ReplayEvent(1000, "ap_hidden", "$first:true"),
            MonitorMission.ReplayEvent(2000, "ap_hidden", "$second:true"),
            MonitorMission.ReplayEvent(3000, "ap_hidden", "$first:false"),
        )
        assertEquals(setOf(second), MonitorMission.hiddenApsAfter(events))
        assertEquals(emptySet<String>(), MonitorMission.hiddenApsAfter(emptyList()))
    }

    @Test fun log_preview_skips_large_device_snapshots() {
        val log = Files.createTempFile("whyfi-mission", ".jsonl").toFile()
        try {
            log.writeText("{\"event\":\"monitor_started\"}\n" +
                "{\"event\":\"devices_snapshot\",\"tsv\":\"${"x".repeat(100_000)}\"}\n" +
                "{\"event\":\"monitor_finished\"}\n")
            assertFalse(MonitorMission.previewLog(log).contains("devices_snapshot"))
            assertTrue(MonitorMission.previewLog(log).contains("monitor_finished"))
        } finally { log.delete() }
    }
}

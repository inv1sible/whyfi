package com.whyfi.app.usbmon

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorReplayMissionTest {
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

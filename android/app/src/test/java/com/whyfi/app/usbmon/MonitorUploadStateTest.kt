package com.whyfi.app.usbmon

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorUploadStateTest {
    // Missions live in UUID-named folders (MonitorMission.listIn).
    private fun mission(): File = File(Files.createTempDirectory("missions").toFile(), java.util.UUID.randomUUID().toString()).also {
        it.mkdirs()
        File(it, "mission.jsonl").writeText("{\"event\":\"monitor_started\"}\n")
    }

    @Test fun uploadedUntilTheLogGrows() {
        val dir = mission()
        assertNull(MonitorMission.uploadedLogBytes(dir))
        assertFalse(MonitorMission.isUploaded(dir))
        val stored = MonitorMission.listIn(dir.parentFile).first { it.directory == dir }
        MonitorMission.markUploaded(stored)
        assertTrue(MonitorMission.isUploaded(dir))
        // Continuing the mission appends: it needs another upload.
        File(dir, "mission.jsonl").appendText("{\"event\":\"monitor_finished\"}\n")
        assertFalse(MonitorMission.isUploaded(dir))
        dir.deleteRecursively()
    }

    @Test fun olderTimeOnlyMarkersCountAsUploaded() {
        val dir = mission()
        File(dir, ".uploaded").writeText("1762844400000")
        assertEquals(Long.MAX_VALUE, MonitorMission.uploadedLogBytes(dir))
        assertTrue(MonitorMission.isUploaded(dir))
        dir.deleteRecursively()
    }

    @Test fun logCopyIsComplete() {
        val dir = mission()
        val copy = File(dir, "copy.jsonl")
        assertEquals(File(dir, "mission.jsonl").length(), MonitorMission.copyLog(File(dir, "mission.jsonl"), copy))
        assertEquals(File(dir, "mission.jsonl").readText(), copy.readText())
        dir.deleteRecursively()
    }
}

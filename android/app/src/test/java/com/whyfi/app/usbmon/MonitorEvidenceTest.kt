package com.whyfi.app.usbmon

import com.google.gson.Gson
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MonitorEvidenceTest {
    private val mac = "00:11:22:33:44:55"
    private fun tsv(mac: String, role: Int) = listOf(mac, role, 3, 1, 0, 0, -60, 6, "", "", 0,
        "", "", "", "", 100, 200, -62, 195000, 6).joinToString("\t")

    @Test fun oneReadRestoresAllPassesAndCorrelatesClientTransmissions() {
        val log = Files.createTempFile("whyfi-evidence", ".jsonl").toFile()
        try {
            val client = "00:11:22:33:44:66"
            val rows = listOf(
                mapOf("event" to "monitor_started", "at_ms" to 999_000),
                mapOf("event" to "mission_target", "at_ms" to 999_001, "details" to mac),
                mapOf("event" to "devices_snapshot", "at_ms" to 1_000_000, "clock_ms" to 200_000, "tsv" to tsv(mac, 1)),
                mapOf("event" to "devices_snapshot", "at_ms" to 1_000_001, "clock_ms" to 200_001, "tsv" to tsv(client, 2)),
                mapOf("event" to "receiver_fix", "at_ms" to 1_001_000, "boot_ms" to 201_000,
                    "provider" to "gps", "lat" to 48.0, "lng" to 11.0, "accuracy_m" to 10.0),
            )
            log.writeText(rows.joinToString("\n", postfix = "\n") { Gson().toJson(it) } + "incomplete line\n")
            val evidence = MonitorEvidence.read(log)
            assertEquals(setOf(mac, client), evidence.inventoryAt().map { it.mac }.toSet())
            assertEquals(listOf(mac), evidence.inventoryAt(1_000_000).map { it.mac })
            assertEquals(setOf(mac, client), evidence.radioSamples.map { it.mac }.toSet())
            assertEquals(listOf(mac), evidence.apSamples.map { it.mac })
            assertEquals(setOf(mac), evidence.initialFavorites)
        } finally { log.delete() }
    }

    @Test fun incompleteLocationEntriesCannotProduceZeroCoordinates() {
        val log = Files.createTempFile("whyfi-incomplete", ".jsonl").toFile()
        try {
            log.writeText("{\"event\":\"receiver_fix\",\"at_ms\":1000,\"boot_ms\":500,\"provider\":\"gps\"}\n" +
                "{\"event\":\"geo_sample\",\"at_ms\":1000,\"mac\":\"$mac\"}\n")
            val evidence = MonitorEvidence.read(log)
            assertTrue(evidence.receiverFixes.isEmpty())
            assertTrue(evidence.savedSamples.isEmpty())
        } finally { log.delete() }
    }
}

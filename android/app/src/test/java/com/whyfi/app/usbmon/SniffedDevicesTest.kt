package com.whyfi.app.usbmon

import com.whyfi.app.usbmon.SniffedDevices.Kind
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffedDevicesTest {

    private fun line(
        mac: String, roles: Int, up: Long = 0, down: Long = 0, probes: Long = 0, channels: String = "6",
        ssid: String = "", probed: String = "", bssids: String = "", wpsName: String = "", frames: Long = 30,
    ) = listOf(
        mac, roles, frames, probes, up, down, "-60", channels, ssid, probed, "0", bssids, wpsName, "", "", 10, 900,
    ).joinToString("\t")

    private val vendors = OuiTable(
        mapOf(
            "BC9A8E" to "Hangzhou Hikvision Digital Technology Co.,Ltd.",
            "24A160" to "Espressif Inc.",
            "001E06" to "Realtek Semiconductor Corp.",
            "3C2EFF" to "Apple, Inc.",
            "40A5EF" to "Shenzhen Four Seas Global Link Network Technology Co., Ltd.",
        ),
    )

    private fun guess(tsv: String) = SniffedDevices.guess(SniffedDevices.parse(tsv).single(), vendors::vendorOf)

    @Test
    fun `parse reads every field and skips malformed lines`() {
        val tsv = line("24:a1:60:00:00:01", 2, probes = 3, probed = "Home|Office", bssids = "aa:bb:cc:00:00:01", channels = "1,6") +
            "\ngarbage\n"
        val d = SniffedDevices.parse(tsv).single()
        assertEquals(listOf("Home", "Office"), d.probed)
        assertEquals(listOf(1, 6), d.channels)
        assertEquals(-60, d.bestRssi)
        assertTrue(d.isClient)
        assertFalse(d.isAp)
    }

    @Test
    fun `parse keeps the recent radio reading separate from the session maximum`() {
        val d = SniffedDevices.parse(line("24:a1:60:00:00:01", 2) + "\t-74\t1234567\t11").single()
        assertEquals(-60, d.bestRssi)
        assertEquals(-74, d.lastRssi)
        assertEquals(1_234_567L, d.lastRssiBootMs)
        assertEquals(11, d.lastRssiChannel)
    }

    @Test
    fun `camera vendor is a camera`() {
        assertEquals(Kind.CAMERA, guess(line("bc:9a:8e:00:00:01", 2)).kind)
    }

    @Test
    fun `IoT module vendor that uploads continuously is a camera`() {
        val g = guess(line("24:a1:60:00:00:02", 2, up = 900_000, down = 20_000))
        assertEquals(Kind.CAMERA, g.kind)
        assertTrue(g.reasons.any { it.startsWith("uploads continuously") })
    }

    @Test
    fun `IoT module vendor alone is IoT`() {
        assertEquals(Kind.IOT, guess(line("24:a1:60:00:00:03", 2)).kind)
    }

    @Test
    fun `WPS name mentioning a camera wins over an unknown vendor`() {
        val g = guess(line("40:a5:ef:00:00:04", 2, wpsName = "IPC-HFW1230S"))
        assertEquals(Kind.CAMERA, g.kind)
        assertTrue(g.reasons.any { it.contains("IPC-HFW1230S") })
    }

    @Test
    fun `randomized client is a phone or laptop and has no vendor`() {
        val g = guess(line("da:a1:19:00:00:05", 2, probes = 4))
        assertEquals(Kind.PHONE_LAPTOP, g.kind)
        assertNull(g.vendor)
    }

    @Test
    fun `phone vendor with a fixed MAC is a phone or laptop`() {
        assertEquals(Kind.PHONE_LAPTOP, guess(line("3c:2e:ff:00:00:06", 2)).kind)
    }

    @Test
    fun `chip maker only on 2_4 GHz is likely embedded`() {
        assertEquals(Kind.IOT, guess(line("00:1e:06:00:00:07", 2, channels = "1,6")).kind)
    }

    @Test
    fun `beaconing device is an access point unless its name is a setup hotspot`() {
        assertEquals(Kind.ACCESS_POINT, guess(line("40:a5:ef:00:00:08", 1, ssid = "FRITZ!Box 7590")).kind)
        assertEquals(Kind.IOT, guess(line("40:a5:ef:00:00:09", 1, ssid = "ESP_A1B2C3")).kind)
    }

    @Test
    fun `activity describes searching and connections`() {
        val d = SniffedDevices.parse(line("da:a1:19:00:00:0a", 2, probes = 2, probed = "Home", bssids = "aa:bb:cc:00:00:01")).single()
        assertEquals("searching for \"Home\"; connected to 1 AP", SniffedDevices.activity(d))
    }

    @Test
    fun `oui table reads the asset format and ignores randomized MACs`() {
        val bytes = "BC9A8E\tHikvision\nbad line\n24A160\tEspressif Inc.\n".toByteArray()
        val table = OuiTable.read(ByteArrayInputStream(bytes))
        assertEquals(2, table.size)
        assertEquals("Hikvision", table.vendorOf("bc:9a:8e:12:34:56"))
        assertNull(table.vendorOf("be:9a:8e:12:34:56"))
    }

    @Test
    fun `report summary has kinds and vendors but no MACs or names unless opted in`() {
        val tsv = listOf(
            line("bc:9a:8e:00:00:01", 2, bssids = "aa:bb:cc:00:00:01"),
            line("da:a1:19:00:00:05", 2, probes = 4, probed = "SecretHome"),
        ).joinToString("\n")
        val devices = SniffedDevices.parse(tsv).map { it to SniffedDevices.guess(it, vendors::vendorOf) }
        val run = UsbMonitorReport.Run(
            device = "0bda:0811", androidInfo = "Pixel", appVersion = "spike", result = null, durationMs = 60_000,
            stats = LongArray(0), perChannel = longArrayOf(), trace = null, logcat = null, devices = devices,
            vendorPrefixes = 5,
        )
        val anonymous = UsbMonitorReport.format(run)
        assertTrue(anonymous.contains("Camera?: 1"))
        assertTrue(anonymous.contains("Vendor table: 5 prefixes"))
        assertTrue(anonymous.contains("searching and not connected: 1"))
        assertTrue(anonymous.contains("Hangzhou Hikvision Digital Technology Co.,Ltd. ×1"))
        assertFalse(anonymous.contains("bc:9a:8e"))
        assertFalse(anonymous.contains("SecretHome"))

        val detailed = UsbMonitorReport.format(run.copy(includeDeviceDetails = true))
        assertTrue(detailed.contains("bc:9a:8e:00:00:01"))
        assertTrue(detailed.contains("SecretHome"))
    }
}

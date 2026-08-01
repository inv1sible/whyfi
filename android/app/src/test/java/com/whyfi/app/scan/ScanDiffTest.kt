package com.whyfi.app.scan

import com.whyfi.app.data.remote.BleObservationDto
import com.whyfi.app.data.remote.CellObservationDto
import com.whyfi.app.data.remote.SatelliteObservationDto
import com.whyfi.app.data.remote.ScanSessionUploadRequest
import com.whyfi.app.data.remote.WifiObservationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDiffTest {

    private fun wifi(bssid: String, ssid: String = "Net", rssi: Int = -50) = WifiObservationDto(
        bssid = bssid, ssid = ssid, rssi = rssi, frequencyMhz = 2437, capabilities = "[ESS]",
    )

    private fun cell(cellId: String, serving: Boolean = false, dbm: Int? = -90) = CellObservationDto(
        mcc = "262", mnc = "01", carrierName = "Telekom", radioType = "LTE", cellId = cellId,
        tacOrLac = "4711", band = "3", isServingCell = serving, signalDbm = dbm,
        rsrp = null, rsrq = null, sinr = null,
    )

    private fun ble(mac: String, rssi: Int = -70) = BleObservationDto(
        bleMac = mac, stableIdentifier = mac, rssi = rssi, txPower = null,
        manufacturerData = "", deviceTypeGuess = "UNKNOWN",
    )

    private fun sat(constellation: String, svid: Int, cn0: Double = 30.0) = SatelliteObservationDto(
        constellation = constellation, svid = svid, cn0DbHz = cn0,
        elevationDegrees = 45.0, azimuthDegrees = 180.0, usedInFix = true,
    )

    private fun pass(
        wifi: List<WifiObservationDto> = emptyList(),
        cells: List<CellObservationDto> = emptyList(),
        ble: List<BleObservationDto> = emptyList(),
        sats: List<SatelliteObservationDto> = emptyList(),
    ) = ScanSessionUploadRequest(
        clientScanId = "test", startedAt = "2026-01-01T00:00:00Z", completedAt = "2026-01-01T00:00:05Z",
        latitude = null, longitude = null, locationAccuracyMeters = null,
        wifiObservations = wifi, cellObservations = cells, bleObservations = ble,
        satelliteObservations = sats,
    )

    // --- The ambiguity guard ---------------------------------------------

    @Test
    fun `with no previous pass nothing is claimed to be new`() {
        val latest = pass(wifi = listOf(wifi("aa"), wifi("bb")))

        val rows = ScanDiff.rowsFor(RadioKind.WIFI, latest, previous = null)

        assertTrue(rows.all { it.change == DeviceChange.UNKNOWN })
        assertEquals(false, ScanDiff.summarize(rows).comparable)
    }

    @Test
    fun `a radio absent from the previous pass yields no comparison, not all-new`() {
        // BLE was switched off last pass, so its empty list means "we don't
        // know", not "none were there". Calling every device NEW here would
        // invent a finding out of a radio the user had turned off.
        val previous = pass(wifi = listOf(wifi("aa")))
        val latest = pass(wifi = listOf(wifi("aa")), ble = listOf(ble("11:22")))

        val bleRows = ScanDiff.rowsFor(RadioKind.BLE, latest, previous)

        assertTrue(bleRows.all { it.change == DeviceChange.UNKNOWN })
        assertEquals(false, ScanDiff.summarize(bleRows).comparable)
        // WiFi in the same pair of passes is comparable, so the guard is
        // per-radio rather than per-pass.
        assertEquals(true, ScanDiff.summarize(ScanDiff.rowsFor(RadioKind.WIFI, latest, previous)).comparable)
    }

    // --- New / present / gone --------------------------------------------

    @Test
    fun `wifi is classified against the previous pass by bssid`() {
        val previous = pass(wifi = listOf(wifi("aa"), wifi("bb")))
        val latest = pass(wifi = listOf(wifi("aa"), wifi("cc")))

        val rows = ScanDiff.rowsFor(RadioKind.WIFI, latest, previous)
        val byKey = rows.associateBy { it.key }

        assertEquals(DeviceChange.PRESENT, byKey.getValue("aa").change)
        assertEquals(DeviceChange.NEW, byKey.getValue("cc").change)
        assertEquals(DeviceChange.GONE, byKey.getValue("bb").change)

        val summary = ScanDiff.summarize(rows)
        assertEquals(1, summary.new)
        assertEquals(1, summary.gone)
        // Gone devices aren't part of "how many are here now".
        assertEquals(2, summary.total)
    }

    @Test
    fun `gone rows come last so the current picture reads first`() {
        val previous = pass(wifi = listOf(wifi("gone-one"), wifi("still-here")))
        val latest = pass(wifi = listOf(wifi("still-here")))

        val rows = ScanDiff.rowsFor(RadioKind.WIFI, latest, previous)

        assertEquals(DeviceChange.GONE, rows.last().change)
    }

    @Test
    fun `signal delta is carried for devices seen in both passes`() {
        val previous = pass(wifi = listOf(wifi("aa", rssi = -70)))
        val latest = pass(wifi = listOf(wifi("aa", rssi = -55)))

        val row = ScanDiff.rowsFor(RadioKind.WIFI, latest, previous).single()

        assertEquals(15, row.signalDelta)
    }

    @Test
    fun `a newly seen device has no signal delta to report`() {
        val previous = pass(wifi = listOf(wifi("aa")))
        val latest = pass(wifi = listOf(wifi("aa"), wifi("bb")))

        val row = ScanDiff.rowsFor(RadioKind.WIFI, latest, previous).single { it.key == "bb" }

        assertNull(row.signalDelta)
    }

    // --- Identity keys ----------------------------------------------------

    @Test
    fun `cell key matches the backend tower_key format`() {
        // backend/scans/serializers.py: f"{mcc}-{mnc}-{tac_or_lac}-{cell_id}"
        assertEquals("262-01-4711-12345", ScanDiff.cellKey("262", "01", "4711", "12345"))
    }

    @Test
    fun `cells are keyed so the same tower is not counted twice`() {
        val previous = pass(cells = listOf(cell("12345")))
        val latest = pass(cells = listOf(cell("12345"), cell("67890")))

        val summary = ScanDiff.summarize(ScanDiff.rowsFor(RadioKind.CELLULAR, latest, previous))

        assertEquals(1, summary.new)
        assertEquals(0, summary.gone)
    }

    @Test
    fun `anonymous neighbour cells stay distinct rows and are never matched`() {
        // Neighbours routinely report no cell ID (CellIdentity.ci is the
        // unavailable sentinel), so several share one tower_key. They must
        // not collide into one row, and must not be paired across passes.
        val previous = pass(cells = listOf(cell(""), cell("")))
        val latest = pass(cells = listOf(cell(""), cell(""), cell("")))

        val rows = ScanDiff.rowsFor(RadioKind.CELLULAR, latest, previous)

        assertEquals(3, rows.size)
        assertEquals("distinct row keys", 3, rows.map { it.key }.toSet().size)
        assertTrue(rows.all { it.change == DeviceChange.UNKNOWN })
        assertEquals(0, ScanDiff.summarize(rows).gone)
    }

    @Test
    fun `identifiable cells still compare even when anonymous ones are present`() {
        val previous = pass(cells = listOf(cell("12345"), cell("")))
        val latest = pass(cells = listOf(cell("12345"), cell(""), cell("99999")))

        val rows = ScanDiff.rowsFor(RadioKind.CELLULAR, latest, previous)
        val byKey = rows.associateBy { it.key }

        assertEquals(DeviceChange.PRESENT, byKey.getValue("262-01-4711-12345").change)
        assertEquals(DeviceChange.NEW, byKey.getValue("262-01-4711-99999").change)
    }

    @Test
    fun `the serving cell is listed first`() {
        val latest = pass(cells = listOf(cell("neighbour", serving = false, dbm = -60), cell("serving", serving = true, dbm = -95)))

        val rows = ScanDiff.rowsFor(RadioKind.CELLULAR, latest, previous = null)

        // Ordered ahead of a stronger neighbour: it's the cell actually
        // carrying traffic, which is the one worth seeing.
        assertEquals("262-01-4711-serving", rows.first().key)
    }

    @Test
    fun `satellites with the same svid in different constellations are distinct`() {
        val latest = pass(sats = listOf(sat("GPS", 12), sat("GALILEO", 12)))

        val rows = ScanDiff.rowsFor(RadioKind.SATELLITE, latest, previous = null)

        assertEquals(2, rows.map { it.key }.toSet().size)
    }

    // --- Column shape -----------------------------------------------------

    @Test
    fun `every row supplies exactly one cell per declared column`() {
        val latest = pass(
            wifi = listOf(wifi("aa")),
            cells = listOf(cell("1")),
            ble = listOf(ble("11:22")),
            sats = listOf(sat("GPS", 3)),
        )

        RadioKind.entries.forEach { kind ->
            val expected = ScanDiff.columnsFor(kind).size
            ScanDiff.rowsFor(kind, latest, previous = null).forEach { row ->
                assertEquals("column count for $kind", expected, row.columns.size)
            }
        }
    }
}

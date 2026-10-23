package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorSampleReconcilerTest {
    private val mac = "00:11:22:33:44:55"
    private val tsv = listOf(mac, "1", "3", "0", "0", "0", "-60", "6", "Example", "", "0", "", "", "", "",
        "100", "200", "-62", "195000", "6").joinToString("\t")
    private val snapshot = MonitorMission.DeviceSnapshot(1_000_000, 200_000, tsv)
    private val delayedFix = MonitorReceiverFix(1_001_000, 201_000, "gps", 48.0, 11.0, 10.0, 30.0)

    @Test fun `later receiver fix recovers pinned radio reading with travel uncertainty`() {
        val samples = MonitorSampleReconciler.reconcile(emptyList(), listOf(delayedFix), listOf(snapshot), setOf(mac), emptyList())
        assertEquals(1, samples.size)
        assertEquals(995_000L, samples.single().atMs)
        assertEquals(6_000L, samples.single().fixAgeMs)
        assertEquals(30.0, samples.single().speedMps, 0.001)
    }

    @Test fun `saved reading is not duplicated and unpinned devices stay off target map`() {
        val saved = MonitorGeoSample(mac, 995_100, 48.0, 11.0, 10.0, -62, 6)
        assertEquals(listOf(saved), MonitorSampleReconciler.reconcile(listOf(saved), listOf(delayedFix),
            listOf(snapshot), setOf(mac), emptyList()))
        assertTrue(MonitorSampleReconciler.reconcile(emptyList(), listOf(delayedFix), listOf(snapshot),
            emptySet(), emptyList()).isEmpty())
    }

    @Test fun `readings before pinning or after unpinning are not inferred`() {
        val pinnedSnapshot = snapshot.copy(atMs = snapshot.atMs + 5_000, clockMs = snapshot.clockMs + 5_000,
            tsv = tsv.replace("195000", "204000"))
        val staleAfterPin = snapshot.copy(atMs = snapshot.atMs + 2_000, clockMs = snapshot.clockMs + 2_000)
        val laterSnapshot = snapshot.copy(atMs = snapshot.atMs + 10_000, clockMs = snapshot.clockMs + 10_000,
            tsv = tsv.replace("195000", "209000"))
        val changes = listOf(
            MonitorMission.ReplayEvent(snapshot.atMs + 1_000, "favorite", "$mac:true"),
            MonitorMission.ReplayEvent(snapshot.atMs + 9_000, "favorite", "$mac:false"),
        )
        val recovered = MonitorSampleReconciler.reconcile(emptyList(), listOf(delayedFix),
            listOf(snapshot, staleAfterPin, pinnedSnapshot, laterSnapshot), emptySet(), changes)
        assertEquals(listOf(204_000L), recovered.map { it.atMs - 800_000L })
    }
}

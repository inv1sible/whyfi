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

    @Test fun `access points are correlated without favorites but clients are excluded`() {
        val client = tsv.replace("\t1\t3\t", "\t2\t3\t").replace(mac, "00:11:22:33:44:66")
        val samples = MonitorSampleReconciler.accessPoints(listOf(delayedFix),
            listOf(snapshot.copy(tsv = "$tsv\n$client"), snapshot.copy(atMs = 1_002_000, clockMs = 202_000)))
        assertEquals(1, samples.size)
        assertEquals(mac, samples.single().mac)
        assertEquals(995_000L, samples.single().atMs)
        assertEquals(6_000L, samples.single().fixAgeMs)
    }

    @Test fun `resumed mission uses its current favorites rather than old pins`() {
        val later = snapshot.copy(atMs = 1_010_000, clockMs = 210_000,
            tsv = tsv.replace("195000", "209000"))
        val resumed = listOf(MonitorMission.ReplayEvent(1_005_000, "mission_resumed", ""))
        val samples = MonitorSampleReconciler.reconcile(emptyList(), listOf(delayedFix),
            listOf(snapshot, later), setOf(mac), resumed)
        assertEquals(listOf(995_000L), samples.map { it.atMs })
        val pinnedAgain = resumed + MonitorMission.ReplayEvent(1_006_000, "mission_resumed_target", mac)
        assertEquals(2, MonitorSampleReconciler.reconcile(emptyList(), listOf(delayedFix),
            listOf(snapshot, later), setOf(mac), pinnedAgain).size)
    }

    @Test fun `old uptime snapshots cannot create false AP locations`() {
        assertTrue(MonitorSampleReconciler.accessPoints(listOf(delayedFix),
            listOf(snapshot.copy(clockMs = 10_000))).isEmpty())
    }
}

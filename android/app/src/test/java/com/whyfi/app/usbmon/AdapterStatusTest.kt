package com.whyfi.app.usbmon

import org.junit.Assert.assertEquals
import org.junit.Test

class AdapterStatusTest {

    @Test
    fun fewChannelsAreListedManyAreCounted() {
        assertEquals("ch 1, 6, 11", AdapterStatus.channels(listOf(11, 1, 6)))
        assertEquals("13 channels", AdapterStatus.channels((1..13).toList()))
        assertEquals("no channels", AdapterStatus.channels(emptyList()))
    }

    @Test
    fun elapsedSwitchesToHours() {
        assertEquals("0:00", AdapterStatus.elapsed(0))
        assertEquals("12:41", AdapterStatus.elapsed(761_000))
        assertEquals("1:02:03", AdapterStatus.elapsed(3_723_000))
    }

    @Test
    fun notificationNamesTheAdapterCapture() {
        assertEquals("Monitor capture · ch 6 · 4,212 devices", AdapterStatus.notification(listOf(6), 4212))
        assertEquals("Monitor capture · ch 1 · 1 device", AdapterStatus.notification(listOf(1), 1))
    }

    @Test
    fun stripCoversRecordingReadyAndNoAdapter() {
        assertEquals(
            "● REC 2:05 · ch 1, 6, 11 · 37 devices · PCAP 12.3 MB",
            AdapterStatus.strip(true, "0bda:8812", listOf(1, 6, 11), 125_000, 12_345_678, 37),
        )
        assertEquals("● REC 0:10 · ch 6 · 0 devices", AdapterStatus.strip(true, null, listOf(6), 10_000, 0, 0))
        assertEquals("Adapter ready · 0bda:8812 Realtek", AdapterStatus.strip(false, "0bda:8812 Realtek", emptyList(), 0, 0, 0))
        assertEquals("No adapter connected · missions and maps still open", AdapterStatus.strip(false, null, emptyList(), 0, 0, 0))
    }
}

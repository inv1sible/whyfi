package com.whyfi.app.usbmon

/**
 * SPIKE — JNI bindings to src/main/cpp/whyfiusb.cpp: a receive-only
 * monitor-mode probe for RTL8812AU-family USB sticks via devourer + libusb.
 * Throwaway feasibility code; not wired into scanning or uploads.
 */
object UsbMonitorNative {

    /** Null when the library loaded; otherwise why it didn't. */
    val loadError: String? = runCatching { System.loadLibrary("whyfiusb") }
        .exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }

    /** Blocks for the whole test. Returns "" on success, else the reason. */
    external fun run(fd: Int, lockDir: String, tracePath: String, pcapPath: String, channels: IntArray, dwellMs: Int, totalMs: Int): String

    external fun stop()

    /** Indexed by [Stat]. */
    external fun stats(): LongArray

    /** Flat [channel, frames, channel, frames, ...] for channels that saw any. */
    external fun perChannel(): LongArray

    /** Per-device TSV, parsed by [SniffedDevices.parse]. */
    external fun devices(): String

    /** Mirrors the Stat enum in whyfiusb.cpp — keep in sync. */
    enum class Stat {
        TOTAL, MGMT, CTRL, DATA, BEACON, PROBE_REQ, PROBE_RESP,
        PROBE_REQ_SOURCES, PROBE_REQ_LOCAL_ADMIN, PROBE_REQ_DIRECTED,
        CLIENTS_TO_AP, RSSI_MIN, RSSI_MAX, CURRENT_CHANNEL, STAGE, BAD_FCS_OR_SHORT,
        BEACON_CH_MATCH, BEACON_CH_OTHER, DEVICES, NO_RSSI, PCAP_BYTES, PCAP_TRUNCATED,
    }

    /** Native STAGE values, for display. */
    val STAGES = listOf(
        "not started", "libusb init", "claiming interface", "creating radio",
        "bring-up (firmware, calibration, monitor mode)", "receiving and hopping", "stopping", "finished",
    )

    /** What to listen to and how long per channel. */
    enum class Mode(val label: String, val channels: IntArray, val dwellMs: Int) {
        SURVEY("All channels (survey)", DEFAULT_CHANNELS, 400),
        CLIENTS_24("Clients on 2.4 GHz (1/6/11, long dwell)", intArrayOf(1, 6, 11), 3_000),
    }

    /** 2.4 GHz channels 1–13 plus the listed 5 GHz 20 MHz channels
     * (including DFS). Receive only; this adapter has no 6 GHz radio. */
    val DEFAULT_CHANNELS = (1..13).toList().toIntArray() + intArrayOf(
        36, 40, 44, 48, 52, 56, 60, 64,
        100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140,
        149, 153, 157, 161, 165,
    )
}

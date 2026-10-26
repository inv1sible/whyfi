package com.whyfi.app.usbmon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorMovePromptTest {
    private val anchor = MonitorLocationCandidate("gps", 1_000, 52.0, 13.0, 40.0)

    @Test fun quietStationaryReceiverGetsOneEligiblePrompt() {
        val jitter = anchor.copy(bootMs = 120_000, lat = 52.0001, accuracyM = 45.0)
        assertFalse(MonitorMovePrompt.shouldPrompt(119_999, 0, anchor, jitter))
        assertTrue(MonitorMovePrompt.shouldPrompt(120_000, 0, anchor, jitter))
        assertFalse(MonitorMovePrompt.moved(anchor, jitter))
    }

    @Test fun realMovementOrMissingFixDoesNotPrompt() {
        val moved = anchor.copy(bootMs = 120_000, lat = 52.001, accuracyM = 15.0)
        assertTrue(MonitorMovePrompt.moved(anchor, moved))
        assertFalse(MonitorMovePrompt.shouldPrompt(120_000, 0, anchor, moved))
        assertFalse(MonitorMovePrompt.shouldPrompt(120_000, 0, null, anchor))
    }
}

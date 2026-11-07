package com.whyfi.app.scan

import com.whyfi.app.scan.RemoteScanAction.Companion.decide
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteScanDecisionTest {

    @Test
    fun localLoopSurvivesRepeatedRemoteOff() {
        // Field report 2026-11-07: armed remote control with remote scanning
        // off stopped a locally started continuous scan after one pass.
        assertEquals(RemoteScanAction.NONE, decide(previous = null, wanted = false, looping = true))
        assertEquals(RemoteScanAction.NONE, decide(previous = false, wanted = false, looping = true))
    }

    @Test
    fun switchingRemoteScanningOffStopsTheLoop() {
        assertEquals(RemoteScanAction.STOP, decide(previous = true, wanted = false, looping = true))
        assertEquals(RemoteScanAction.NONE, decide(previous = true, wanted = false, looping = false))
    }

    @Test
    fun switchingRemoteScanningOnStarts() {
        assertEquals(RemoteScanAction.START, decide(previous = false, wanted = true, looping = false))
        // First policy after arming / app restart.
        assertEquals(RemoteScanAction.START, decide(previous = null, wanted = true, looping = false))
    }

    @Test
    fun localStopIsNotUndoneByTheNextHeartbeat() {
        assertEquals(RemoteScanAction.NONE, decide(previous = true, wanted = true, looping = false))
    }

    @Test
    fun runningRemoteLoopPicksUpConfigChanges() {
        assertEquals(RemoteScanAction.UPDATE, decide(previous = true, wanted = true, looping = true))
    }
}

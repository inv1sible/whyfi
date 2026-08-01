package com.whyfi.app.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionClassifierTest {

    private val t0 = 1_000_000L

    @Test
    fun `starts walking rather than assuming either extreme`() {
        // Before anything is known, Walking is the safe read: Stationary would
        // risk missing ground, Driving would burn battery.
        assertEquals(MotionState.WALKING, MotionClassifier().state(t0))
    }

    @Test
    fun `going still only counts after the dwell has elapsed`() {
        val c = MotionClassifier()
        c.onMovementSignal(moving = false, atMs = t0)

        assertEquals(MotionState.WALKING, c.state(t0 + 1_000))
        assertEquals(
            MotionState.WALKING,
            c.state(t0 + MotionClassifier.STILL_DWELL_FROM_WALKING_MS - 1),
        )
        assertEquals(
            MotionState.STATIONARY,
            c.state(t0 + MotionClassifier.STILL_DWELL_FROM_WALKING_MS),
        )
    }

    @Test
    fun `movement promotes out of stationary immediately`() {
        // This is the transition the feature turns on — waiting for a speed
        // sample would mean waiting out the rest of a 10-minute sleep.
        val c = MotionClassifier()
        c.onMovementSignal(moving = false, atMs = t0)
        assertEquals(MotionState.STATIONARY, c.state(t0 + MotionClassifier.STILL_DWELL_FROM_WALKING_MS))

        c.onMovementSignal(moving = true, atMs = t0 + 200_000)

        assertEquals(MotionState.WALKING, c.state(t0 + 200_000))
    }

    @Test
    fun `a fast speed sample while moving means driving`() {
        val c = MotionClassifier()
        c.onMovementSignal(moving = true, atMs = t0)
        c.onSpeedSample(MotionClassifier.DRIVING_ENTER_MPS + 1.0, atMs = t0)

        assertEquals(MotionState.DRIVING, c.state(t0))
    }

    @Test
    fun `walking pace stays walking`() {
        val c = MotionClassifier()
        c.onMovementSignal(moving = true, atMs = t0)
        c.onSpeedSample(1.4, atMs = t0)

        assertEquals(MotionState.WALKING, c.state(t0))
    }

    @Test
    fun `driving holds through the hysteresis band`() {
        // City driving dips below the entry threshold between junctions; the
        // cadence shouldn't flap on every slow stretch.
        val c = MotionClassifier()
        c.onMovementSignal(moving = true, atMs = t0)
        c.onSpeedSample(10.0, atMs = t0)
        assertEquals(MotionState.DRIVING, c.state(t0))

        c.onSpeedSample(MotionClassifier.DRIVING_EXIT_MPS + 0.2, atMs = t0 + 30_000)
        assertEquals(MotionState.DRIVING, c.state(t0 + 30_000))

        c.onSpeedSample(MotionClassifier.DRIVING_EXIT_MPS - 0.2, atMs = t0 + 60_000)
        assertEquals(MotionState.WALKING, c.state(t0 + 60_000))
    }

    @Test
    fun `leaving a vehicle state needs a much longer still period`() {
        // A car at a red light is still for a minute. Dropping to the
        // stationary cadence there is wrong, and risky: a smooth ride produces
        // little accelerometer signal, so the sensor may be slow to say
        // "moving" again and the survey would stall at 10-minute intervals.
        val c = MotionClassifier()
        c.onMovementSignal(moving = true, atMs = t0)
        c.onSpeedSample(15.0, atMs = t0)
        assertEquals(MotionState.DRIVING, c.state(t0))

        c.onMovementSignal(moving = false, atMs = t0 + 1_000)

        val walkingDwell = t0 + 1_000 + MotionClassifier.STILL_DWELL_FROM_WALKING_MS
        assertEquals("still driving at the walking dwell", MotionState.DRIVING, c.state(walkingDwell))

        val drivingDwell = t0 + 1_000 + MotionClassifier.STILL_DWELL_FROM_DRIVING_MS
        assertEquals(MotionState.STATIONARY, c.state(drivingDwell))
    }

    @Test
    fun `a stale speed sample stops counting`() {
        // You may have parked. An hour-old 20 m/s reading must not keep the
        // scanner in the driving cadence.
        val c = MotionClassifier()
        c.onMovementSignal(moving = true, atMs = t0)
        c.onSpeedSample(20.0, atMs = t0)
        assertEquals(MotionState.DRIVING, c.state(t0))

        val later = t0 + MotionClassifier.SPEED_STALE_MS + 1
        c.onMovementSignal(moving = true, atMs = later)

        assertEquals(MotionState.WALKING, c.state(later))
    }

    @Test
    fun `repeated still signals do not restart the dwell`() {
        // Tier 3 polls the accelerometer every 30s and reports "still" each
        // time. If each report reset the timer, a stationary phone would never
        // reach the stationary state at all.
        val c = MotionClassifier()
        c.onMovementSignal(moving = false, atMs = t0)
        c.onMovementSignal(moving = false, atMs = t0 + 30_000)
        c.onMovementSignal(moving = false, atMs = t0 + 60_000)

        assertEquals(
            MotionState.STATIONARY,
            c.state(t0 + MotionClassifier.STILL_DWELL_FROM_WALKING_MS),
        )
    }
}

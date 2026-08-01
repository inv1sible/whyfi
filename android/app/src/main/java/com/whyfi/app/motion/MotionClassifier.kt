package com.whyfi.app.motion

/** How the phone is moving, and therefore how much new ground a scan covers. */
enum class MotionState(val label: String) {
    STATIONARY("Stationary"),
    WALKING("Walking"),
    DRIVING("Driving"),
}

/**
 * Turns two noisy signals — a hardware "moving / not moving" bit and an
 * occasional speed sample — into one motion state.
 *
 * Deliberately pure: no Android types, no clock of its own (every method takes
 * the time). All the plumbing lives in [MotionDetector]. This is the part with
 * the judgement calls in it, so it's the part worth unit-testing.
 *
 * The two signals are complementary, which is the whole reason for fusing
 * them. The motion sensor is nearly free and reacts in seconds, but cannot
 * tell walking from driving. Speed tells them apart precisely, but only
 * arrives when a scan pass takes a position — once every 10 minutes when
 * stationary, which is far too slow to notice you've started walking.
 */
class MotionClassifier {

    private var state = MotionState.WALKING
    private var stillSinceMs: Long? = null
    private var lastSpeedMps: Double? = null
    private var lastSpeedAtMs = 0L

    /** The hardware's verdict. [moving] false means "has been still a while". */
    fun onMovementSignal(moving: Boolean, atMs: Long) {
        if (moving) {
            stillSinceMs = null
            // Promote immediately rather than waiting for a speed sample: this
            // is the transition the whole feature turns on, and the scan loop
            // uses it to cut a long stationary sleep short.
            if (state == MotionState.STATIONARY) state = MotionState.WALKING
        } else if (stillSinceMs == null) {
            stillSinceMs = atMs
        }
    }

    /** Metres per second, derived from the distance between two scan passes. */
    fun onSpeedSample(speedMps: Double, atMs: Long) {
        lastSpeedMps = speedMps
        lastSpeedAtMs = atMs
    }

    fun state(atMs: Long): MotionState {
        val stillSince = stillSinceMs
        if (stillSince != null) {
            // Leaving a vehicle state takes much longer to confirm. A car at a
            // red light is still for a minute, and dropping it to the 10-minute
            // cadence there would be wrong — worse, a smooth car ride produces
            // little enough accelerometer signal that the sensor might not
            // promptly say "moving" again, stranding a moving survey at the
            // stationary interval.
            if (atMs - stillSince >= dwellMsFor(state)) {
                state = MotionState.STATIONARY
            }
            return state
        }

        val speed = lastSpeedMps?.takeIf { atMs - lastSpeedAtMs <= SPEED_STALE_MS }
        state = when {
            // Moving, but no recent speed to grade it by — the usual cause is
            // being indoors with no position fix. Decay to Walking rather than
            // holding whatever was last believed: it neither burns battery
            // like Driving nor risks missing ground like Stationary, and
            // "moving with no fix" is far more often on foot than in a car.
            speed == null -> MotionState.WALKING
            speed >= DRIVING_ENTER_MPS -> MotionState.DRIVING
            // Hysteresis band, so city driving that dips to walking pace
            // between junctions doesn't flap the cadence back and forth.
            state == MotionState.DRIVING && speed >= DRIVING_EXIT_MPS -> MotionState.DRIVING
            else -> MotionState.WALKING
        }
        return state
    }

    private fun dwellMsFor(current: MotionState): Long =
        if (current == MotionState.DRIVING) STILL_DWELL_FROM_DRIVING_MS else STILL_DWELL_FROM_WALKING_MS

    companion object {
        /** ~16 km/h — comfortably above a run, below any real road speed. */
        const val DRIVING_ENTER_MPS = 4.5

        /** ~11 km/h. Between this and [DRIVING_ENTER_MPS] the current state stands. */
        const val DRIVING_EXIT_MPS = 3.0

        /** Stop trusting a speed reading after this long — you may have parked. */
        const val SPEED_STALE_MS = 180_000L

        const val STILL_DWELL_FROM_WALKING_MS = 90_000L
        const val STILL_DWELL_FROM_DRIVING_MS = 300_000L
    }
}

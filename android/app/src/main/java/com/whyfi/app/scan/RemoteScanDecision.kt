package com.whyfi.app.scan

/**
 * What the phone does with the backend's `remote_scan_enabled` on each
 * heartbeat. Pure, unit-tested (RemoteScanDecisionTest).
 *
 * The phone acts on *changes* of the setting, not on its level. Acting on the
 * level (the original behaviour) meant a loop started on the phone was
 * stopped after one pass whenever remote control was armed with remote
 * scanning off, because every heartbeat repeated "off". It also meant a loop
 * stopped on the phone was restarted by the next heartbeat while remote
 * scanning was on. Whoever acted last wins.
 */
enum class RemoteScanAction {
    /** Start the loop, or update its config if it already runs. */
    START,
    /** The loop runs and the backend still wants it: refresh its config. */
    UPDATE,
    /** The backend switched remote scanning off: stop gracefully. */
    STOP,
    NONE;

    companion object {
        /**
         * @param previous the setting at the last heartbeat that got a policy,
         *   or null for the first one since remote control was armed (then a
         *   wanted scan starts, e.g. after the app restarted).
         * @param looping whether the continuous loop runs now.
         */
        fun decide(previous: Boolean?, wanted: Boolean, looping: Boolean): RemoteScanAction = when {
            wanted && previous != true -> START
            wanted && looping -> UPDATE
            !wanted && previous == true && looping -> STOP
            else -> NONE
        }
    }
}

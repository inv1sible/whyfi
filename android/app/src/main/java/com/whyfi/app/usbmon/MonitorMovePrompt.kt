package com.whyfi.app.usbmon

/** A quiet field prompt only when the receiver has stayed within fix uncertainty. */
object MonitorMovePrompt {
    const val QUIET_MS = 120_000L

    fun moved(anchor: MonitorLocationCandidate?, current: MonitorLocationCandidate?): Boolean =
        anchor != null && current != null && MonitorLocationPolicy.distanceM(anchor, current) >
            maxOf(30.0, anchor.accuracyM + current.accuracyM)

    fun shouldPrompt(nowBootMs: Long, lastInsightBootMs: Long,
                     anchor: MonitorLocationCandidate?, current: MonitorLocationCandidate?): Boolean =
        anchor != null && current != null && !moved(anchor, current) &&
            nowBootMs - lastInsightBootMs >= QUIET_MS
}

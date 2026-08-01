package com.whyfi.app.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Watches how the phone is moving so the scanner can slow down when it isn't.
 *
 * **No Google Play Services.** Its Activity Recognition API would hand us
 * STILL/WALKING/IN_VEHICLE directly, but this project deliberately ships
 * without Play Services (same reason FCM was rejected — see MEMORY.md), so
 * everything here comes from platform sensors.
 *
 * **No new permission.** The sensors used below are all permission-free.
 * `TYPE_STEP_COUNTER` would classify walking more directly but needs
 * `ACTIVITY_RECOGNITION` on API 29+, and asking for a permission named
 * "physical activity" to save battery is a bad trade on a scanner app.
 *
 * Three tiers, best first — [describeSource] reports which one is live so the
 * UI can be honest about the quality of the answer:
 *
 * 1. `STATIONARY_DETECT` + `MOTION_DETECT` (API 24+). Hardware trigger
 *    sensors: they cost essentially nothing and fire on the transition.
 * 2. `SIGNIFICANT_MOTION` for the still→moving edge, plus periodic
 *    accelerometer windows to spot the moving→still edge it can't report.
 * 3. Periodic accelerometer windows alone.
 *
 * The accelerometer is never left registered: it samples for
 * [ACCEL_WINDOW_MS] out of every [ACCEL_PERIOD_MS], which is what keeps the
 * fallback tiers from spending the battery the feature is meant to save.
 */
class MotionDetector(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Fired when movement is detected after a still period, so the scan loop
     * can abandon a long sleep instead of finishing it. */
    private val onMovementResumed: () -> Unit,
) {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val classifier = MotionClassifier()

    private val _state = MutableStateFlow(MotionState.WALKING)
    val state: StateFlow<MotionState> = _state.asStateFlow()

    private var samplerJob: Job? = null
    private var running = false
    private var source = "not running"

    private val stationaryDetect: Sensor? by lazy { triggerSensor(TYPE_STATIONARY_DETECT) }
    private val motionDetect: Sensor? by lazy { triggerSensor(TYPE_MOTION_DETECT) }
    private val significantMotion: Sensor? by lazy { triggerSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }
    private val accelerometer: Sensor? by lazy { sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    private fun triggerSensor(type: Int): Sensor? = runCatching { sensorManager?.getDefaultSensor(type) }.getOrNull()

    fun describeSource(): String = source

    fun start() {
        if (running || sensorManager == null) return
        running = true

        val hasTierOne = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            stationaryDetect != null && motionDetect != null
        source = when {
            hasTierOne -> "motion sensors"
            significantMotion != null -> "significant motion + accelerometer"
            accelerometer != null -> "accelerometer"
            else -> "unavailable"
        }

        if (hasTierOne) {
            armStationaryDetect()
            armMotionDetect()
            return
        }
        if (significantMotion != null) armSignificantMotion()
        // Both remaining tiers need the periodic accelerometer window: on tier
        // 2 it supplies the moving→still edge, on tier 3 it supplies both.
        if (accelerometer != null) startAccelerometerSampler()
        if (accelerometer == null && significantMotion == null) {
            // Nothing to detect with. Report Walking forever rather than
            // silently pinning the survey at the 10-minute cadence.
            publish(MotionState.WALKING)
        }
    }

    fun stop() {
        running = false
        samplerJob?.cancel()
        samplerJob = null
        runCatching {
            sensorManager?.cancelTriggerSensor(stationaryListener, stationaryDetect)
            sensorManager?.cancelTriggerSensor(motionListener, motionDetect)
            sensorManager?.cancelTriggerSensor(significantMotionListener, significantMotion)
        }
        source = "not running"
    }

    /**
     * Feeds in the distance covered between two scan passes.
     *
     * Speed comes from positions the scanner already takes, never from turning
     * the GPS on to answer this question — a phone that woke the GPS every
     * minute to confirm it was still parked would cost more than the cadence
     * saves.
     */
    fun onPassLocation(lat: Double, lng: Double, atMs: Long) {
        val previous = lastPosition
        lastPosition = Triple(lat, lng, atMs)
        if (previous == null) return

        val elapsedSeconds = (atMs - previous.third) / 1000.0
        if (elapsedSeconds < MIN_SPEED_SAMPLE_SECONDS) return

        val results = FloatArray(1)
        Location.distanceBetween(previous.first, previous.second, lat, lng, results)
        val metres = results[0].toDouble()
        // GPS jitter alone can fake a few metres between fixes. Below this the
        // reading says nothing, and feeding it in would only add noise.
        if (metres < MIN_SPEED_SAMPLE_METRES) return

        classifier.onSpeedSample(metres / elapsedSeconds, atMs)
        publish(classifier.state(atMs))
    }

    private var lastPosition: Triple<Double, Double, Long>? = null

    // --- Tier 1: hardware trigger sensors ---------------------------------
    // One-shot by definition, so each listener re-arms its counterpart: after
    // "it went still" the only transition worth watching for is "it moved".

    private val stationaryListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            signal(moving = false)
            if (running) armMotionDetect()
        }
    }

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            signal(moving = true)
            if (running) armStationaryDetect()
        }
    }

    private val significantMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            signal(moving = true)
            if (running) armSignificantMotion()
        }
    }

    private fun armStationaryDetect() {
        runCatching { sensorManager?.requestTriggerSensor(stationaryListener, stationaryDetect) }
    }

    private fun armMotionDetect() {
        runCatching { sensorManager?.requestTriggerSensor(motionListener, motionDetect) }
    }

    private fun armSignificantMotion() {
        runCatching { sensorManager?.requestTriggerSensor(significantMotionListener, significantMotion) }
    }

    // --- Tiers 2 and 3: duty-cycled accelerometer -------------------------

    private fun startAccelerometerSampler() {
        samplerJob = scope.launch {
            while (isActive && running) {
                val moving = sampleIsMoving()
                if (moving != null) signal(moving)
                delay(ACCEL_PERIOD_MS)
            }
        }
    }

    /** Registers the accelerometer just long enough to measure how much the
     * total acceleration varies, then unregisters. Null if nothing arrived. */
    private suspend fun sampleIsMoving(): Boolean? {
        val sensor = accelerometer ?: return null
        val magnitudes = mutableListOf<Double>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                magnitudes += sqrt((x * x + y * y + z * z).toDouble())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        try {
            delay(ACCEL_WINDOW_MS)
        } finally {
            sensorManager?.unregisterListener(listener)
        }

        if (magnitudes.size < 8) return null
        // Standard deviation of |a|, which is gravity-invariant: a phone lying
        // on a desk and one standing upright both read ~9.81 with ~0 variation,
        // so there's no need to know the orientation.
        val mean = magnitudes.average()
        val deviation = sqrt(magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size)
        return deviation >= STILL_DEVIATION_THRESHOLD
    }

    // --- Shared -----------------------------------------------------------

    private fun signal(moving: Boolean) {
        val now = System.currentTimeMillis()
        val wasStationary = _state.value == MotionState.STATIONARY
        classifier.onMovementSignal(moving, now)
        val next = classifier.state(now)
        publish(next)
        if (wasStationary && next != MotionState.STATIONARY) onMovementResumed()
    }

    private fun publish(next: MotionState) {
        _state.value = next
    }

    /** Re-evaluates dwell timers, which only elapse with the clock. Called by
     * the scan loop before it picks an interval. */
    fun refresh() {
        publish(classifier.state(System.currentTimeMillis()))
    }

    companion object {
        // Sensor.TYPE_STATIONARY_DETECT / TYPE_MOTION_DETECT are API 24
        // constants; spelled out so this file compiles against minSdk 28
        // without a version guard on the constant itself.
        private const val TYPE_STATIONARY_DETECT = 29
        private const val TYPE_MOTION_DETECT = 30

        const val ACCEL_WINDOW_MS = 3_000L
        const val ACCEL_PERIOD_MS = 30_000L

        /** m/s² of variation in |a|. Below this the phone is on a surface. */
        const val STILL_DEVIATION_THRESHOLD = 0.22

        const val MIN_SPEED_SAMPLE_SECONDS = 5.0
        const val MIN_SPEED_SAMPLE_METRES = 15.0
    }
}

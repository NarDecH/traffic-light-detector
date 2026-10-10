package com.example.trafficlightdetector.domain

import com.example.trafficlightdetector.ml.Detection
import com.example.trafficlightdetector.ml.TrafficLightClass

/**
 * State machine that decides **when to beep once** for a green light.
 *
 * Requirements it implements
 * --------------------------
 * 1. A detection only counts when `confidence > confidenceThreshold`
 *    (default `0.70f`, i.e. "> 70%").
 * 2. Debounce: the green light must be seen in [confirmFrames] consecutive
 *    analysed frames before it counts. Isolated false positives never trigger.
 * 3. One alarm per green phase: after firing, the gate latches into
 *    [State.GREEN_ACTIVE] and stays silent until the light has been absent for
 *    [releaseMissFrames] consecutive frames, which re-arms it ([State.ARMED]).
 * 4. Safety net: [minAlarmIntervalMillis] is the shortest time between two
 *    alarms, so a flickering detector can never produce an endless beep loop.
 *
 * The class is deliberately free of Android / coroutine dependencies: it is
 * plain Kotlin so it can be unit tested frame by frame (see `GreenLightGateTest`).
 * It is used from the analyzer thread only, like the detector itself.
 */
class GreenLightGate(
    confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val releaseMissFrames: Int = DEFAULT_RELEASE_MISS_FRAMES,
    private val minAlarmIntervalMillis: Long = DEFAULT_MIN_ALARM_INTERVAL_MILLIS,
) {

    enum class State {
        /** No green light confirmed: the next confirmed green light raises the alarm. */
        ARMED,

        /** Green light already confirmed and announced: stays silent until it goes away. */
        GREEN_ACTIVE,
    }

    /** Why [evaluate] did or did not raise an alarm — handy in logs and tests. */
    enum class Reason {
        /** Alarm raised: green light newly confirmed. */
        GREEN_CONFIRMED,

        /** Green seen, but fewer than [confirmFrames] consecutive frames so far. */
        CONFIRMING,

        /** Green already announced; still waiting for it to disappear. */
        ALREADY_ALARMED,

        /** Green newly confirmed but [minAlarmIntervalMillis] had not elapsed. */
        COOLDOWN,

        /** Green disappeared long enough to re-arm the gate. */
        RE_ARMED,

        /** No green light above the threshold in this frame. */
        NO_GREEN,
    }

    data class Decision(
        /** `true` exactly once per green-light phase. */
        val alarm: Boolean,
        val state: State,
        val reason: Reason,
        /** Confidence of the best green detection in this frame, if any. */
        val greenConfidence: Float?,
    )

    /** Confidence a `green_light` detection must **exceed** to count. */
    var confidenceThreshold: Float = confidenceThreshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        set(value) {
            field = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        }

    var state: State = State.ARMED
        private set

    /** Number of alarms raised since construction / [reset]. */
    var alarmCount: Int = 0
        private set

    var lastAlarmAtMillis: Long? = null
        private set

    /** Confidence of the best green detection in the last evaluated frame. */
    var lastGreenConfidence: Float? = null
        private set

    private var consecutiveGreenFrames = 0
    private var consecutiveMissFrames = 0

    /**
     * Feeds one analysed frame into the machine.
     *
     * @param detections detections of a single frame (may be empty)
     * @param nowMillis monotonic timestamp, e.g. `SystemClock.elapsedRealtime()`
     */
    fun evaluate(detections: List<Detection>, nowMillis: Long): Decision {
        val bestGreen = detections
            .filter { it.trafficLightClass == TrafficLightClass.GREEN_LIGHT }
            .maxByOrNull { it.confidence }
        lastGreenConfidence = bestGreen?.confidence

        // Strictly greater than the threshold: "confidence > 70%".
        val greenIsConfident =
            bestGreen != null && bestGreen.confidence > confidenceThreshold

        if (!greenIsConfident) {
            consecutiveGreenFrames = 0
            consecutiveMissFrames++
            if (state == State.GREEN_ACTIVE && consecutiveMissFrames >= releaseMissFrames) {
                state = State.ARMED
                return Decision(false, state, Reason.RE_ARMED, lastGreenConfidence)
            }
            return Decision(false, state, Reason.NO_GREEN, lastGreenConfidence)
        }

        consecutiveMissFrames = 0

        when (state) {
            State.GREEN_ACTIVE ->
                return Decision(false, state, Reason.ALREADY_ALARMED, lastGreenConfidence)

            State.ARMED -> {
                consecutiveGreenFrames++
                if (consecutiveGreenFrames < confirmFrames) {
                    return Decision(false, state, Reason.CONFIRMING, lastGreenConfidence)
                }
                // Confirmed: latch immediately so the next frame cannot fire again.
                consecutiveGreenFrames = 0
                state = State.GREEN_ACTIVE

                val sinceLastAlarm = lastAlarmAtMillis?.let { nowMillis - it }
                if (sinceLastAlarm != null && sinceLastAlarm < minAlarmIntervalMillis) {
                    return Decision(false, state, Reason.COOLDOWN, lastGreenConfidence)
                }
                lastAlarmAtMillis = nowMillis
                alarmCount++
                return Decision(true, state, Reason.GREEN_CONFIRMED, lastGreenConfidence)
            }
        }
    }

    /** Re-arms the gate, e.g. when the user turns the alarm back on. */
    fun reset() {
        state = State.ARMED
        consecutiveGreenFrames = 0
        consecutiveMissFrames = 0
        lastAlarmAtMillis = null
        lastGreenConfidence = null
    }

    companion object {
        /** Requirement: alarm above 70% confidence. */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.70f
        /** Frames of agreement required before the first alarm. */
        const val DEFAULT_CONFIRM_FRAMES = 3
        /** Frames without green required before the gate re-arms. */
        const val DEFAULT_RELEASE_MISS_FRAMES = 4
        /** Shortest interval between two alarms (flicker protection). */
        const val DEFAULT_MIN_ALARM_INTERVAL_MILLIS = 5_000L

        private const val MIN_THRESHOLD = 0.05f
        private const val MAX_THRESHOLD = 0.99f
    }
}

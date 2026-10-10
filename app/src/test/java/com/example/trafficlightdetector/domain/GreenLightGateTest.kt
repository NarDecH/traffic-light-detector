package com.example.trafficlightdetector.domain

import com.example.trafficlightdetector.ml.Detection
import com.example.trafficlightdetector.ml.TrafficLightClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame-by-frame tests of the alarm rules:
 * confidence > 70%, 3 consecutive frames to confirm, one alarm per green phase,
 * re-arm only after the green light has been gone for 4 frames, 5 s cooldown.
 */
class GreenLightGateTest {

    @Test
    fun `alarms once after three consecutive confident frames`() {
        assertEquals(listOf(2), runSequence(0.8f, 0.8f, 0.8f))
    }

    @Test
    fun `stays silent when confidence never exceeds the threshold`() {
        // 0.70 is exactly the threshold: the requirement is "more than 70%".
        assertEquals(emptyList<Int>(), runSequence(0.70f, 0.70f, 0.70f, 0.70f, 0.70f))
        assertEquals(emptyList<Int>(), runSequence(0.69f, 0.55f, 0.30f, 0.69f))
    }

    @Test
    fun `confirmation requires consecutive frames`() {
        // Frame 1 and 2 are green, frame 3 is not: the streak restarts.
        assertEquals(listOf(4), runSequence(0.8f, 0.8f, 0.6f, 0.8f, 0.8f, 0.8f))
    }

    @Test
    fun `alarms only once per green phase no matter how many frames follow`() {
        val twentyGreenFrames = Array<Float?>(20) { 0.95f }
        assertEquals(listOf(2), runSequence(*twentyGreenFrames))
    }

    @Test
    fun `re-arms after the light disappears and the cooldown elapsed`() {
        val gate = GreenLightGate()
        var now = 0L
        fun frame(confidence: Float?): Boolean {
            now += 1_000L
            val detections = confidence?.let { listOf(greenDetection(it)) } ?: emptyList()
            return gate.evaluate(detections, now).alarm
        }

        assertFalse(frame(0.9f))
        assertFalse(frame(0.9f))
        assertTrue(frame(0.9f)) // t = 3000 ms, first alarm

        // Light turns red for four frames -> gate re-arms.
        repeat(4) { assertFalse(frame(null)) }
        assertEquals(GreenLightGate.State.ARMED, gate.state)

        // Next green cycle is far enough after the first alarm -> alarm again.
        assertFalse(frame(0.9f))
        assertFalse(frame(0.9f))
        assertTrue(frame(0.9f)) // t = 10000 ms > 3000 + 5000
        assertEquals(2, gate.alarmCount)
    }

    @Test
    fun `cooldown blocks a second alarm when the light flickers back quickly`() {
        val gate = GreenLightGate()
        var now = 0L
        fun frame(confidence: Float?): GreenLightGate.Decision {
            now += 100L
            val detections = confidence?.let { listOf(greenDetection(it)) } ?: emptyList()
            return gate.evaluate(detections, now)
        }

        frame(0.9f); frame(0.9f)
        assertTrue(frame(0.9f).alarm) // t = 300 ms

        repeat(4) { frame(null) } // re-armed at t = 700 ms
        assertEquals(GreenLightGate.State.ARMED, gate.state)

        frame(0.9f); frame(0.9f)
        val decision = frame(0.9f) // t = 1000 ms, only 700 ms after the alarm
        assertFalse(decision.alarm)
        assertEquals(GreenLightGate.Reason.COOLDOWN, decision.reason)
        assertEquals(1, gate.alarmCount)
    }

    @Test
    fun `reports continued green as already alarmed`() {
        val gate = GreenLightGate()
        var now = 0L
        fun frame(): GreenLightGate.Decision {
            now += 100L
            return gate.evaluate(listOf(greenDetection(0.9f)), now)
        }

        frame(); frame()
        val alarm = frame()
        assertTrue(alarm.alarm)
        assertEquals(GreenLightGate.Reason.GREEN_CONFIRMED, alarm.reason)

        val next = frame()
        assertFalse(next.alarm)
        assertEquals(GreenLightGate.Reason.ALREADY_ALARMED, next.reason)
        assertEquals(1, gate.alarmCount)
    }

    @Test
    fun `ignores red and yellow detections`() {
        val gate = GreenLightGate()
        var now = 0L
        repeat(10) {
            now += 100L
            val decision = gate.evaluate(
                listOf(
                    detection(TrafficLightClass.RED_LIGHT, 0.99f),
                    detection(TrafficLightClass.YELLOW_LIGHT, 0.95f),
                ),
                now,
            )
            assertFalse(decision.alarm)
        }
        assertEquals(0, gate.alarmCount)
        assertEquals(GreenLightGate.State.ARMED, gate.state)
    }

    @Test
    fun `picks the most confident green box of a frame`() {
        val gate = GreenLightGate()
        var now = 0L
        repeat(3) {
            now += 100L
            gate.evaluate(
                listOf(
                    detection(TrafficLightClass.GREEN_LIGHT, 0.40f),
                    detection(TrafficLightClass.GREEN_LIGHT, 0.91f),
                ),
                now,
            )
        }
        assertEquals(1, gate.alarmCount)
        assertEquals(0.91f, gate.lastGreenConfidence!!, 1e-4f)
    }

    @Test
    fun `threshold setter is clamped to a sane range`() {
        val gate = GreenLightGate()
        gate.confidenceThreshold = 0.9999f
        assertEquals(0.99f, gate.confidenceThreshold, 1e-4f)
        gate.confidenceThreshold = 0f
        assertEquals(0.05f, gate.confidenceThreshold, 1e-4f)
    }

    @Test
    fun `reset re-arms the gate`() {
        val gate = GreenLightGate()
        var now = 0L
        repeat(3) {
            now += 100L
            gate.evaluate(listOf(greenDetection(0.9f)), now)
        }
        assertEquals(1, gate.alarmCount)

        gate.reset()
        assertEquals(GreenLightGate.State.ARMED, gate.state)

        repeat(3) {
            now += 100L
            gate.evaluate(listOf(greenDetection(0.9f)), now)
        }
        assertEquals(2, gate.alarmCount)
    }

    // ------------------------------------------------------------- helpers ---

    /** Feeds a sequence of confidences (null = no green light) 100 ms apart. */
    private fun runSequence(vararg confidences: Float?): List<Int> {
        val gate = GreenLightGate()
        val alarmedFrames = mutableListOf<Int>()
        var now = 0L
        confidences.forEachIndexed { index, confidence ->
            now += 100L
            val detections = confidence?.let { listOf(greenDetection(it)) } ?: emptyList()
            if (gate.evaluate(detections, now).alarm) alarmedFrames += index
        }
        return alarmedFrames
    }

    private fun greenDetection(confidence: Float): Detection =
        detection(TrafficLightClass.GREEN_LIGHT, confidence)

    private fun detection(lightClass: TrafficLightClass, confidence: Float): Detection =
        Detection(
            trafficLightClass = lightClass,
            label = lightClass.modelLabel,
            classIndex = lightClass.ordinal,
            confidence = confidence,
            left = 0.4f,
            top = 0.4f,
            right = 0.6f,
            bottom = 0.6f,
        )
}

package com.example.trafficlightdetector.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonMaxSuppressionTest {

    @Test
    fun `intersection over union of identical boxes is one`() {
        val box = detection(TrafficLightClass.RED_LIGHT, 0.9f, 0f, 0f, 0.5f, 0.5f)
        assertEquals(1f, box.intersectionOverUnion(box), 1e-5f)
    }

    @Test
    fun `intersection over union of disjoint boxes is zero`() {
        val a = detection(TrafficLightClass.RED_LIGHT, 0.9f, 0f, 0f, 0.2f, 0.2f)
        val b = detection(TrafficLightClass.RED_LIGHT, 0.8f, 0.5f, 0.5f, 0.8f, 0.8f)
        assertEquals(0f, a.intersectionOverUnion(b), 1e-5f)
    }

    @Test
    fun `intersection over union of half overlapping boxes`() {
        val a = detection(TrafficLightClass.RED_LIGHT, 0.9f, 0f, 0f, 1f, 1f)
        val b = detection(TrafficLightClass.RED_LIGHT, 0.8f, 0.5f, 0f, 1.5f, 1f)
        // intersection = 0.5, union = 1.5 -> 1/3
        assertEquals(1f / 3f, a.intersectionOverUnion(b), 1e-4f)
    }

    @Test
    fun `overlapping boxes of the same class collapse to the most confident one`() {
        val detections = listOf(
            detection(TrafficLightClass.GREEN_LIGHT, 0.60f, 0f, 0f, 0.4f, 0.4f),
            detection(TrafficLightClass.GREEN_LIGHT, 0.95f, 0.02f, 0.02f, 0.42f, 0.42f),
            detection(TrafficLightClass.GREEN_LIGHT, 0.70f, 0.02f, 0.02f, 0.41f, 0.41f),
        )
        val kept = NonMaxSuppression.suppress(detections, iouThreshold = 0.3f, maxDetections = 10)
        assertEquals(1, kept.size)
        assertEquals(0.95f, kept.first().confidence, 1e-5f)
    }

    @Test
    fun `boxes of different classes are never suppressed against each other`() {
        val detections = listOf(
            detection(TrafficLightClass.RED_LIGHT, 0.90f, 0f, 0f, 0.4f, 0.4f),
            detection(TrafficLightClass.GREEN_LIGHT, 0.80f, 0f, 0f, 0.4f, 0.4f),
        )
        val kept = NonMaxSuppression.suppress(detections, iouThreshold = 0.3f, maxDetections = 10)
        assertEquals(2, kept.size)
    }

    @Test
    fun `results are sorted by confidence and capped`() {
        val detections = (1..5).map { index ->
            detection(
                trafficLightClass = TrafficLightClass.YELLOW_LIGHT,
                confidence = index / 10f,
                // Far apart so NMS keeps everything.
                left = index / 20f,
                top = 0f,
                right = index / 20f + 0.02f,
                bottom = 0.02f,
            )
        }
        val kept = NonMaxSuppression.suppress(detections, iouThreshold = 0.5f, maxDetections = 3)
        assertEquals(3, kept.size)
        assertTrue(kept[0].confidence > kept[1].confidence)
        assertTrue(kept[1].confidence > kept[2].confidence)
        assertEquals(0.5f, kept.first().confidence, 1e-5f)
    }

    @Test
    fun `empty input or zero budget returns nothing`() {
        assertTrue(NonMaxSuppression.suppress(emptyList()).isEmpty())
        val detections = listOf(detection(TrafficLightClass.RED_LIGHT, 0.9f, 0f, 0f, 0.2f, 0.2f))
        assertTrue(NonMaxSuppression.suppress(detections, maxDetections = 0).isEmpty())
    }

    private fun detection(
        trafficLightClass: TrafficLightClass,
        confidence: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = Detection(
        trafficLightClass = trafficLightClass,
        label = trafficLightClass.modelLabel,
        classIndex = trafficLightClass.ordinal,
        confidence = confidence,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )
}

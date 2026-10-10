package com.example.trafficlightdetector.domain

import com.example.trafficlightdetector.ml.Detection

/**
 * One processed analysis frame.
 *
 * The bitmap itself is deliberately *not* part of this object: it is recycled
 * right after inference, and the UI only needs the normalised boxes plus the
 * frame size to draw them.
 */
data class DetectionFrame(
    val detections: List<Detection>,
    /** Width of the upright frame the detections are normalised against. */
    val imageWidth: Int,
    val imageHeight: Int,
    /** Wall time spent inside `TrafficLightDetector.detect()`. */
    val inferenceTimeMillis: Long,
    /** `SystemClock.elapsedRealtime()` when the frame was analysed. */
    val timestampMillis: Long,
)

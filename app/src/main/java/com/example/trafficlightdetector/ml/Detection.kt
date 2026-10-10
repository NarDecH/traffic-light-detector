package com.example.trafficlightdetector.ml

/**
 * A single detected traffic light.
 *
 * Coordinates are **normalised to the upright analysis frame** (`0f..1f`, origin
 * top-left), so the overlay can blit them into any view size without knowing the
 * camera resolution. `left/top/right/bottom` are always clamped to that range.
 */
data class Detection(
    val trafficLightClass: TrafficLightClass,
    /** Label string as reported by the model's label map. */
    val label: String,
    /** Index into `labels.txt`. */
    val classIndex: Int,
    /** Confidence in `0f..1f`. */
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** Confidence as a rounded percentage, for labels such as "GREEN 92%". */
    val confidencePercent: Int get() = (confidence * 100f + 0.5f).toInt()

    /** Draw label, e.g. `GREEN 92%`. */
    val displayLabel: String get() = "${trafficLightClass.displayName} $confidencePercent%"
}

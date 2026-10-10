package com.example.trafficlightdetector.ml

/**
 * Greedy, per-class non-maximum suppression.
 *
 * Most TFLite detection exports already run NMS inside the graph, but running a
 * cheap second pass here keeps the overlay clean for models that do not (and
 * because a "duplicate" box would also trigger a second alarm decision).
 */
object NonMaxSuppression {

    /**
     * @param detections raw detections for one frame (normalised coordinates)
     * @param iouThreshold boxes of the same class overlapping more than this are merged
     * @param maxDetections upper bound of returned detections
     */
    fun suppress(
        detections: List<Detection>,
        iouThreshold: Float = 0.5f,
        maxDetections: Int = Int.MAX_VALUE,
    ): List<Detection> {
        if (detections.isEmpty() || maxDetections <= 0) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val kept = ArrayList<Detection>(minOf(sorted.size, maxDetections))
        for (candidate in sorted) {
            val suppressed = kept.any { keptBox ->
                keptBox.trafficLightClass == candidate.trafficLightClass &&
                    keptBox.intersectionOverUnion(candidate) > iouThreshold
            }
            if (!suppressed) {
                kept += candidate
                if (kept.size == maxDetections) break
            }
        }
        return kept
    }
}

/** Intersection over union of two detections in normalised coordinates. */
fun Detection.intersectionOverUnion(other: Detection): Float {
    val intersectionWidth = minOf(right, other.right) - maxOf(left, other.left)
    val intersectionHeight = minOf(bottom, other.bottom) - maxOf(top, other.top)
    if (intersectionWidth <= 0f || intersectionHeight <= 0f) return 0f
    val intersection = intersectionWidth * intersectionHeight
    val union = area + other.area - intersection
    return if (union <= 0f) 0f else intersection / union
}

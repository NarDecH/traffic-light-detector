package com.example.trafficlightdetector.domain

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.trafficlightdetector.camera.toUprightBitmap
import com.example.trafficlightdetector.ml.TrafficLightDetector

/**
 * The CameraX [ImageAnalysis.Analyzer] that drives inference.
 *
 * This is the only class that knows about both CameraX and TFLite; it does not
 * hold UI state. Results are handed to the MVVM layer through [onResult], which
 * the `MainViewModel` wires to its state machine.
 *
 * @param detectorProvider returns the current detector, or `null` while the
 *   model is still loading (frames are dropped instead of blocking the camera).
 * @param minIntervalMillis analysis is throttled to keep the CPU cool; the
 *   camera still drops frames through `STRATEGY_KEEP_ONLY_LATEST`.
 */
class TrafficLightAnalyzer(
    private val detectorProvider: () -> TrafficLightDetector?,
    private val onResult: (DetectionFrame) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val minIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedAtMillis = Long.MIN_VALUE / 2

    override fun analyze(image: ImageProxy) {
        try {
            val detector = detectorProvider()
            val now = clock()
            if (detector == null || now - lastAnalyzedAtMillis < minIntervalMillis) return
            lastAnalyzedAtMillis = now

            val bitmap = image.toUprightBitmap()
            val startedAt = clock()
            val detections = detector.detect(bitmap)
            val inferenceMillis = clock() - startedAt

            val frame = DetectionFrame(
                detections = detections,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                inferenceTimeMillis = inferenceMillis,
                timestampMillis = now,
            )
            bitmap.recycle()
            onResult(frame)
        } catch (t: Throwable) {
            // A single bad frame must never kill the camera pipeline.
            onError(t)
        } finally {
            // Closing the proxy hands the buffer back to CameraX.
            image.close()
        }
    }

    private companion object {
        /** ~10 inferences/second: plenty for traffic lights, gentle on battery. */
        const val DEFAULT_INTERVAL_MILLIS = 100L
    }
}

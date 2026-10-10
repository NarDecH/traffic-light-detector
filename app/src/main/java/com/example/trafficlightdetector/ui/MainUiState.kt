package com.example.trafficlightdetector.ui

import com.example.trafficlightdetector.domain.GreenLightGate
import com.example.trafficlightdetector.ml.Detection

/** Lifecycle of the TFLite model. */
enum class DetectorStatus { Loading, Running, Error }

/**
 * Everything the screen needs to render, as one immutable snapshot.
 *
 * The View exposes this object; it never reads from the detector or the camera
 * directly, which is what keeps the MVVM boundary intact.
 */
data class MainUiState(
    val detectorStatus: DetectorStatus = DetectorStatus.Loading,
    /** Active TFLite backend, e.g. `GPU` / `CPU x4`. */
    val acceleration: String = "",
    /** Detections of the latest analysed frame, in normalised coordinates. */
    val detections: List<Detection> = emptyList(),
    /** Size of the upright frame the detections are normalised against. */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** Confidence of the best green detection, in percent, or `null` if none. */
    val greenConfidencePercent: Int? = null,
    val gateState: GreenLightGate.State = GreenLightGate.State.ARMED,
    val alarmEnabled: Boolean = true,
    val confidenceThresholdPercent: Int = 70,
    /** How many times the warning has fired since the app started. */
    val alarmCount: Int = 0,
    val inferenceTimeMillis: Long = 0L,
    val framesPerSecond: Float = 0f,
    val errorMessage: String? = null,
) {
    /** `true` while a confirmed green light is being announced. */
    val greenLightActive: Boolean get() = gateState == GreenLightGate.State.GREEN_ACTIVE
}

package com.example.trafficlightdetector.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafficlightdetector.domain.DetectionFrame
import com.example.trafficlightdetector.domain.GreenLightGate
import com.example.trafficlightdetector.domain.TrafficLightAnalyzer
import com.example.trafficlightdetector.ml.TrafficLightDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The "VM" of MVVM: owns the model lifecycle, runs the alarm decision and
 * publishes immutable state.
 *
 * Responsibilities
 * ----------------
 * * loads [TrafficLightDetector] off the main thread and exposes
 *   [DetectorStatus] instead of throwing at the Activity;
 * * receives analysed frames from [TrafficLightAnalyzer] (the camera glue lives
 *   outside the ViewModel and only calls back into it);
 * * runs [GreenLightGate] — the confidence threshold + debounce state machine;
 * * emits a **one-shot** [alarmEvents] signal per green-light phase. Playing the
 *   sound is the View's job, so this class stays free of audio code.
 *
 * Threading: `onFrameAnalyzed` runs on the CameraX analysis thread (one frame at
 * a time), everything else on the main thread.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /**
     * "Play the warning" events. Hot but replay-less: an alarm fired while the
     * app is in the background is dropped rather than played late. The single
     * buffer slot plus DROP_OLDEST keeps a burst from queueing up.
     */
    private val _alarmEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val alarmEvents: SharedFlow<Unit> = _alarmEvents.asSharedFlow()

    private val gate = GreenLightGate()

    @Volatile
    private var detector: TrafficLightDetector? = null

    /** Read from the analysis thread; mirrors [MainUiState.alarmEnabled]. */
    @Volatile
    private var alarmEnabled: Boolean = true

    /** Handed to CameraX by the Activity; it is just a frame callback. */
    val analyzer: TrafficLightAnalyzer = TrafficLightAnalyzer(
        detectorProvider = { detector },
        onResult = ::onFrameAnalyzed,
        onError = { error -> Log.w(TAG, "Frame dropped", error) },
    )

    private var lastFrameAtMillis: Long? = null
    private var smoothedIntervalMillis: Float = 0f

    init {
        loadDetector()
    }

    // ------------------------------------------------------------- detector ---

    private fun loadDetector() {
        viewModelScope.launch {
            _uiState.update { it.copy(detectorStatus = DetectorStatus.Loading, errorMessage = null) }
            try {
                val created = withContext(Dispatchers.IO) {
                    // Loads the .tflite flatbuffer, builds the interpreter and
                    // binds the GPU/NNAPI/CPU backend.
                    TrafficLightDetector.create(getApplication(), TrafficLightDetector.Config())
                }
                detector = created
                _uiState.update {
                    it.copy(
                        detectorStatus = DetectorStatus.Running,
                        acceleration = created.accelerationDescription,
                        // Usually empty; when GPU/NNAPI was rejected this is what
                        // explains the slower backend on screen.
                        acceleratorFallbacks = created.acceleratorFallbacks,
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Model could not be loaded", t)
                _uiState.update {
                    it.copy(
                        detectorStatus = DetectorStatus.Error,
                        errorMessage = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    // -------------------------------------------------------- frame handling ---

    /**
     * Called for every analysed frame: runs the alarm gate, then publishes state.
     */
    private fun onFrameAnalyzed(frame: DetectionFrame) {
        val decision = gate.evaluate(frame.detections, frame.timestampMillis)

        // The gate advances regardless of the mute switch so that un-muting
        // never replays an alarm for a green light the app already passed.
        if (decision.alarm && alarmEnabled) {
            _alarmEvents.tryEmit(Unit)
        } else if (decision.alarm) {
            Log.i(TAG, "Green light confirmed but the alarm is muted")
        }

        updateFrameRate(frame.timestampMillis)

        val framesPerSecond = if (smoothedIntervalMillis > 0f) {
            1000f / smoothedIntervalMillis
        } else {
            0f
        }

        _uiState.update { state ->
            state.copy(
                detectorStatus = DetectorStatus.Running,
                detections = frame.detections,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                greenConfidencePercent = decision.greenConfidence?.let { (it * 100f).roundToInt() },
                gateState = decision.state,
                alarmCount = gate.alarmCount,
                inferenceTimeMillis = frame.inferenceTimeMillis,
                framesPerSecond = framesPerSecond,
            )
        }
    }

    /** Exponential moving average of the frame interval, to keep the FPS steady. */
    private fun updateFrameRate(nowMillis: Long) {
        val previous = lastFrameAtMillis
        if (previous != null) {
            val interval = (nowMillis - previous).toFloat()
            smoothedIntervalMillis = if (smoothedIntervalMillis <= 0f) {
                interval
            } else {
                smoothedIntervalMillis * 0.8f + interval * 0.2f
            }
        }
        lastFrameAtMillis = nowMillis
    }

    // ------------------------------------------------------------- commands ---

    /** Mutes / unmutes the warning. The gate keeps tracking the light either way. */
    fun setAlarmEnabled(enabled: Boolean) {
        alarmEnabled = enabled
        _uiState.update { it.copy(alarmEnabled = enabled) }
    }

    /** Updates the "> N%" requirement, clamped by [GreenLightGate.confidenceThreshold]. */
    fun setConfidenceThresholdPercent(percent: Int) {
        val clamped = percent.coerceIn(MIN_THRESHOLD_PERCENT, MAX_THRESHOLD_PERCENT)
        gate.confidenceThreshold = clamped / 100f
        _uiState.update { it.copy(confidenceThresholdPercent = clamped) }
    }

    override fun onCleared() {
        super.onCleared()
        detector?.close()
        detector = null
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val MIN_THRESHOLD_PERCENT = 55
        const val MAX_THRESHOLD_PERCENT = 99
    }
}

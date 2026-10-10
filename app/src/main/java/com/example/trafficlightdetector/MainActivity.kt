package com.example.trafficlightdetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.trafficlightdetector.alarm.Alerter
import com.example.trafficlightdetector.alarm.ToneAlerter
import com.example.trafficlightdetector.camera.CameraController
import com.example.trafficlightdetector.databinding.ActivityMainBinding
import com.example.trafficlightdetector.ml.TrafficLightClass
import com.example.trafficlightdetector.ui.DetectorStatus
import com.example.trafficlightdetector.ui.MainUiState
import com.example.trafficlightdetector.ui.MainViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The "View" of MVVM. It does exactly three things:
 *
 * 1. requests the camera permission and hands the `PreviewView` to
 *    [CameraController] together with the ViewModel's analyzer;
 * 2. renders [MainUiState] (bounding boxes, counters, controls) — the detection
 *    decision itself already happened in the ViewModel;
 * 3. plays the alarm when the ViewModel emits it, via [Alerter].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    /** Owned by the View, never by the ViewModel: audio is a platform side effect. */
    private val alerter: Alerter by lazy { ToneAlerter(this) }

    private var cameraController: CameraController? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showMessage(getString(R.string.permission_required))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Dash-cam style usage: the screen must stay on while driving.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.alarmSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAlarmEnabled(isChecked)
        }
        binding.thresholdSeekBar.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) viewModel.setConfidenceThresholdPercent(THRESHOLD_MIN_PERCENT + progress)
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            },
        )

        observeViewModel()
        ensureCameraPermission()
    }

    override fun onStart() {
        super.onStart()
        // Re-binds after a trip to the background (or a config change).
        cameraController?.start()
    }

    override fun onStop() {
        super.onStop()
        cameraController?.stop()
        alerter.stop()
    }

    override fun onDestroy() {
        alerter.release()
        cameraController?.release()
        cameraController = null
        super.onDestroy()
    }

    // ----------------------------------------------------------------- MVVM ---

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // One event per green-light phase: the debounce lives in the gate.
                viewModel.alarmEvents.collect { alerter.alert() }
            }
        }
    }

    /** Pure render function: state in, pixels out. No logic about detections. */
    private fun render(state: MainUiState) {
        binding.overlayView.detections = state.detections
        binding.overlayView.imageWidth = state.imageWidth
        binding.overlayView.imageHeight = state.imageHeight
        binding.overlayView.highlightedClass =
            if (state.greenLightActive) TrafficLightClass.GREEN_LIGHT else null

        binding.stateTextView.text = when {
            state.detectorStatus == DetectorStatus.Error -> getString(R.string.state_error)
            state.greenLightActive -> getString(R.string.state_alarm)
            state.detectorStatus == DetectorStatus.Running -> getString(R.string.state_running)
            else -> getString(R.string.state_initialising)
        }
        binding.stateTextView.setTextColor(
            if (state.greenLightActive) GREEN_ACTIVE_COLOR else Color.WHITE,
        )

        binding.detectionTextView.text = if (state.detections.isEmpty()) {
            getString(R.string.detection_placeholder)
        } else {
            state.detections.joinToString(separator = "\n") {
                String.format(Locale.US, "%s %.0f%%", it.label, it.confidence * 100f)
            }
        }

        val green = state.greenConfidencePercent
        binding.perfTextView.text = String.format(
            Locale.US,
            "%.0f ms · %.1f fps · %s · green %s · alarms %d",
            state.inferenceTimeMillis.toFloat(),
            state.framesPerSecond,
            state.acceleration.ifEmpty { "-" },
            green?.let { "$it%" } ?: "-",
            state.alarmCount,
        )

        binding.thresholdLabelTextView.text =
            getString(R.string.threshold_format, state.confidenceThresholdPercent)

        // Keep the controls in sync when state changes from the outside.
        if (binding.alarmSwitch.isChecked != state.alarmEnabled) {
            binding.alarmSwitch.isChecked = state.alarmEnabled
        }
        val expectedProgress = state.confidenceThresholdPercent - THRESHOLD_MIN_PERCENT
        if (binding.thresholdSeekBar.progress != expectedProgress) {
            binding.thresholdSeekBar.progress = expectedProgress
        }

        state.errorMessage?.let(::showMessage) ?: hideMessage()
    }

    // ---------------------------------------------------------------- camera ---

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (cameraController != null) return
        cameraController = CameraController(
            context = this,
            lifecycleOwner = this,
            previewView = binding.previewView,
            analyzer = viewModel.analyzer,
            onError = { error ->
                showMessage(
                    getString(
                        R.string.camera_error,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            },
        ).also { it.start() }
    }

    private fun showMessage(message: String) {
        binding.messageTextView.text = message
        binding.messageTextView.visibility = android.view.View.VISIBLE
    }

    private fun hideMessage() {
        binding.messageTextView.visibility = android.view.View.GONE
    }

    private companion object {
        /**
         * SeekBar range: `progress + THRESHOLD_MIN_PERCENT` (max 44 -> 55..99%).
         * The values mirror `MainViewModel`'s clamping.
         */
        const val THRESHOLD_MIN_PERCENT = 55
        val GREEN_ACTIVE_COLOR = Color.parseColor("#69F0AE")
    }
}

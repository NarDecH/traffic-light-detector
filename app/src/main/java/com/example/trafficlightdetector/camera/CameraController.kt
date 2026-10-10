package com.example.trafficlightdetector.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the CameraX graph: a [Preview] bound to a [PreviewView] plus an
 * [ImageAnalysis] use case that feeds the traffic-light analyzer.
 *
 * Design notes
 * ------------
 * * Preview and analysis share the same 4:3 aspect strategy. Matching aspect
 *   ratios is what lets `OverlayView` map normalised detection coordinates onto
 *   the preview with a single FIT_CENTER transform.
 * * `STRATEGY_KEEP_ONLY_LATEST` guarantees the analyzer never queues frames
 *   behind a slow inference run.
 * * `OUTPUT_IMAGE_FORMAT_RGBA_8888` gives the analyzer real colour bytes
 *   (YUV would be converted again anyway, just further down the pipeline).
 * * Analysis runs on a dedicated single-thread executor, so [analyzer] is never
 *   called concurrently — the TFLite interpreter stays single-threaded.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val analyzer: ImageAnalysis.Analyzer,
    private val onError: (Throwable) -> Unit,
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    /** Binds the use cases to the lifecycle; safe to call after a config change. */
    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider

                    val preview = Preview.Builder()
                        .setResolutionSelector(selectorFor(PREVIEW_SIZE))
                        .build()
                        .apply { surfaceProvider = previewView.surfaceProvider }

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(selectorFor(ANALYSIS_SIZE))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setTargetRotation(previewView.display?.rotation ?: DEFAULT_ROTATION)
                        .build()
                        .apply { setAnalyzer(analysisExecutor, analyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    imageAnalysis = analysis
                } catch (t: Throwable) {
                    Log.e(TAG, "Camera binding failed", t)
                    onError(t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** Unbinds the camera and stops delivering frames (e.g. in `onStop`). */
    fun stop() {
        runCatching { imageAnalysis?.clearAnalyzer() }
        runCatching { cameraProvider?.unbindAll() }
        imageAnalysis = null
    }

    /** Releases the camera and the analysis thread; call from `onDestroy`. */
    fun release() {
        stop()
        analysisExecutor.shutdown()
    }

    private fun selectorFor(target: Size): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()

    private companion object {
        const val TAG = "CameraController"
        val PREVIEW_SIZE = Size(1280, 960)
        val ANALYSIS_SIZE = Size(640, 480)
        const val DEFAULT_ROTATION = android.view.Surface.ROTATION_0
    }
}

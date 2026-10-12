package com.example.trafficlightdetector.ml

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device regression tests for [TrafficLightDetector], running the **real**
 * TFLite runtime against the model in `assets/`.
 *
 * Why these exist: the interpreter reuses one output buffer per tensor between
 * frames, and TFLite's `Tensor.copyTo` writes with `dst.put(...)`, which is
 * bounded by the destination's *remaining* bytes. Forgetting to rewind made
 * every frame after the first fail with `BufferOverflowException` ("Frame
 * dropped") on a physical device, while all JVM tests stayed green. So this
 * test deliberately runs the same frame several times **and** many different
 * frames in a row.
 *
 * Run with a device attached:
 *
 *     gradle :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class TrafficLightDetectorInstrumentedTest {

    private lateinit var detector: TrafficLightDetector

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        detector = TrafficLightDetector.create(
            context,
            // CPU only: delegate behaviour is not what this test is about, and CPU
            // keeps the results identical from run to run.
            TrafficLightDetector.Config(useGpuDelegate = false, useNnApiDelegate = false),
        )
    }

    @After
    fun tearDown() {
        detector.close()
    }

    @Test
    fun consecutiveFramesDoNotOverflowTheOutputBuffers() {
        val frames = (0 until 8).map(::frame)
        try {
            frames.forEachIndexed { index, bitmap ->
                // Before the rewind fix this threw BufferOverflowException from frame 2 on.
                val detections = detector.detect(bitmap)
                assertTrue(
                    "frame $index returned more than maxDetections boxes",
                    detections.size <= MAX_DETECTIONS,
                )
            }
        } finally {
            frames.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun theSameFrameProducesIdenticalDetections() {
        val bitmap = frame(seed = 1)
        try {
            val first = detector.detect(bitmap)
            repeat(3) { run ->
                // A buffer that is not rewound (or that keeps stale bytes) shows up
                // here as a different result, not only as an exception.
                assertEquals("run $run differs from the first run", first, detector.detect(bitmap))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun detectionsRespectTheDetectionContract() {
        val frames = (0 until 4).map(::frame)
        try {
            for (bitmap in frames) {
                for (detection in detector.detect(bitmap)) {
                    assertTrue("confidence out of range: $detection", detection.confidence in 0f..1f)
                    assertTrue("left out of range: $detection", detection.left in 0f..1f)
                    assertTrue("top out of range: $detection", detection.top in 0f..1f)
                    assertTrue("right out of range: $detection", detection.right in 0f..1f)
                    assertTrue("bottom out of range: $detection", detection.bottom in 0f..1f)
                    assertTrue("inverted box: $detection", detection.left <= detection.right)
                    assertTrue("inverted box: $detection", detection.top <= detection.bottom)
                    // The detector drops classes the label map does not know, and
                    // labels.txt declares exactly the three traffic-light classes.
                    assertTrue("unexpected class: $detection", detection.classIndex in 0..2)
                }
            }
        } finally {
            frames.forEach(Bitmap::recycle)
        }
    }

    /** Builds a deterministic [FRAME_SIZE] square frame, different for every seed. */
    private fun frame(seed: Int): Bitmap {
        val pixels = IntArray(FRAME_SIZE * FRAME_SIZE)
        for (y in 0 until FRAME_SIZE) {
            for (x in 0 until FRAME_SIZE) {
                pixels[y * FRAME_SIZE + x] = Color.rgb(
                    (x + seed * 13) % 256,
                    (y + seed * 29) % 256,
                    (x + y + seed * 7) % 256,
                )
            }
        }
        return Bitmap.createBitmap(pixels, FRAME_SIZE, FRAME_SIZE, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        /** Deliberately larger than the model input, to exercise the scaling path. */
        const val FRAME_SIZE = 480

        /** Mirrors `TrafficLightDetector.Config.maxDetections` (the default). */
        const val MAX_DETECTIONS = 10
    }
}

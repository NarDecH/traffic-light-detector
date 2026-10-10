package com.example.trafficlightdetector.ml

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Small numeric helpers shared by the preprocessing (bitmap -> input tensor) and
 * the post-processing (output tensor -> [Detection]) steps.
 *
 * Pure Kotlin on purpose: this is the part of the pipeline that is worth unit
 * testing, and it must not need a device to run.
 */
object TensorMath {

    /**
     * Converts a value stored in an integer tensor back to its float domain:
     * `real = (quantized - zeroPoint) * scale`.
     *
     * Models exported without quantization report `scale == 0f`; the raw value is
     * returned unchanged in that case.
     */
    fun dequantize(quantized: Int, scale: Float, zeroPoint: Int): Float =
        if (scale == 0f) quantized.toFloat() else (quantized - zeroPoint) * scale

    /**
     * Converts a float value into the integer domain of a quantized tensor:
     * `quantized = round(real / scale) + zeroPoint`, clamped to [min, max].
     */
    fun quantize(
        real: Float,
        scale: Float,
        zeroPoint: Int,
        min: Int = 0,
        max: Int = 255,
    ): Int {
        if (scale == 0f) return real.roundToInt().coerceIn(min, max)
        return (real / scale + zeroPoint).roundToInt().coerceIn(min, max)
    }

    /**
     * Some exports (raw SSD anchor heads) emit logits instead of probabilities.
     * Applied only when [Detection]-post-processing decided it is needed.
     */
    fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    /** True when every value is an integer within [epsilon] (class indices, not scores). */
    fun looksLikeIntegerSequence(values: FloatArray, epsilon: Float = 1e-3f): Boolean {
        if (values.isEmpty()) return false
        for (value in values) {
            if (value < 0f) return false
            val nearest = value.roundToInt()
            if (kotlin.math.abs(value - nearest) > epsilon) return false
        }
        return true
    }
}

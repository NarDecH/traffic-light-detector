package com.example.trafficlightdetector.ml

import androidx.annotation.ColorInt

/**
 * The three traffic-light states the model is trained on.
 *
 * [modelLabel] must match the strings in `src/main/assets/labels.txt` (the file
 * itself is what maps a model class index to one of these constants — never the
 * other way round, so a differently ordered label map keeps working).
 */
enum class TrafficLightClass(
    val modelLabel: String,
    /** Short name shown in the UI and the overlay chip. */
    val displayName: String,
    /** Colour used to paint the bounding box and its label chip. */
    @ColorInt val color: Int,
) {
    RED_LIGHT(
        modelLabel = "red_light",
        displayName = "RED",
        color = 0xFFE53935.toInt(),
    ),
    YELLOW_LIGHT(
        modelLabel = "yellow_light",
        displayName = "YELLOW",
        color = 0xFFFBC02D.toInt(),
    ),
    GREEN_LIGHT(
        modelLabel = "green_light",
        displayName = "GREEN",
        color = 0xFF2E7D32.toInt(),
    );

    /** Colour used to draw text on top of [color]. */
    val onColor: Int
        get() = if (this == YELLOW_LIGHT) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    override fun toString(): String = modelLabel

    companion object {
        private val BY_LABEL: Map<String, TrafficLightClass> =
            entries.associateBy { normalise(it.modelLabel) }

        /** Returns the constant matching [label], or `null` for unknown labels. */
        fun fromLabel(label: String): TrafficLightClass? = BY_LABEL[normalise(label)]

        /** Case / separator insensitive comparison key ("Red Light" -> "red_light"). */
        private fun normalise(raw: String): String =
            raw.trim().lowercase().replace('-', '_').replace(' ', '_')
    }
}

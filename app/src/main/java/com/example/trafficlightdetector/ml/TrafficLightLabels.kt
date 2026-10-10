package com.example.trafficlightdetector.ml

import android.content.Context
import android.util.Log

/**
 * Index -> label mapping, loaded from `src/main/assets/labels.txt`.
 *
 * Keeping this in an asset (instead of hard-coding indices) means a model whose
 * label map is ordered differently still maps onto the correct
 * [TrafficLightClass]: the label *string* is the contract.
 */
class TrafficLightLabels private constructor(private val labels: List<String>) {

    val size: Int get() = labels.size

    /** Raw label at [index], or `null` when the model reports an unknown class. */
    fun labelAt(index: Int): String? = labels.getOrNull(index)

    /** Known traffic-light class at [index], or `null` for background/unknown classes. */
    fun classAt(index: Int): TrafficLightClass? =
        labels.getOrNull(index)?.let(TrafficLightClass::fromLabel)

    override fun toString(): String = labels.toString()

    companion object {
        const val ASSET_NAME = "labels.txt"
        private const val TAG = "TrafficLightLabels"

        /** Fallback map used when the asset is missing, so the app still runs. */
        val DEFAULT: TrafficLightLabels =
            TrafficLightLabels(TrafficLightClass.entries.map { it.modelLabel })

        /** Reads `labels.txt` from the APK assets; never throws. */
        fun fromAssets(context: Context): TrafficLightLabels = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                fromLines(reader.readLines())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read $ASSET_NAME, using the default label map", t)
            DEFAULT
        }

        /** Parses label lines; blank lines and `#` comments are ignored. */
        fun fromLines(lines: List<String>): TrafficLightLabels {
            val parsed = lines
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
            return if (parsed.isEmpty()) DEFAULT else TrafficLightLabels(parsed)
        }
    }
}

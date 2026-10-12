package com.example.trafficlightdetector.ml

/**
 * A TFLite accelerator that was tried and rejected while loading the model,
 * together with the reason it was abandoned.
 *
 * [TrafficLightDetector.create] walks GPU -> NNAPI -> CPU and keeps going on
 * failure, which is the right behaviour but hides *why* a device ended up on
 * CPU. Driver problems are per-device (and change with OS updates), so the
 * reasons are published instead of only logged: `MainUiState` carries them to
 * the status panel, where they can be read in the field without attaching adb.
 *
 * Pure data: no Android or TFLite types, so the formatting is unit-testable on
 * the JVM.
 */
data class AcceleratorFallback(
    /** Backend that was rejected, e.g. `GPU` — matches `Attempt.name`. */
    val backend: String,
    /** Why it was rejected, already safe to render (single line, non-empty). */
    val reason: String,
) {
    /** One-line read-out, e.g. `GPU: delegate creation failed`. */
    val description: String get() = "$backend: $reason"

    companion object {
        /**
         * Longest reason kept. Driver errors can be paragraphs long, and the
         * read-out shares the screen with the camera preview.
         */
        const val MAX_REASON_LENGTH = 120

        /** Fallback for the (unlikely) case where even the class name is empty. */
        private const val UNKNOWN_REASON = "unknown error"

        /** Captures a failed backend attempt as renderable text. */
        fun of(backend: String, error: Throwable): AcceleratorFallback =
            AcceleratorFallback(backend = backend, reason = reasonFor(error))

        /**
         * Turns a failure into a reason string.
         *
         * `Throwable.message` is blank for several real driver failures — the
         * GPU delegate throws `IllegalArgumentException` with no text on some
         * devices — and some messages embed newlines. So: first non-blank line,
         * trimmed, truncated, and never empty (an unexplained fallback is
         * exactly what this type exists to prevent).
         */
        internal fun reasonFor(error: Throwable): String {
            val firstLine = error.message
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
            val reason = firstLine.ifEmpty { error.javaClass.simpleName }
            return reason.take(MAX_REASON_LENGTH).ifEmpty { UNKNOWN_REASON }
        }
    }
}

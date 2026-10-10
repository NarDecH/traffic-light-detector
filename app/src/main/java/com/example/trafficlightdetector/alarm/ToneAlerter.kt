package com.example.trafficlightdetector.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * [Alerter] built on [ToneGenerator] — no audio asset needed, low latency, and
 * routed through the alarm stream so the warning stays audible in a car.
 *
 * A burst consists of [beepCount] beeps. Pending beeps are cancelled before a
 * new burst starts, so overlapping calls can never stack into a continuous
 * tone. The state machine that decides *whether* to alert lives in
 * `GreenLightGate`, not here.
 *
 * @param beepDurationMillis length of a single beep
 * @param beepCount how many beeps per alarm (>= 1)
 * @param vibrate also vibrate as a tactile fallback
 */
class ToneAlerter(
    context: Context,
    private val toneType: Int = ToneGenerator.TONE_PROP_BEEP2,
    private val beepDurationMillis: Int = 600,
    private val beepCount: Int = 3,
    private val vibrate: Boolean = true,
    private val volumePercent: Int = ToneGenerator.MAX_VOLUME,
    private val onError: (Throwable) -> Unit = {},
) : Alerter {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var toneGenerator: ToneGenerator? = null
    private var pendingBeep: Runnable? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun alert() {
        val generator = obtainToneGenerator() ?: return
        cancelPendingBeeps()
        requestAudioFocus()

        if (vibrate) vibrate()
        playBeep(generator, index = 0)
    }

    override fun stop() {
        cancelPendingBeeps()
        runCatching { toneGenerator?.stopTone() }
    }

    override fun release() {
        stop()
        runCatching { toneGenerator?.release() }
        toneGenerator = null
        abandonAudioFocus()
    }

    // ----------------------------------------------------------------- audio ---

    private fun playBeep(generator: ToneGenerator, index: Int) {
        try {
            generator.startTone(toneType, beepDurationMillis)
        } catch (t: RuntimeException) {
            Log.e(TAG, "ToneGenerator.startTone failed", t)
            onError(t)
            return
        }
        val nextIndex = index + 1
        if (nextIndex >= beepCount) return
        val next = Runnable { playBeep(generator, nextIndex) }
        pendingBeep = next
        // Small gap between beeps makes the warning recognisable.
        handler.postDelayed(next, beepDurationMillis + BEEP_GAP_MILLIS)
    }

    private fun cancelPendingBeeps() {
        pendingBeep?.let { handler.removeCallbacks(it) }
        pendingBeep = null
    }

    private fun obtainToneGenerator(): ToneGenerator? {
        toneGenerator?.let { return it }
        return try {
            val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ToneGenerator(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    volumePercent,
                )
            } else {
                @Suppress("DEPRECATION")
                ToneGenerator(AudioManager.STREAM_ALARM, volumePercent)
            }
            toneGenerator = created
            created
        } catch (t: RuntimeException) {
            Log.e(TAG, "ToneGenerator unavailable", t)
            onError(t)
            null
        }
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setWillPauseWhenDucked(false)
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    private fun vibrate() {
        val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(VIBRATION_PATTERN, -1),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATION_PATTERN, -1)
            }
        }.onFailure { onError(it) }
    }

    private companion object {
        const val TAG = "ToneAlerter"
        const val BEEP_GAP_MILLIS = 120L
        val VIBRATION_PATTERN = longArrayOf(0L, 200L, 120L, 200L)
    }
}

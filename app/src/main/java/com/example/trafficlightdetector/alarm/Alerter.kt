package com.example.trafficlightdetector.alarm

/**
 * Plays the driver warning.
 *
 * The MVVM layer (`MainViewModel`) only *decides* that an alarm is due and emits
 * an event; the Activity owns an [Alerter] and plays the sound. That keeps the
 * ViewModel free of audio side effects and easy to test.
 *
 * Implemented by [ToneAlerter] (ToneGenerator) — swap in a `MediaPlayer` or a
 * spoken prompt by writing another implementation.
 */
interface Alerter {

    /** Plays one alarm burst. Must be safe to call repeatedly. */
    fun alert()

    /** Silences any tone in progress (e.g. when the app leaves the foreground). */
    fun stop()

    /** Frees native resources; call once the host is destroyed. */
    fun release()
}

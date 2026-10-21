package com.onpath.app.ui

/**
 * Plan haptics in one place (owner 2026-10-21, after 0.8.9): vibrate **at the moment of the press, never after the
 * action** – and only on the map:
 * - hold recognised on the empty map / route line / a POI → 1× at the hold,
 * - hold recognised on a route point (its menu with Delete opens) → 2× at the hold,
 * - nothing after an edit completes (menu buttons, set / delete), nothing in the Points list (✕, drag), no undo buzz.
 * Decided by [MapGestures.buzz] at finger-down, played by [Haptics] when the hold ring is full. Follows only OnPath's
 * setting "Vibrate when planning" ([shouldPlay]), never the system "Touch feedback" flag. Pure, unit tested
 * (PlanHapticsTest).
 */
object PlanHaptics {
    /** Vibrator waveform (off/on ms): one pulse; two pulses clearly separated. */
    fun pattern(b: MapGestures.Buzz): LongArray = when (b) {
        MapGestures.Buzz.NONE -> LongArray(0)
        MapGestures.Buzz.ONE -> longArrayOf(0, 40)
        MapGestures.Buzz.TWO -> longArrayOf(0, 40, 100, 40)
    }

    /** Strength per [pattern] step (0..255) where the vibrator supports it: clearly felt. */
    fun amplitudes(b: MapGestures.Buzz): IntArray = when (b) {
        MapGestures.Buzz.NONE -> IntArray(0)
        MapGestures.Buzz.ONE -> intArrayOf(0, PULSE_AMPLITUDE)
        MapGestures.Buzz.TWO -> intArrayOf(0, PULSE_AMPLITUDE, 0, PULSE_AMPLITUDE)
    }

    /** Whether [b] vibrates: only OnPath's "Vibrate when planning" ([planVibrate]) counts. */
    fun shouldPlay(b: MapGestures.Buzz, planVibrate: Boolean): Boolean = b != MapGestures.Buzz.NONE && planVibrate

    const val PULSE_AMPLITUDE = 230

    /**
     * The pulses are never sent as touch feedback: [Haptics] always gives them an explicit media usage.
     * Why (owner's phone, 0.8.9, `dumpsys vibrator_manager`): a vibration without attributes has usage UNKNOWN, and
     * Android re-labels a short effect of at most 3 segments as TOUCH ("haptic feedback candidate"). The 1× pulse
     * (2 segments) became TOUCH and was dropped (`ignored_for_settings` – "Touch feedback" is off on that phone); the
     * 2× pulse (4 segments) stayed UNKNOWN and played. So "set" never vibrated while "delete" did.
     */
    const val MAX_UNLABELLED_TOUCH_SEGMENTS = 3
}

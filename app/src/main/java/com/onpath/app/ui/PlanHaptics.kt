package com.onpath.app.ui

/** What a plan edit or gesture did – decides how it feels (PlanHaptics). */
enum class PlanEdit {
    /** A point was set: Start here, Go here, new stop, "New point here", a point added by a hold, a place added. */
    POINT_SET,
    /** A point was deleted: point menu Delete, ✕ in the Points list. */
    POINT_DELETED,
    /** A point dropped at a new place in the Points list. */
    REORDERED,
    /** A hold armed / a menu opens (on a point, a POI or the map). */
    MENU_OPENED,
    /** Undo / redo – the line changing back is enough. */
    UNDO,
    /** Everything else (reverse, round trip, start / end here, clear, a route loaded): no vibration. */
    OTHER,
}

/**
 * Plan haptics in one place (owner 2026-10-21): 1 pulse = a point set, 2 pulses = a point deleted, a very light short
 * tick = a menu opens / a hold is armed, reorder drop = 1, undo = none. Pure, unit tested (PlanHapticsTest); played by
 * [Haptics]: only OnPath's own setting "Vibrate when planning" counts (owner 2026-10-21) – the system "Touch feedback"
 * flag may only silence the light menu tick ([shouldPlay]).
 */
object PlanHaptics {
    enum class Feel { NONE, TICK, ONE, TWO }

    fun feel(e: PlanEdit): Feel = when (e) {
        PlanEdit.POINT_SET, PlanEdit.REORDERED -> Feel.ONE
        PlanEdit.POINT_DELETED -> Feel.TWO
        PlanEdit.MENU_OPENED -> Feel.TICK
        PlanEdit.UNDO, PlanEdit.OTHER -> Feel.NONE
    }

    /** Vibrator waveform (off/on ms): a tick is shorter than one pulse; two pulses are clearly separated. */
    fun pattern(f: Feel): LongArray = when (f) {
        Feel.NONE -> LongArray(0)
        Feel.TICK -> longArrayOf(0, 8)
        Feel.ONE -> longArrayOf(0, 40)
        Feel.TWO -> longArrayOf(0, 40, 100, 40)
    }

    /** Strength per [pattern] step (0..255) where the vibrator supports it: pulses clearly felt, the tick light. */
    fun amplitudes(f: Feel): IntArray = when (f) {
        Feel.NONE -> IntArray(0)
        Feel.TICK -> intArrayOf(0, 120)
        Feel.ONE -> intArrayOf(0, PULSE_AMPLITUDE)
        Feel.TWO -> intArrayOf(0, PULSE_AMPLITUDE, 0, PULSE_AMPLITUDE)
    }

    /**
     * Whether [f] vibrates: [planVibrate] = OnPath's setting "Vibrate when planning"; [systemTouch] = the system
     * "Touch feedback" flag, which only gates the light menu tick – point set / deleted / reorder follow only our setting.
     */
    fun shouldPlay(f: Feel, planVibrate: Boolean, systemTouch: Boolean): Boolean = when (f) {
        Feel.NONE -> false
        Feel.TICK -> planVibrate && systemTouch
        Feel.ONE, Feel.TWO -> planVibrate
    }

    const val PULSE_AMPLITUDE = 230
}

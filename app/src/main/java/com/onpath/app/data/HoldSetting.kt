package com.onpath.app.data

import kotlin.math.roundToInt

/**
 * How long a finger must hold a button of the tour's bottom bar until it fires (owner 2026-11-06 22:50: "ein Schieber
 * von 0 bis 2 Sekunden in 0,5-Sekunden-Schritten"). It replaces the lock on/off switch: 0 = off (a plain tap, no lock
 * line), 0,5 … 2 s = the hold lock ([com.onpath.app.ui.HoldButton]). Pure, unit-tested (`HoldSettingTest`).
 */
object HoldSetting {
    const val STEP_MS = 500
    const val MAX_MS = 2_000
    /** 1 s – "eine Ampel lang" (the hold lock's first value, owner 2026-11-06). */
    const val DEFAULT_MS = 1_000
    val STEPS_MS: List<Int> = (0..MAX_MS step STEP_MS).toList()
    /** Slider steps between the ends (Material's `steps`): 0 · 0,5 · 1 · 1,5 · 2 → 3. */
    val SLIDER_STEPS = STEPS_MS.size - 2

    /** The nearest step for a slider position or a stored value (outside 0..2 s → the nearest end). */
    fun snap(ms: Float): Int = ((ms.coerceIn(0f, MAX_MS.toFloat()) / STEP_MS).roundToInt() * STEP_MS)

    fun snap(ms: Int): Int = snap(ms.toFloat())

    /** The hold lock is on (anything above 0). */
    fun locked(ms: Int): Boolean = ms > 0

    /**
     * The stored time: [storedMs] (the new key, snapped) wins; without it the old switch [oldLock] decides – off → 0,
     * on or never set → [DEFAULT_MS].
     */
    fun fromPrefs(storedMs: Int?, oldLock: Boolean?): Int = when {
        storedMs != null -> snap(storedMs)
        oldLock == false -> 0
        else -> DEFAULT_MS
    }

    /** "0,5" / "1" / "1,5" with the language's [decimal] separator; null for 0 (the caller says "Aus"). */
    fun seconds(ms: Int, decimal: Char): String? {
        val s = snap(ms)
        if (s == 0) return null
        val whole = s / 1000
        val half = s % 1000 != 0
        return if (half) "$whole${decimal}5" else "$whole"
    }
}

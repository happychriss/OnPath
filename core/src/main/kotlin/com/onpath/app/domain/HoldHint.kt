package com.onpath.app.domain

/**
 * Learning to hold (Chriss 2026-11-28 09:41, picture halten-lernen-abc (a)): a short tap on the empty map of an empty
 * plan – where a tap did nothing before – shows the quiet pill „Halten, um hier zu planen“ at the tapped spot. It goes by
 * itself after [SHOW_MS] (a short line without a button, `VISION.md`) and no longer comes once the user has held
 * [LEARNED_HOLDS] times with something happening (remembered across restarts). A tap on a place keeps showing its info;
 * „Karte laden?“ stays as it is (no pill while it asks); Navigate unchanged (not [editable]). Pure – `MapHost` asks it.
 */
object HoldHint {
    /** Holds that did something before the pill stops coming. */
    const val LEARNED_HOLDS = 3

    /** How long the pill stays: a few words, read in passing. */
    const val SHOW_MS = 4_000L

    /**
     * Whether this press shows the pill: a [MapGestures.Press.TAP] in Plan ([editable]) that does nothing
     * ([MapGestures.Action.None]) – not on a place ([poi]), not on a route point ([marker] ≥ 0) – on an empty plan,
     * while no „Karte laden?“ question came from it ([loadAsked]) and the user has not learned it yet ([holds]).
     */
    fun pillOnTap(editable: Boolean, press: MapGestures.Press, action: MapGestures.Action, poi: Boolean, marker: Int,
                  planEmpty: Boolean, loadAsked: Boolean, holds: Int): Boolean =
        editable && press == MapGestures.Press.TAP && action == MapGestures.Action.None && !poi && marker < 0 &&
            planEmpty && !loadAsked && holds < LEARNED_HOLDS

    /** Whether this press counts as a hold that did something (a menu opened or a point was set). */
    fun counts(press: MapGestures.Press, action: MapGestures.Action): Boolean =
        press == MapGestures.Press.HOLD && (action is MapGestures.Action.PointMenu || action == MapGestures.Action.PoiMenu ||
            action == MapGestures.Action.FirstPointMenu || action == MapGestures.Action.AddPoint)

    /** The stored count after one more counted hold (stops at [LEARNED_HOLDS], never below 0). */
    fun next(holds: Int): Int = (holds.coerceAtLeast(0) + 1).coerceAtMost(LEARNED_HOLDS)
}

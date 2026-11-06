package com.onpath.app.ui

/**
 * Whether a done tour / track has a line one can plan from or follow (field bug 2026-11-06 22:30: a 0 m tour opened on
 * the map offered "Als Plan übernehmen" – "welche Route? Es ist ja gar keine Route da"). Fewer than two points, or less
 * than [MIN_M] in all (GPS standing still wanders that much), is no line: the sheet says "Keine Strecke aufgezeichnet".
 */
object TrackLine {
    const val MIN_M = 20.0

    fun has(points: Int, distanceM: Double): Boolean = points >= 2 && distanceM >= MIN_M

    /** My routes' list knows only the distance of a tour, not its points. */
    fun hasDistance(distanceM: Double): Boolean = distanceM >= MIN_M
}

/**
 * The end sheet ignores a tap whose finger went down within [GUARD_MS] of the sheet appearing (field bug 2026-11-06
 * 22:30, hold lock: the thumb that held "Beenden" ~1 s lands where "Tour speichern" opens – the tour was saved 1 s after
 * the sheet appeared, the owner did not mean to). Measured from the press, not the release: a thumb held on from the
 * hold lock and let go later must not save either.
 */
object EndSheetGuard {
    const val GUARD_MS = 500L

    fun accepts(shownAtMs: Long, pressedAtMs: Long): Boolean = pressedAtMs - shownAtMs >= GUARD_MS
}

package com.onpath.app.domain.tour

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

/**
 * The three ways to end a tour – End tour, "You have arrived" → Save, and the "Interrupted tour" question after a restart
 * – all run the ride check first (core feature C12): rides found → the bus / car / lift summary; none → arrival's Save
 * saves at once, End tour asks plainly (Save · Weiter · Löschen); an interrupted tour's Save saves at once.
 */
object TourEndFlow {
    enum class Next { RIDES, SAVE_NOW, ASK_PLAIN }

    /** End tour ([saveIfNoRides] = false) or "You have arrived" → Save ([saveIfNoRides] = true). */
    fun afterRideCheck(ridesFound: Boolean, saveIfNoRides: Boolean): Next = when {
        ridesFound -> Next.RIDES
        saveIfNoRides -> Next.SAVE_NOW
        else -> Next.ASK_PLAIN
    }

    /**
     * "You have arrived" shows only in Navigate while the tour records: an arrival box from Navigate waits for Navigate,
     * Plan does not show it (owner 2026-11-03: Plan is silent) – it stays pending.
     */
    fun arrivalBoxShows(asked: Boolean, recording: Boolean, navigate: Boolean): Boolean = asked && recording && navigate

    /** "Interrupted tour" → Save. */
    fun unfinishedSave(ridesFound: Boolean): Next = if (ridesFound) Next.RIDES else Next.SAVE_NOW

    /**
     * The track drawn under the map: the running recording's; without one (no service after a reboot) the interrupted
     * tour's behind its question (field bug 2026-10-31: the map behind "Interrupted tour" was empty); else nothing.
     */
    fun <T> trackShown(recording: List<T>?, unfinishedOpen: Boolean, unfinishedTrack: List<T>?): List<T> =
        recording ?: unfinishedTrack?.takeIf { unfinishedOpen } ?: emptyList()
}

package com.onpath.app.domain.tour

/**
 * A recording that ends with less than [MIN_M] of track – 0 m, one GPS point, a start by mistake – is no tour (owner
 * 2026-11-12, decided; field: "Tour 5. Nov. …" with the orange dot, a date name because there was no line to name):
 * it is not shown as "Tour gespeichert" but goes to the recycle bin at the end, like the end sheet's Löschen, with a
 * short notice and Rückgängig (back as a normal tour). Never deleted for good – the bin's 30 days apply, the points
 * stay in the database, restore brings the tour back whole.
 *
 * Only the end of a running recording decides this way; a tour the user saves from the "Tour unterbrochen" question
 * is saved as he chose (the question shows its distance) – the owner's text says nothing about it, so it stays.
 */
object ShortRecording {
    const val MIN_M = 50.0

    /** [distanceM] = the finished tour's stored distance (after the ride choice, core feature C12). */
    fun bins(distanceM: Double): Boolean = distanceM < MIN_M
}

package com.onpath.app.domain.tour

import com.onpath.app.data.Geo
import com.onpath.app.gpx.TrackPoint

/**
 * A recording that never got away from its start – 0 m, one GPS point, a start by mistake – is no tour (owner
 * 2026-11-12, decided; field: "Tour 5. Nov. …" with the orange dot, a date name because there was no line to name):
 * it is not shown as "Tour gespeichert" but goes to the recycle bin at the end, like the end sheet's Löschen, with the
 * box "Kurze Aufzeichnung (12 m)" and Rückgängig (back as a normal tour). Never deleted for good – the bin's 30 days
 * apply, the points stay in the database, restore brings the tour back whole.
 *
 * The stored distance alone is no safe measure (review 2026-11-13): a GPS gap longer than
 * [com.onpath.app.recording.PointFilter.BRIDGE_MAX_MS] or a jump starts a new piece that adds 0 m – a two-hour hike
 * with the phone in a backpack, under forest or in Doze can store ~0 m. So a recording is short only when EVERY
 * recorded point of EVERY piece lies within [MIN_M] of the first point – the walk never left the start – and the
 * distance as recorded (before any ride choice) is below [MIN_M]. A recording with a bus / car / lift part found
 * (core feature C12) is never short.
 *
 * Only the end of a running recording decides this way; a tour the user saves from the "Tour unterbrochen" question
 * is saved as he chose (the question shows its distance) – the owner's text says nothing about it, so it stays.
 */
object ShortRecording {
    const val MIN_M = 50.0

    /**
     * [recordedM] = the distance as recorded ([com.onpath.app.rides.RideRecord.recorded] when rides were found, else the
     * tour's), [pieces] = every point of every track piece as stored, [rides] = a ride part was found.
     */
    fun bins(recordedM: Double, pieces: List<List<TrackPoint>>, rides: Boolean): Boolean {
        if (rides || recordedM >= MIN_M) return false
        val first = pieces.firstOrNull { it.isNotEmpty() }?.first() ?: return true // no point at all
        return pieces.all { piece -> piece.all { Geo.distance(first.lat, first.lon, it.lat, it.lon) < MIN_M } }
    }
}

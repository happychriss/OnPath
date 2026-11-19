package com.onpath.app.ui.routes

import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint

/**
 * A planned route as Save writes it (TourDb.insertTour / replacePlanned): the numbers of its line, the line itself, its
 * via points as stored stops, the pinned ones, the difficulty level and – of a multi-day tour – its days. No name: a new
 * route gets it with [SaveStore.insertPlanned]; an overwrite keeps the stored one (and renames separately).
 */
class PlannedRow(
    val activity: String?, val sac: Int,
    val distanceM: Double, val ascentM: Double, val descentM: Double, val durationS: Double,
    val segments: List<List<TrackPoint>>, val stops: List<GpxWaypoint>, val pins: Set<Int>, val difficulty: String?,
    /** null = a normal route (insert) / keep the stored days (replace) – never wiped by a write that does not know them. */
    val days: MultiDay? = null,
)

/**
 * What [SaveSlice] reads and writes in the tour database (blocking, may throw – called only inside `withContext(io)`).
 * The app implements it over TourDb (one call each, same arguments); tests over the JDBC tables (`JdbcTours`).
 */
interface SaveStore {
    fun getTour(id: Long): Tour?
    fun loadSegments(id: Long): List<List<TrackPoint>>
    /** A new planned route [name] ([com.onpath.app.data.TourKind.PLANNED], saved – not a draft); returns its id. One transaction. */
    fun insertPlanned(name: String, row: PlannedRow): Long
    /** Overwrites planned route [id] in place (stats, stops, pins, difficulty, line – name and date stay); false = it is gone. One transaction. */
    fun replacePlanned(id: Long, row: PlannedRow): Boolean
    fun rename(id: Long, name: String)
    /** The star on or off; false = the tour is gone. */
    fun setFavourite(id: Long, favourite: Boolean): Boolean
}

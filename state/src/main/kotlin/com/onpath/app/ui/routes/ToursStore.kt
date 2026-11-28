package com.onpath.app.ui.routes

import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.time.TourTimes

/**
 * What the Routes slices read and write of the tour database and the stored times (blocking, may throw – called only inside
 * `withContext(io)`). It extends [SaveStore] on purpose: one store object in the app (over TourDb) serves Save and Routes,
 * and the next Routes pieces add their calls here, not in a second overlapping interface. Tests implement it over the JDBC
 * tables (`JdbcTours`).
 */
interface ToursStore : SaveStore {
    /** The followed plan's points stored with a recorded tour; null = none. */
    fun planPoints(id: Long): List<GpxWaypoint>?
    /** The stored times of every tour (the overview's, by tour id) – read once per list refresh. */
    fun tourTimes(): Map<Long, TourTimes>
    /** Every tour row (as the tables hold them; Routes II: the followed plan's saved route is looked up here). */
    fun listTours(): List<com.onpath.app.data.Tour>
    /** The last version of the plan a recorded tour followed; null = none. */
    fun getTourPlan(id: Long): com.onpath.app.data.TourPlan?
}

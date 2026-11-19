package com.onpath.app.ui.tour

import com.onpath.app.rides.RideStore

/**
 * What the tour pieces write into the tour database (blocking, may throw – called only inside `withContext(io)` / a launch
 * on `io`). The design's name; piece 16b needed `updateActivity`, piece 17 adds the end's writes (`updateStats`,
 * `setDeleted`, the ride store), the later tour pieces add theirs (`setTourPlan`, …). The app implements it over `TourDb`
 * with named arguments (pinned in `TourWiringTest`); tests implement it over the JDBC tables (`JdbcTours`).
 */
interface TourRows {
    /** The activity switched during a recording: stored on the tour row (activity name, T level). */
    fun updateActivity(id: Long, activity: String, sac: Int)

    /** An interrupted tour's stats and state ("done"): the rows changed (0 = the tour row is gone). */
    fun updateStats(id: Long, distance: Double, ascent: Double, descent: Double, duration: Double, state: String): Int

    /** A tour into the recycle bin ([deletedMs]) or back (null); false when the tour is gone. */
    fun setDeleted(id: Long, deletedMs: Long?): Boolean

    /** The rides' store ([com.onpath.app.rides.RideApply] reads and writes the tour's rides through it). */
    fun rides(): RideStore
}

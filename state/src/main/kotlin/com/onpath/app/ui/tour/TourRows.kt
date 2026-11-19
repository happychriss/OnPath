package com.onpath.app.ui.tour

/**
 * What the tour pieces write into the tour database (blocking, may throw – called only inside `withContext(io)` / a launch
 * on `io`). The design's name; piece 16b needs the one call below, the later tour pieces add theirs here (`updateStats`,
 * `setDeleted`, `setTourPlan`, …). The app implements it over `TourDb` with named arguments (pinned in `TourWiringTest`);
 * tests implement it over the JDBC tables (`JdbcTours`).
 */
interface TourRows {
    /** The activity switched during a recording: stored on the tour row (activity name, T level). */
    fun updateActivity(id: Long, activity: String, sac: Int)
}

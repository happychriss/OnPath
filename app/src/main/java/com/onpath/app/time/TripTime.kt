package com.onpath.app.time

/** Live times of the running tour, published by the recording service (RecState.time). */
data class TripTime(
    /** Recording time without breaks (s). */
    val movingS: Double,
    /** Breaks so far (s), including one still going on. */
    val breakS: Double,
    /** Calm moving time to the end of the followed line (s, rounded for display); null = nothing followed / not known. */
    val toGoS: Double? = null,
    /** Calm arrival clock (epoch ms) incl. the break allowance; null with [toGoS]. */
    val arrivalMs: Long? = null,
    /** Learned on this trip, relative to the plan (1 = as planned); null until ~20 min of learning. */
    val learned: Factors? = null,
    /** Can the to-go / arrival be trusted now (docs/plan-time-estimate.md, owner rules 6 and 8)? */
    val state: EstimateState = EstimateState.OK,
    /** Why the estimate is paused ([EstimateState.PAUSED]). */
    val pausedBecause: PauseReason? = null,
    /** The followed line changed and moved the arrival by more than 5 min: said once, kept until the next change. */
    val lineNote: LineNote? = null,
)

/** "New route: arrival 12:45, was 13:15" (backlog: arrival forecast after plan edits). */
data class LineNote(val wasMs: Long, val nowMs: Long) {
    fun text(): String = "New route: arrival ${TimeEstimate.clock(nowMs)}, was ${TimeEstimate.clock(wasMs)}"
}

enum class EstimateState {
    OK,
    /** Not on the followed line (confirmed off route, or not matched yet): show "— not on route", no stale time. */
    NOT_ON_ROUTE,
    /** The estimate cannot follow for a while: the last values are shown greyed with "estimate paused". */
    PAUSED,
}

enum class PauseReason(val text: String) {
    NO_GPS("no GPS"),
    NO_PROGRESS("not moving along the route"),
}

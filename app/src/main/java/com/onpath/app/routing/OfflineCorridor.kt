package com.onpath.app.routing

/**
 * How far beside a route the offline data reaches (F-OFF-02, owner 2026-11-07): map areas, routing parts and what
 * "Clean up" keeps – one rule for all three, so nothing is loaded that the next clean-up deletes. 10 km on foot,
 * 20 km for every bike activity (a detour by bike reaches further). Pure; shared with the web planner
 * (app/web-shared-files.txt) through [Rd5Parts.forCorridor].
 */
object OfflineCorridor {
    const val HIKE_M = 10_000.0
    const val BIKE_M = 20_000.0
    /** The largest corridor – what an unknown activity gets (robust first: never less than a bike plan needs). */
    const val MAX_M = BIKE_M

    /** Corridor either side of a route for [activity]; null (not known) → [MAX_M]. */
    fun marginM(activity: Activity?): Double = when (activity) {
        Activity.HIKE -> HIKE_M
        Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.EBIKE, Activity.MTB, Activity.EMTB -> BIKE_M
        null -> MAX_M
    }
}

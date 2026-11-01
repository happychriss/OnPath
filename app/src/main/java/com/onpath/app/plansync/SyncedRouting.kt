package com.onpath.app.plansync

import com.onpath.app.routing.PlannedRoute

/**
 * A route from the browser is routed on the phone before it is stored (review routing M5): only a real route goes into
 * My routes and the backup. Without routing data there (a holiday region) the planner gives straight legs – ascent 0,
 * flat time – so the route keeps just its points and is routed when the data arrives or when it is opened. Pure.
 */
object SyncedRouting {
    fun storable(r: PlannedRoute): Boolean = r.error == null && r.legs.isNotEmpty() && r.legs.none { it.straight }
}

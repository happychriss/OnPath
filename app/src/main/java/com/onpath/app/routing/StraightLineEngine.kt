package com.onpath.app.routing

import com.onpath.strings.*
import com.onpath.app.data.Geo

/**
 * Temporary/fallback engine: connects waypoints with straight lines.
 * Used when BRouterEngine is not on the classpath (see [EngineProvider]).
 */
class StraightLineEngine : RoutingEngine {
    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
        if (waypoints.size < 2) throw RoutingException(Txt.s(Res.string.plan_err_set_start_end))
        val pts = waypoints.map { GeoPoint(it.lat, it.lon, it.ele) }
        val dist = Geo.length(pts)
        val (up, down) = Geo.climb(pts.map { it.ele })
        // the one time model (time/RouteTimes), as for a planned route on these points
        return RouteResult(pts, dist, up, down, com.onpath.app.time.RouteTimes.of(pts, activity).total())
    }
}

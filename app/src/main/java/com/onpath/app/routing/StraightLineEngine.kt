package com.onpath.app.routing

import com.onpath.app.data.Geo

/**
 * Temporary/fallback engine: connects waypoints with straight lines.
 * Used when BRouterEngine is not on the classpath (see [EngineProvider]).
 */
class StraightLineEngine : RoutingEngine {
    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
        if (waypoints.size < 2) throw RoutingException("Set a start and an end point.")
        val pts = waypoints.map { GeoPoint(it.lat, it.lon, it.ele) }
        val dist = Geo.length(pts)
        val (up, down) = Geo.climb(pts.map { it.ele })
        return RouteResult(pts, dist, up, down, Geo.estimateSeconds(dist, up, down, activity))
    }
}

package com.onpath.app.routing

import com.onpath.app.data.Geo

/**
 * Decides where a newly tapped point belongs in the waypoint list (F-PLAN-13).
 *
 * A new point is usually a *hint* ("go via here"), not a new destination. Rules, in order:
 * 1. Fewer than 2 waypoints: append (start, then destination).
 * 2. Tap on or near the planned line (within [lineSnapM]): insert into the leg that the line
 *    belongs to there – this reshapes that leg, like dragging the line in Komoot.
 * 3. Otherwise "cheapest insertion": insert where the extra straight-line distance is smallest,
 *    unless appending after the destination is cheaper – then the point becomes the new destination.
 *    Example: A→B, tap beside the middle → via between A and B; tap beyond B → new destination.
 *
 * The UI shows what happened ("Added as stop 2") with one-tap alternatives, so the user stays in control.
 */
object WaypointInsertion {

    enum class Kind { START, DESTINATION, VIA_ON_LINE, VIA_BEST_FIT }

    data class Decision(val index: Int, val kind: Kind)

    /**
     * @param waypoints current waypoints in order
     * @param p the new point
     * @param route current route geometry through all waypoints (may be empty / null)
     * @param lineSnapM distance to the route line that counts as "tapped on the line"
     *        (callers pass ~ 24 dp converted to metres at the current zoom)
     */
    fun decide(waypoints: List<GeoPoint>, p: GeoPoint, route: List<GeoPoint>?, lineSnapM: Double): Decision {
        if (waypoints.isEmpty()) return Decision(0, Kind.START)
        if (waypoints.size == 1) return Decision(1, Kind.DESTINATION)

        // Rule 2: tap on the line.
        if (route != null && route.size >= 2) {
            val hit = nearestOnRoute(route, p)
            if (hit != null && hit.second <= lineSnapM) {
                val leg = legOfRouteIndex(waypoints, route, hit.first)
                return Decision(leg + 1, Kind.VIA_ON_LINE)
            }
        }

        // Rule 3: cheapest insertion vs. appending.
        var bestLeg = -1
        var bestCost = Double.MAX_VALUE
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]; val b = waypoints[i + 1]
            val cost = Geo.distance(a, p) + Geo.distance(p, b) - Geo.distance(a, b)
            if (cost < bestCost) { bestCost = cost; bestLeg = i }
        }
        val appendCost = Geo.distance(waypoints.last(), p)
        return if (bestCost < appendCost) Decision(bestLeg + 1, Kind.VIA_BEST_FIT)
        else Decision(waypoints.size, Kind.DESTINATION)
    }

    /** Index of the route vertex nearest to [p] (segment-projected) and the distance in metres. */
    fun nearestOnRoute(route: List<GeoPoint>, p: GeoPoint): Pair<Int, Double>? {
        if (route.size < 2) return null
        var bestI = -1
        var bestD = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val d = distanceToSegment(p, route[i], route[i + 1])
            if (d < bestD) { bestD = d; bestI = i }
        }
        return bestI to bestD
    }

    /**
     * Which leg (0-based: leg i runs from waypoint i to i+1) contains route vertex [routeIndex].
     * Waypoints are matched to route vertices in order (monotonic), so out-and-back routes work.
     */
    fun legOfRouteIndex(waypoints: List<GeoPoint>, route: List<GeoPoint>, routeIndex: Int): Int {
        val wpIdx = routeIndices(waypoints, route)
        for (leg in 0 until waypoints.size - 1) {
            if (routeIndex < wpIdx[leg + 1]) return leg
        }
        return waypoints.size - 2
    }

    /**
     * Route vertex index for each waypoint, matched in order (monotonic): the start maps to 0,
     * the destination to the route end, each stop to its nearest vertex after the previous one.
     */
    fun routeIndices(waypoints: List<GeoPoint>, route: List<GeoPoint>): IntArray {
        val wpIdx = IntArray(waypoints.size)
        if (route.isEmpty()) return wpIdx
        var from = 0
        for (w in waypoints.indices) {
            // Search forward from the previous match; the last waypoint maps to the route end.
            if (w == waypoints.size - 1) { wpIdx[w] = route.size - 1; break }
            if (w == 0) { wpIdx[w] = 0; continue }
            var best = from
            var bestD = Double.MAX_VALUE
            for (k in from until route.size) {
                val d = Geo.distance(route[k], waypoints[w])
                if (d < bestD) { bestD = d; best = k }
            }
            wpIdx[w] = best
            from = best
        }
        return wpIdx
    }

    /** Approximate distance (m) from [p] to segment a–b, using a local equirectangular projection. */
    fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * Math.cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * mPerDegLon; val ay = (a.lat - p.lat) * mPerDegLat
        val bx = (b.lon - p.lon) * mPerDegLon; val by = (b.lat - p.lat) * mPerDegLat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return Math.sqrt(cx * cx + cy * cy)
    }
}

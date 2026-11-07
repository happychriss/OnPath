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
 * 4. Led to a far route that was never reached ("Guide me" during a tour, [here] = the position, Bug 02): the way
 *    there counts as a leg too – a point that fits best between here and the start is a **stop on the way**
 *    ([Kind.ON_THE_WAY]): it goes before ①, the tour is led via it to ① (a round trip's loop 1 → 2 → 3 → 1 stays
 *    unchanged, owner 2026-11-03), not a destination after the route's end. It is never offered as "Set as start"
 *    (field 2026-11-07: during a tour the owner expects a stop on the route he follows, not a new start).
 * 5. A round trip never gets a point behind its closing point (owner 2026-11-03: "Rundtour bleibt Rundtour"): instead
 *    of a new destination the point goes into the loop where it fits best. Deleting the closing point opens the loop.
 *
 * The UI shows what happened ("Added as stop 2") with one-tap alternatives, so the user stays in control.
 */
object WaypointInsertion {

    enum class Kind { START, DESTINATION, VIA_ON_LINE, VIA_BEST_FIT, ON_THE_WAY }

    data class Decision(val index: Int, val kind: Kind)

    /**
     * @param waypoints current waypoints in order
     * @param p the new point
     * @param route current route geometry through all waypoints (may be empty / null)
     * @param lineSnapM distance to the route line that counts as "tapped on the line"
     *        (callers pass ~ 24 dp converted to metres at the current zoom)
     * @param here the position while a tour is led to this plan and has never reached it (rule 4); else null
     */
    fun decide(waypoints: List<GeoPoint>, p: GeoPoint, route: List<GeoPoint>?, lineSnapM: Double, here: GeoPoint? = null): Decision {
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
        val loop = PlanEdits.isRoundTrip(waypoints)
        // Rule 5: a round trip stays closed – nothing goes behind its closing point
        val appendCost = if (loop) Double.MAX_VALUE else Geo.distance(waypoints.last(), p)
        // Rule 4: on the way there (here → start) – the new start; for a round trip a stop on the way before the loop
        val first = waypoints.first()
        val wayThereCost = here?.let { Geo.distance(it, p) + Geo.distance(p, first) - Geo.distance(it, first) }
        if (wayThereCost != null && wayThereCost < bestCost && wayThereCost < appendCost)
            return Decision(0, Kind.ON_THE_WAY)
        return if (bestCost < appendCost) Decision(bestLeg + 1, Kind.VIA_BEST_FIT)
        else Decision(waypoints.size, Kind.DESTINATION)
    }

    /**
     * Where a candidate place [p] would join the [route] if added with [d] (from [decide] on the same [waypoints] and
     * route): the route's start for a new start, its end for a new destination, else the spot of the leg it goes into
     * that lies nearest to [p]. The map draws a thin dashed line from the candidate marker to it ("not taken yet");
     * null = no route to join (fewer than two points, no line, a route still being computed).
     */
    fun joinPoint(waypoints: List<GeoPoint>, p: GeoPoint, route: List<GeoPoint>?, d: Decision): GeoPoint? {
        if (route == null || route.size < 2 || waypoints.size < 2) return null
        if (d.index <= 0) return route.first()
        if (d.index >= waypoints.size) return route.last()
        val wpIdx = routeIndices(waypoints, route)
        val from = wpIdx[d.index - 1].coerceIn(0, route.size - 1)
        val to = wpIdx[d.index].coerceIn(from, route.size - 1)
        if (from == to) return route[from]
        var best = route[from]
        var bestD = Double.MAX_VALUE
        for (i in from until to) {
            val c = Geo.closestOnSegment(p, route[i], route[i + 1])
            val dist = Geo.distance(p, c)
            if (dist < bestD) { bestD = dist; best = c }
        }
        return best
    }

    /** Index of the route vertex nearest to [p] (segment-projected) and the distance in metres. */
    fun nearestOnRoute(route: List<GeoPoint>, p: GeoPoint): Pair<Int, Double>? {
        if (route.size < 2) return null
        var bestI = -1
        var bestD = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val d = Geo.distanceToSegment(p, route[i], route[i + 1])
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
}

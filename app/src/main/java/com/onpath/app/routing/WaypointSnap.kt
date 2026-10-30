package com.onpath.app.routing

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Moves free plan points onto the way the router actually used (owner rule: "if a point is not
 * routable, take the closest way as the point" – OnPath does no off-road guidance). The router snaps
 * each point to the nearest usable way anyway; this keeps marker, stored plan and route line in step.
 *
 * Pure Kotlin, no Android and no other app classes (also compiled by tools/routing/jvm-test).
 */
object WaypointSnap {
    /** Smaller moves are GPS / rounding noise: leave the point where the user put it. */
    const val MIN_MOVE_M = 3.0
    /** Router's catching range (BRouter `waypointCatchingRange`); further = not the same point. */
    const val MAX_MOVE_M = 1000.0
    /** A matched crosspoint further than this from the drawn line is not on it (e.g. a cut spike). */
    private const val ON_LINE_M = 5.0

    /** A point this close to the way BRouter matched it on counts as "on the way" (the user meant that way). */
    const val ON_WAY_M = 30.0
    /** The line passes the matched spot at least this far away = cut out, not snapping noise (a spike trimmed at a junction). */
    const val MISSED_MIN_M = 15.0
    /** … and farther than this many times the point's own distance to its way (a vague tap beside a way is smoothed). */
    const val MISSED_FACTOR = 3.0

    /**
     * The router matched [point] on a way ([matched], ≤ [ON_WAY_M] from it) but the drawn [line] misses that spot by
     * more than max([MISSED_MIN_M], [MISSED_FACTOR] × the point's distance to its way) – BRouter's via correction cut the
     * there-and-back to it (field bug 2026-10-30: matched 4.5 m from the tap, line 47 m from the spot). Relative, because
     * a tap right on a path means that path, while a tap 20 m beside two ways may mean either.
     */
    fun missesMatched(point: GeoPoint, matched: GeoPoint?, line: List<GeoPoint>): Boolean {
        if (matched == null || line.size < 2) return false
        val onWay = distM(point, matched)
        return onWay <= ON_WAY_M && distToLine(matched, line) > maxOf(MISSED_MIN_M, MISSED_FACTOR * onWay)
    }

    /** Interior points of [waypoints] the [line] misses although the router matched them on a way ([missesMatched]). */
    fun skipped(waypoints: List<GeoPoint>, line: List<GeoPoint>, matched: List<GeoPoint?>): Set<Int> {
        if (waypoints.size < 3 || matched.size != waypoints.size) return emptySet()
        return (1 until waypoints.lastIndex).filter { missesMatched(waypoints[it], matched[it], line) }.toSet()
    }

    /**
     * Where each waypoint lies on the routed [line] (same size as [waypoints]); null = unknown.
     * Start = first line point, destination = last line point (that is where the line is drawn from/to).
     * Via = the router's matched crosspoint ([matched], same order as [waypoints], may be empty or hold
     * nulls) when it lies on the line, otherwise the waypoint projected onto the line.
     */
    fun onRoute(waypoints: List<GeoPoint>, line: List<GeoPoint>, matched: List<GeoPoint?> = emptyList()): List<GeoPoint?> {
        if (waypoints.size < 2 || line.size < 2) return List(waypoints.size) { null }
        return waypoints.indices.map { i ->
            when (i) {
                0 -> line.first()
                waypoints.lastIndex -> line.last()
                else -> matched.getOrNull(i)?.takeIf { distToLine(it, line) <= ON_LINE_M } ?: project(waypoints[i], line)
            }
        }
    }

    /**
     * The plan after snapping, or null when no point moves. A point moves only when its snapped
     * position is known, it is not in [keep] (POI-pinned / named stops keep their exact coordinates)
     * and it is more than [minM] but at most [maxM] away. Elevation of the original point is kept.
     */
    fun apply(
        waypoints: List<GeoPoint>,
        snapped: List<GeoPoint?>,
        keep: Set<Int> = emptySet(),
        minM: Double = MIN_MOVE_M,
        maxM: Double = MAX_MOVE_M,
    ): List<GeoPoint>? {
        if (snapped.size != waypoints.size) return null
        var changed = false
        val out = waypoints.mapIndexed { i, p ->
            val s = snapped[i]
            if (s == null || i in keep || !s.lat.isFinite() || !s.lon.isFinite()) return@mapIndexed p
            val d = distM(p, s)
            if (d <= minM || d > maxM) p else { changed = true; GeoPoint(s.lat, s.lon, p.ele) }
        }
        return if (changed) out else null
    }

    /** Old → new value for every moved point, leaving out values that moved to two different places. */
    fun moves(before: List<GeoPoint>, after: List<GeoPoint>): Map<GeoPoint, GeoPoint> {
        val m = HashMap<GeoPoint, GeoPoint>()
        val bad = HashSet<GeoPoint>()
        for (i in before.indices) {
            val a = before[i]; val b = after.getOrNull(i) ?: continue
            if (a == b) continue
            val prev = m.put(a, b)
            if (prev != null && prev != b) bad += a
        }
        bad.forEach { m.remove(it) }
        return m
    }

    /** Nearest point on the polyline (local equirectangular projection; fine at snapping distances). */
    fun project(p: GeoPoint, line: List<GeoPoint>): GeoPoint {
        if (line.size == 1) return line[0]
        val kx = cos(Math.toRadians(p.lat))
        var best = line[0]; var bestD = Double.MAX_VALUE
        for (i in 1 until line.size) {
            val a = line[i - 1]; val b = line[i]
            val ax = (a.lon - p.lon) * kx; val ay = a.lat - p.lat
            val bx = (b.lon - p.lon) * kx; val by = b.lat - p.lat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val x = ax + t * dx; val y = ay + t * dy
            val d = x * x + y * y
            if (d < bestD) {
                bestD = d
                best = when (t) {
                    0.0 -> a
                    1.0 -> b
                    else -> GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon),
                        if (a.ele != null && b.ele != null) a.ele + t * (b.ele - a.ele) else null)
                }
            }
        }
        return best
    }

    fun distToLine(p: GeoPoint, line: List<GeoPoint>): Double = distM(p, project(p, line))

    fun distM(a: GeoPoint, b: GeoPoint): Double {
        val x = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2.0))
        val y = Math.toRadians(b.lat - a.lat)
        return sqrt(x * x + y * y) * 6_371_000.0
    }
}

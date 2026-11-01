package com.onpath.app.routing

import com.onpath.app.data.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bug-01 pass routes only around the cut stop (owner 2026-10-31, "Option 1"): the first line stays, the legs to
 * the stop and on are routed as requests of two points and spliced in (Planner.splice). Real BRouter data, the owner's
 * 70.6 km hike: tools/routing/jvm-test/SpurSpliceJvmTest.kt.
 */
class PlannerSpliceTest {
    private val lat = 51.48
    /** A point on the road (an east-west line at [lat]) at [lon]. */
    private fun road(lon: Double) = GeoPoint(lat, lon)
    /** [m] metres south of [p] (0.00001° lat ≈ 1.11 m). */
    private fun south(p: GeoPoint, m: Double) = GeoPoint(p.lat - m / 111_195.0, p.lon)

    private val roadWay = WayAttrs("road", "asphalt")
    private val pathWay = WayAttrs("path", "ground")

    /**
     * A road along [lat] with a vertex every 0.0015° (≈ 104 m) and a path south from [junction] to [stop] (40 m). A request
     * with [stop] inside rides past it (the via correction's cut); as a request end it goes there and back. [uTurn] = a
     * request from a road point to the stop first goes 40 m back west (a doubled piece at the splice point). [fail] =
     * requests that throw. Numbers from the line: metres, way types and surfaces per span.
     */
    private inner class RoadEngine(
        val junction: GeoPoint, val stop: GeoPoint,
        val uTurn: Boolean = false, val fail: (List<GeoPoint>) -> Boolean = { false },
    ) : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()

        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            if (fail(waypoints)) throw RoutingException("No route found.")
            val line = ArrayList<GeoPoint>()
            fun add(p: GeoPoint, way: WayAttrs) { if (line.isEmpty() || line.last() != p) line += p.copy(way = way) else line[line.lastIndex] = p.copy(way = way) }
            val ends = waypoints.indices.filter { it == 0 || it == waypoints.lastIndex || waypoints[it] != stop }.map { waypoints[it] }
            for (k in 1 until ends.size) {
                val p = ends[k - 1]; val q = ends[k]
                val from = if (p == stop) junction else p; val to = if (q == stop) junction else q
                if (p == stop) { add(stop, pathWay) }
                if (p != stop && q == stop && uTurn && k == 1) { add(p, roadWay); add(road(p.lon - 0.00058), roadWay) }
                add(from, roadWay)
                val step = if (to.lon >= from.lon) 0.0015 else -0.0015
                var v = Math.round(from.lon / 0.0015) * 0.0015
                while ((step > 0 && v < to.lon) || (step < 0 && v > to.lon)) { if ((v - from.lon) * step > 0) add(road(v), roadWay); v += step }
                add(to, if (q == stop) pathWay else roadWay)
                if (q == stop) add(stop, pathWay)
            }
            val d = Geo.length(line)
            fun shares(key: (WayAttrs) -> String) = (1 until line.size).groupBy { key(line[it - 1].way!!) }
                .map { (k, v) -> Share(k, v.sumOf { Geo.distance(line[it - 1], line[it]) }) }.sortedByDescending { it.meters }
            return RouteResult(line, d, 0.0, 0.0, d / 4.0, shares { it.wayType }, shares { it.surface }, matched = waypoints)
        }
    }

    @Test fun onlyTheLegsAroundTheCutStopAreRoutedAgain() {
        val junction = road(7.4300); val stop = south(junction, 40.0)
        val wps = listOf(road(7.4000), road(7.4200), stop, road(7.4400), road(7.4600))
        val e = RoadEngine(junction, stop)
        val first = e.route(wps, Activity.HIKE, 2); e.calls.clear()
        val r = Planner(e).plan(wps, Activity.HIKE, 2)
        assertEquals(listOf(wps, listOf(wps[1], stop), listOf(stop, wps[3])), e.calls)
        assertTrue(r.skipped.isEmpty())
        assertTrue(WaypointSnap.distToLine(stop, r.points) < 1.0)
        assertEquals(first.distanceM + 80.0, r.distanceM, 1.0) // the path there and back
        assertEquals(r.distanceM, Geo.length(r.points), 1.0)
        assertEquals(80.0, r.wayTypes.single { it.key == "path" }.meters, 1.0)
        assertEquals(first.distanceM, r.wayTypes.single { it.key == "road" }.meters, 1.0)
        assertEquals(r.distanceM, r.surfaces.sumOf { it.meters }, 1.0)
        assertEquals(r.distanceM / 4.0, r.durationS, 1.0)
        assertEquals(stop, r.snapped[2])
        // the line runs on west to east, the plan points in order, no piece twice
        val lons = r.points.filter { it.lat == lat }.map { it.lon }
        assertEquals(lons.sorted(), lons)
        val others = lons.filter { it != junction.lon }
        assertEquals(others.distinct(), others)
        assertEquals(2, lons.count { it == junction.lon }) // to the path and back from it

    }

    @Test fun aLongLegIsNotRoutedAgainForASpurAtItsStart() {
        // the stop's path leaves the road 140 m after point 1; point 3 is 4.5 km on
        val junction = road(7.4020); val stop = south(junction, 40.0)
        val wps = listOf(road(7.4000), stop, road(7.4660))
        val e = RoadEngine(junction, stop)
        val r = Planner(e).plan(wps, Activity.HIKE, 2)
        assertEquals(3, e.calls.size)
        assertEquals(listOf(wps[0], stop), e.calls[1])
        val end = e.calls[2].last()
        assertNotEquals(wps[2], end) // a point of the first line about 1 km on, not point 3
        assertTrue(Geo.distance(junction, end) in Planner.SPLICE_REACH_M..Planner.SPLICE_REACH_M + 110.0)
        assertTrue(r.skipped.isEmpty())
        assertEquals(Geo.distance(wps[0], wps[2]) + 80.0, r.distanceM, 1.0)
        assertEquals(wps[2], r.points.last())
    }

    @Test fun aUTurnAtTheSplicePointRoutesTheChunkAgainAsBefore() {
        val junction = road(7.4300); val stop = south(junction, 40.0)
        val wps = listOf(road(7.4000), road(7.4200), stop, road(7.4400), road(7.4600))
        val e = RoadEngine(junction, stop, uTurn = true)
        val r = Planner(e).plan(wps, Activity.HIKE, 2)
        assertEquals(listOf(wps, listOf(wps[1], stop), listOf(stop, wps[3]), wps.subList(0, 3), wps.subList(2, 5)), e.calls)
        assertTrue(r.skipped.isEmpty())
        assertTrue(WaypointSnap.distToLine(stop, r.points) < 1.0)
    }

    @Test fun aLegWithoutARouteRoutesTheChunkAgainAsBefore() {
        val junction = road(7.4300); val stop = south(junction, 40.0)
        val wps = listOf(road(7.4000), road(7.4200), stop, road(7.4400), road(7.4600))
        val e = RoadEngine(junction, stop, fail = { it == listOf(stop, wps[3]) })
        val r = Planner(e).plan(wps, Activity.HIKE, 2)
        assertEquals(listOf(wps, listOf(wps[1], stop), listOf(stop, wps[3]), wps.subList(0, 3), wps.subList(2, 5)), e.calls)
        assertTrue(r.skipped.isEmpty())
    }

    @Test fun twoCutStopsInARowAreOneSplice() {
        val j1 = road(7.4300); val s1 = south(j1, 40.0)
        val j2 = road(7.4330); val s2 = south(j2, 40.0)
        val wps = listOf(road(7.4000), road(7.4200), s1, s2, road(7.4400), road(7.4600))
        // one engine for both stops: a request with a stop inside rides past it
        val e = object : RoutingEngine {
            val calls = mutableListOf<List<GeoPoint>>()
            val a = RoadEngine(j1, s1); val b = RoadEngine(j2, s2)
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                calls += waypoints
                return when {
                    waypoints.size == 2 && s1 in waypoints && s2 in waypoints -> { // s1 → j1 → j2 → s2
                        val l = listOf(s1, j1, j2, s2).map { it.copy(way = pathWay) }
                        RouteResult(l, Geo.length(l), 0.0, 0.0, 0.0, matched = waypoints)
                    }
                    s1 in waypoints && waypoints.last() != s1 && waypoints.first() != s1 ->
                        a.route(waypoints.filter { it != s2 }, activity, sacLimit).let { it.copy(matched = waypoints) }
                    s1 in waypoints -> a.route(waypoints, activity, sacLimit)
                    else -> b.route(waypoints, activity, sacLimit)
                }
            }
        }
        val r = Planner(e).plan(wps, Activity.HIKE, 2)
        assertEquals(listOf(wps, listOf(wps[1], s1), listOf(s1, s2), listOf(s2, wps[4])), e.calls)
        assertTrue(r.skipped.isEmpty())
        assertTrue(WaypointSnap.distToLine(s1, r.points) < 1.0 && WaypointSnap.distToLine(s2, r.points) < 1.0)
    }

    @Test fun doublesFindsAUTurnOnlyWhereTheLineGoesBack() {
        val prev = listOf(road(7.4000), road(7.4010), road(7.4020))
        assertTrue(Planner.doubles(prev, listOf(road(7.4020), road(7.4010)))) // straight back
        assertTrue(!Planner.doubles(prev, listOf(road(7.4020), road(7.4030)))) // straight on
        assertTrue(!Planner.doubles(prev, listOf(road(7.4020), south(road(7.4020), 100.0)))) // a right angle
    }

    @Test fun climbMirrorsBRoutersTenMetreFilter() {
        fun line(vararg e: Double) = e.mapIndexed { i, v -> GeoPoint(lat, 7.4 + i * 0.001, v) }
        assertEquals(0.0 to 0.0, Planner.climb(line(100.0, 108.0, 100.0, 109.0)))
        assertEquals(10.0 to 0.0, Planner.climb(line(100.0, 120.0)))
        assertEquals(10.0 to 10.0, Planner.climb(line(100.0, 120.0, 100.0)))
    }

    /**
     * Review routing H3: an unforeseen leg (here: an empty line with NaN numbers) must not fail the whole plan with a raw
     * exception text – the chunk is routed again as before the splice existed, the cut stop as a chunk end.
     */
    @Test fun aBrokenLegNeverFailsThePlan() {
        val junction = road(7.4300); val stop = south(junction, 40.0)
        val wps = listOf(road(7.4000), road(7.4200), stop, road(7.4400), road(7.4600))
        val inner = RoadEngine(junction, stop)
        val broken = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
                if (waypoints.size == 2 && stop in waypoints) RouteResult(emptyList(), Double.NaN, 0.0, 0.0, Double.NaN, emptyList(), emptyList())
                else inner.route(waypoints, activity, sacLimit)
        }
        val r = Planner(broken).plan(wps, Activity.HIKE, 2)
        assertTrue(r.error == null)
        assertTrue(r.points.size >= 2)
        assertTrue(r.distanceM.isFinite())
    }
}

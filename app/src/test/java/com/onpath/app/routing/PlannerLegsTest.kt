package com.onpath.app.routing

import com.onpath.app.data.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joins of a plan routed leg by leg (PLANNER_VERSION 4, owner 2026-11-05): a point tapped beside the road, snapped
 * onto a side way, makes its two legs go in and back the same way. A whole BRouter request cut that there-and-back
 * (its via correction); the Planner does the same at the join – not at a pinned point, not beyond the via correction's
 * reach, and never when the point lies on that side way (owner rule: a point on a way is always visited, core feature C3).
 * Real data: tools/routing/jvm-test/LegsVsWholeTest.kt (old vs new on random plans), SpurStopJvmTest.kt.
 */
class PlannerLegsTest {
    private val lat = 51.48
    /** A point on the road (an east-west line at [lat]) at [lon]. */
    private fun road(lon: Double) = GeoPoint(lat, lon)
    /** [m] metres south of [p] (0.00001° lat ≈ 1.11 m). */
    private fun south(p: GeoPoint, m: Double) = GeoPoint(p.lat - m / 111_195.0, p.lon)

    private val roadWay = WayAttrs("road", "asphalt")
    private val pathWay = WayAttrs("path", "ground")

    private val a = road(7.400)
    private val c = road(7.412)
    private val junction = road(7.406)

    /**
     * A road along [lat] with a vertex every 0.0015° and a side path south from [junction] to [spot] ([pathM] long). The
     * plan point [stop] is matched at [spot]: a leg to it goes along the road to the junction and down the path, a leg
     * from it back up. Numbers from the line: metres, way types and surfaces per span.
     */
    private inner class SideWayEngine(val stop: GeoPoint, pathM: Double) : RoutingEngine {
        val spot = south(junction, pathM)
        val calls = mutableListOf<List<GeoPoint>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            val (p, q) = waypoints
            val line = ArrayList<GeoPoint>()
            fun along(from: GeoPoint, to: GeoPoint) {
                val step = if (to.lon >= from.lon) 0.0015 else -0.0015
                var v = Math.round(from.lon / 0.0015) * 0.0015 + step
                line += from.copy(way = roadWay)
                while ((step > 0 && v < to.lon - 1e-9) || (step < 0 && v > to.lon + 1e-9)) { line += road(v).copy(way = roadWay); v += step }
            }
            when {
                q == stop -> { along(p, junction); line += junction.copy(way = pathWay); line += spot.copy(way = pathWay) }
                p == stop -> { line += spot.copy(way = pathWay); along(junction, q); line += q.copy(way = roadWay) }
                else -> { along(p, q); line += q.copy(way = roadWay) }
            }
            val d = Geo.length(line)
            fun shares(key: (WayAttrs) -> String) = (1 until line.size).groupBy { key(line[it - 1].way!!) }
                .map { (k, v) -> Share(k, v.sumOf { Geo.distance(line[it - 1], line[it]) }) }.sortedByDescending { it.meters }
            val matched = waypoints.map { if (it == stop) spot else it }
            return RouteResult(line, d, 0.0, 0.0, d / 4.0, shares { it.wayType }, shares { it.surface }, matched = matched)
        }
    }

    private fun roadOnly() = Geo.distance(a, c)

    @Test fun aTapBesideTheRoadSnappedOntoASideWayIsTrimmedAtTheJoin() {
        // tapped 35 m beyond the end of a 40 m path: the user meant the road, not the path (as the via correction did)
        val tap = south(junction, 75.0)
        val e = SideWayEngine(tap, 40.0)
        val r = Planner(e).plan(listOf(a, tap, c), Activity.BIKE_TOURING, 2)
        assertEquals(listOf(listOf(a, tap), listOf(tap, c)), e.calls)
        assertFalse("the spike is gone", r.points.any { Geo.distance(it, e.spot) < 1.0 })
        assertEquals(roadOnly(), r.distanceM, 2.0)
        assertEquals(listOf("road"), r.wayTypes.map { it.key })
        assertEquals(roadOnly(), r.wayTypes.sumOf { it.meters }, 2.0)
        assertTrue("the point lies on the line at the junction", Geo.distance(r.snapped[1]!!, junction) < 2.0)
        assertTrue(r.skipped.isEmpty())
    }

    @Test fun aStopOnTheSideWayIsVisited() {
        val e = SideWayEngine(south(junction, 40.0), 40.0)
        val r = Planner(e).plan(listOf(a, e.spot, c), Activity.BIKE_TOURING, 2)
        assertTrue("the line goes to the stop", r.points.any { Geo.distance(it, e.spot) < 1.0 })
        assertEquals(roadOnly() + 80.0, r.distanceM, 2.0)
        assertEquals(setOf("road", "path"), r.wayTypes.map { it.key }.toSet())
        assertTrue(r.skipped.isEmpty())
    }

    @Test fun aPinnedPointIsNeverTrimmed() {
        val tap = south(junction, 75.0)
        val e = SideWayEngine(tap, 40.0)
        val r = Planner(e).plan(listOf(a, tap, c), Activity.BIKE_TOURING, 2, pinned = setOf(1))
        assertTrue(r.points.any { Geo.distance(it, e.spot) < 1.0 })
        assertEquals(roadOnly() + 80.0, r.distanceM, 2.0)
    }

    @Test fun aSideWayLongerThanTheViaCorrectionStays() {
        val tap = south(junction, 480.0) // 30+ m beyond the end of a 450 m path: bikes cut at most 400 m
        val e = SideWayEngine(tap, 450.0)
        val r = Planner(e).plan(listOf(a, tap, c), Activity.BIKE_TOURING, 2)
        assertTrue(r.points.any { Geo.distance(it, e.spot) < 1.0 })
    }

    @Test fun onFootOnlyShortSpikesAreTrimmed() {
        // 60 m: cut for a bike (≤ 400 m), kept on foot (≤ 50 m – a hiker's point on a spur is usually a goal)
        val tap = south(junction, 100.0)
        val bike = SideWayEngine(tap, 60.0)
        assertFalse(Planner(bike).plan(listOf(a, tap, c), Activity.BIKE_TOURING, 2).points.any { Geo.distance(it, bike.spot) < 1.0 })
        val hike = SideWayEngine(tap, 60.0)
        assertTrue(Planner(hike).plan(listOf(a, tap, c), Activity.HIKE, 2).points.any { Geo.distance(it, hike.spot) < 1.0 })
        val shortHike = SideWayEngine(south(junction, 75.0), 40.0)
        assertFalse(Planner(shortHike).plan(listOf(a, south(junction, 75.0), c), Activity.HIKE, 2).points
            .any { Geo.distance(it, shortHike.spot) < 1.0 })
    }

    @Test fun legsThatOnlyMeetAreNotTouched() {
        val b = road(7.406)
        val e = SideWayEngine(GeoPoint(0.0, 0.0), 40.0)
        val r = Planner(e).plan(listOf(a, b, c), Activity.BIKE_TOURING, 2)
        assertEquals(roadOnly(), r.distanceM, 2.0)
        assertEquals(b, r.snapped[1])
        assertEquals(2, r.legs.size)
    }
}

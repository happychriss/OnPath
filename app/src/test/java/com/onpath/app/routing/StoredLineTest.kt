package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.app.data.LegFiles
import com.onpath.app.data.TourPlan
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A loaded route's line at once (owner 2026-11-07, "saved routes load without routing again"): every source of a loaded
 * plan – a saved route, a Zuletzt draft, a done tour's plan copy, a browser plan – either takes its stored legs (no
 * router run) or shows its stored line as a stand-in while the run routes; the line is cut at the plan points.
 */
class StoredLineTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Records every request; each leg is a line of 10 points slightly aside of the straight line. */
    private class Engine : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            val a = waypoints.first(); val b = waypoints.last()
            val line = (0..9).map { k ->
                val f = k / 9.0
                GeoPoint(a.lat + (b.lat - a.lat) * f + (if (k in 1..8) 0.0005 else 0.0), a.lon + (b.lon - a.lon) * f, 300.0 + k,
                    WayAttrs("track", "gravel"))
            }
            return RouteResult(line, Geo.length(line), 9.0, 0.0, 600.0, matched = listOf(a, b))
        }
    }

    private val p = (0..4).map { GeoPoint(46.6 + it * 0.02, 11.1 + it * 0.015) }
    private val act = Activity.BIKE_TOURING

    private fun store() = LegFiles(tmp.root.resolve("legs"), { "r1-0123456789ab" }, { "1730000000000" })

    /** A new process (app restart): empty memory, the phone's leg store. */
    private fun restart() = LegCache(store = store())

    private fun routeOf(pts: List<GeoPoint>, cache: LegCache, e: Engine = Engine()) = Planner(e, legs = cache).plan(pts, act, 2)

    // ---- the line cut at the plan points ----

    @Test fun theStoredLineIsCutAtThePointsNearestEachPlanPoint() {
        val pts = listOf(p[0], p[1], p[2], p[3])
        val line = routeOf(pts, LegCache()).points
        val pieces = StoredLine.split(pts, line)!!
        assertEquals(3, pieces.size)
        assertEquals(line.first(), pieces[0].first())
        assertEquals(line.last(), pieces[2].last())
        for (i in 0 until 2) assertEquals(pieces[i].last(), pieces[i + 1].first())
        for (i in 1..2) assertTrue(Geo.distance(pieces[i].first(), pts[i]) < 1.0)
        assertEquals(line.size + 2, pieces.sumOf { it.size }) // the two cut points are in both pieces
    }

    @Test fun aRoundTripAndAThereAndBackAreCutInOrder() {
        val round = listOf(p[0], p[1], p[2], p[0])
        val line = routeOf(round, LegCache()).points
        val pieces = StoredLine.split(round, line)!!
        assertEquals(3, pieces.size)
        assertTrue(Geo.distance(pieces[1].first(), p[1]) < 1.0)
        assertTrue(Geo.distance(pieces[2].first(), p[2]) < 1.0)
        // there and back: p1 is passed again on the way back – each point at its own passage
        val back = listOf(p[0], p[1], p[2], p[1], p[0])
        val l2 = routeOf(back, LegCache()).points
        val q = StoredLine.split(back, l2)!!
        assertEquals(4, q.size)
        assertTrue(q.all { it.size >= 2 })
        assertEquals(l2.size + 3, q.sumOf { it.size })
    }

    @Test fun aLineOfOtherPointsOrOnlyThePointsIsNoStandIn() {
        val line = routeOf(listOf(p[0], p[1], p[2]), LegCache()).points
        // a plan point 2 km from the line: not this plan's line
        assertNull(StoredLine.split(listOf(p[0], GeoPoint(p[1].lat, p[1].lon + 0.03), p[2]), line))
        // the points as the "line" (a browser plan not routed yet, a copy without its line, a straight plan)
        assertNull(StoredLine.split(listOf(p[0], p[1], p[2]), listOf(p[0], p[1], p[2])))
        assertNull(StoredLine.preview(listOf(p[0], p[1], p[2]), act, 2, emptySet(), LegCache(), listOf(p[0], p[1], p[2])))
    }

    // ---- the sources of a loaded plan ----

    @Test fun aSavedRouteFromBeforeTheLegStoreShowsItsLineAtOnceAndIsRouted() {
        val pts = listOf(p[0], p[1], p[2])
        val saved = routeOf(pts, LegCache()).points.map { GeoPoint(it.lat, it.lon, it.ele) } // what TourDb keeps: no attributes
        val stand = StoredLine.preview(pts, act, 2, emptySet(), restart(), saved)!!
        assertEquals(2, stand.fromLine)
        assertEquals(0, stand.stale)
        assertEquals(saved, stand.route.points)
        assertNull(stand.route.error)
        assertTrue(stand.route.legs.none { it.straight })
        // the run routes every leg (the saved line has no way attributes: never taken as the route) – and stores them
        val e = Engine()
        routeOf(pts, restart(), e)
        assertEquals(2, e.calls.size)
        // the next load: nothing to route, no stand-in needed
        assertNull(StoredLine.preview(pts, act, 2, emptySet(), restart(), saved))
        e.calls.clear(); routeOf(pts, restart(), e); assertTrue(e.calls.isEmpty())
    }

    @Test fun aZuletztDraftLoadsWithoutRouting() {
        // a plan left the map under Zuletzt: it was routed on the map, so its legs are stored
        val pts = listOf(p[1], p[2], p[3], p[4])
        val first = routeOf(pts, restart())
        val e = Engine()
        assertEquals(first, routeOf(pts, restart(), e))
        assertTrue(e.calls.isEmpty())
    }

    @Test fun aDoneToursPlanCopyShowsItsLineAtOnceAndReusesItsLegs() {
        val pts = listOf(p[0], p[2], p[4])
        val routed = routeOf(pts, LegCache())
        // the frozen copy in tour_plan keeps the line (6 decimals) – read back as the app does
        val copy = TourPlan.fromJson(TourPlan("Ride", act.name, 2, pts.map { GpxWaypoint(it.lat, it.lon, null) },
            line = routed.points.map { TrackPoint(it.lat, it.lon, it.ele) }).toJson())!!
        val line = copy.line.map { it.toGeo() }
        val stand = StoredLine.preview(pts, act, 2, emptySet(), restart(), line)
        assertNotNull(stand)
        assertEquals(2, stand!!.fromLine)
        // ridden on this phone, its legs were stored when it was planned: no router run
        routeOf(pts, restart())
        val e = Engine()
        routeOf(pts, restart(), e)
        assertTrue(e.calls.isEmpty())
        assertNull(StoredLine.preview(pts, act, 2, emptySet(), restart(), line))
    }

    @Test fun aBrowserPlanRoutedWhenItArrivedLoadsWithoutRouting() {
        val pts = listOf(p[0], p[3], p[4])
        // the browser saves only the points; routeSyncedPlans routes it on arrival through the same leg cache
        assertNull(StoredLine.preview(pts, act, 2, emptySet(), restart(), pts))
        Planner(Engine(), RouteCancel(), legs = restart()).plan(pts, act, 2)
        val e = Engine()
        routeOf(pts, restart(), e)
        assertTrue(e.calls.isEmpty())
    }

    @Test fun pointsMovedOntoTheLineAfterTheRunKeepTheirLegs() {
        // taps 30 m beside the way: the router matches them on it, the plan moves them there (WaypointSnap) – and saves
        // those moved points; the legs must be found under them, or every load routes the whole plan again
        val e = object : RoutingEngine {
            var calls = 0
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                calls++
                val m = waypoints.map { GeoPoint(it.lat + 0.00027, it.lon) }
                val line = (0..9).map { k -> val f = k / 9.0; GeoPoint(m[0].lat + (m[1].lat - m[0].lat) * f, m[0].lon + (m[1].lon - m[0].lon) * f, 300.0) }
                return RouteResult(line, Geo.length(line), 0.0, 0.0, 100.0, matched = m)
            }
        }
        val taps = listOf(p[0], p[1], p[2])
        val cache = restart()
        val first = Planner(e, legs = cache).plan(taps, act, 2)
        val snap = PlanResult.snap(taps, first, emptySet(), emptySet())
        assertTrue(snap.moved)
        for (i in 0 until taps.size - 1) assertTrue(cache.alias(taps[i], taps[i + 1], snap.points[i], snap.points[i + 1], act, 2))
        e.calls = 0
        val loaded = Planner(e, legs = restart()).plan(snap.points, act, 2)
        assertEquals(0, e.calls)
        assertEquals(first.points, loaded.points)
    }
}

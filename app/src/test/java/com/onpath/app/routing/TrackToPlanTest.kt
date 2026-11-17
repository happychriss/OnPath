package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A track as a plan with few route points. */
class TrackToPlanTest {
    private val m = 0.0000089932
    private fun p(x: Double, y: Double) = GeoPoint(46.0 + y * m, 11.0 + x * m / 0.6947)
    private fun line(vararg c: Pair<Double, Double>): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        for (k in 0 until c.size - 1) {
            val (x0, y0) = c[k]; val (x1, y1) = c[k + 1]
            val n = (kotlin.math.hypot(x1 - x0, y1 - y0) / 10).toInt().coerceAtLeast(1)
            for (s in 0 until n) out += p(x0 + (x1 - x0) * s / n, y0 + (y1 - y0) * s / n)
        }
        out += p(c.last().first, c.last().second); return out
    }

    /** "The router" = a straight line between the two points (worst case for a track with corners). */
    private class Straight : RoutingEngine {
        var calls = 0
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls++
            val a = waypoints.first(); val b = waypoints.last()
            val pts = (0..20).map { GeoPoint(a.lat + (b.lat - a.lat) * it / 20, a.lon + (b.lon - a.lon) * it / 20) }
            return RouteResult(pts, com.onpath.app.data.Geo.length(pts), 0.0, 0.0, 0.0)
        }
    }

    @Test fun aStraightTrackNeedsOnlyStartAndGoal() {
        val r = TrackToPlan.fit(line(0.0 to 0.0, 2000.0 to 0.0), Straight(), Activity.BIKE_TOURING)
        assertEquals(2, r.points.size); assertEquals(1.0, r.follows, 0.01)
    }

    @Test fun anLShapedTrackGetsOnePointAtTheCorner() {
        val r = TrackToPlan.fit(line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 1000.0), Straight(), Activity.BIKE_TOURING)
        assertEquals(3, r.points.size)
        assertTrue("the middle point is at the corner", com.onpath.app.data.Geo.distance(r.points[1], p(1000.0, 0.0)) < 30)
        assertTrue(r.follows > 0.95)
    }

    @Test fun aRoundTripGetsItsFarPointAndCorners() {
        val r = TrackToPlan.fit(line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 1000.0, 0.0 to 1000.0, 0.0 to 0.0), Straight(), Activity.HIKE)
        // start, three corners, goal = start: 5 points
        assertEquals(5, r.points.size)
        assertTrue(r.follows > 0.95)
    }

    @Test fun aWigglyTrackStaysFarBelowItsSize() {
        // 10 km zigzag of 200 m teeth: the straight "router" needs a point per tooth, but never one per GPS point
        val c = (0..50).map { (it * 200.0) to (if (it % 2 == 0) 0.0 else 150.0) }.toTypedArray()
        val tr = line(*c)
        val r = TrackToPlan.fit(tr, Straight(), Activity.BIKE_TOURING)
        assertTrue("${r.points.size} points for ${tr.size}", r.points.size <= 52)
        assertTrue(r.calls <= TrackToPlan.MAX_CALLS)
    }

    @Test fun legsWithoutARouteAreCounted() {
        val never = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult = throw RoutingException("no way")
        }
        val r = TrackToPlan.fit(line(0.0 to 0.0, 500.0 to 0.0), never, Activity.HIKE)
        assertTrue(r.failedLegs >= 1)
        assertTrue(r.points.size >= 2)
    }

    /** Owner 2026-11-01 / review routing M3: Cancel in the modal box stops the fit – the running request included. */
    @Test fun cancelStopsTheFit() {
        val c = (0..50).map { (it * 200.0) to (if (it % 2 == 0) 0.0 else 150.0) }.toTypedArray()
        val cancel = RouteCancel()
        var withLimits = 0
        val eng = object : RoutingEngine {
            val inner = Straight()
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int) = inner.route(waypoints, activity, sacLimit)
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
                withLimits++
                assertTrue(limits.cancel === cancel)
                if (withLimits == 3) cancel.cancel() // the user taps Cancel during the third request
                return inner.route(waypoints, activity, sacLimit)
            }
        }
        TrackToPlan.fit(line(*c), eng, Activity.BIKE_TOURING, cancel = cancel)
        assertEquals("stops right after the request that ran", 3, withLimits)
    }

    // ---- multi-day tours, stage 3 (2026-11-17): long tracks, trim, append ----

    @Test fun aLongTrackGetsMoreCallsAndSeedsSoNoCallRoutesHundredsOfKm() {
        assertEquals(TrackToPlan.MAX_CALLS, TrackToPlan.maxCalls(10_000.0))
        assertEquals(376, TrackToPlan.maxCalls(564_000.0))
        val longest = ArrayList<Double>()
        val eng = object : RoutingEngine {
            val inner = Straight()
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                longest += com.onpath.app.data.Geo.distance(waypoints.first(), waypoints.last())
                return inner.route(waypoints, activity, sacLimit)
            }
        }
        val seen = ArrayList<Pair<Int, Int>>()
        // 120 km straight: seeds every 25 km, every call shorter than 30 km
        val r = TrackToPlan.fit(line(0.0 to 0.0, 120_000.0 to 0.0), eng, Activity.BIKE_TOURING, progress = { c, m -> seen += c to m })
        assertTrue("longest call ${longest.max()}", longest.max() < 30_000)
        assertEquals(1.0, r.follows, 0.01)
        assertEquals(r.calls, seen.size)
        assertEquals(80, seen.last().second)
    }

    @Test fun cutKeepsOnlyThePartBetweenTheHandles() {
        val tr = line(0.0 to 0.0, 1000.0 to 0.0)
        val c = TrackToPlan.cut(tr, 250.0, 600.0)
        assertEquals(350.0, com.onpath.app.data.Geo.length(c), 2.0)
        assertTrue(com.onpath.app.data.Geo.distance(c.first(), p(250.0, 0.0)) < 2)
        assertTrue(com.onpath.app.data.Geo.distance(c.last(), p(600.0, 0.0)) < 2)
        // the other way round and beyond the ends: clamped, swapped
        assertEquals(1000.0, com.onpath.app.data.Geo.length(TrackToPlan.cut(tr, 5000.0, -3.0)), 2.0)
        assertEquals(tr, TrackToPlan.cut(tr, 300.0, 300.0))
    }

    @Test fun appendPutsTheTrackBehindTheLastPointAndTurnsItRound() {
        val plan = listOf(p(0.0, 0.0), p(1000.0, 0.0))
        // runs on from the goal: kept as it is, the joint (same place) once
        assertEquals(listOf(p(0.0, 0.0), p(1000.0, 0.0), p(2000.0, 0.0)), TrackToPlan.append(plan, listOf(p(1010.0, 0.0), p(2000.0, 0.0))))
        // its end is nearer: turned round
        assertEquals(listOf(p(0.0, 0.0), p(1000.0, 0.0), p(1500.0, 0.0), p(3000.0, 0.0)),
            TrackToPlan.append(plan, listOf(p(3000.0, 0.0), p(1500.0, 0.0))))
        assertEquals(plan, TrackToPlan.append(plan, emptyList()))
    }
}

package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A track as a plan with few route points (owner 2026-10-28). */
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
}

package com.onpath.app.navigation

import com.onpath.app.data.FollowProgress
import com.onpath.app.navigation.Trace.fix
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.navigation.Trace.walk
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.time.RouteTimes
import com.onpath.app.time.TripPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walk-readiness edge cases: degenerate lines, duplicate points, far / jumping fixes, a line
 * replaced or removed mid-walk. Nothing may throw, and no NaN may reach "to go".
 */
class NavEdgeCasesTest {

    private fun finite(d: Double?) = d == null || d.isFinite()

    private val degenerate: List<List<GeoPoint>> = listOf(
        emptyList(),
        listOf(pt(0.0, 0.0)),
        listOf(pt(0.0, 0.0), pt(0.0, 0.0)),                              // zero length
        listOf(pt(0.0, 0.0), pt(0.0, 0.0), pt(0.0, 0.0)),
        listOf(pt(0.0, 0.0), pt(100.0, 0.0)),                            // 2 points
        listOf(pt(0.0, 0.0), pt(50.0, 0.0), pt(50.0, 0.0), pt(50.0, 0.0), pt(100.0, 0.0)), // duplicates inside
    )

    @Test fun navLineDegenerateLinesNeverThrow() {
        for (pts in degenerate) {
            val l = NavLine(pts)
            for (p in listOf(pt(0.0, 0.0), pt(50.0, 10.0), pt(100_000.0, 0.0))) {
                for ((a, b) in listOf(0.0 to l.lengthM, -500.0 to 500.0, 300.0 to 200.0, Double.NaN to 10.0)) {
                    val m = l.match(p, a, b, preferAlongM = 20.0)
                    if (m != null) assertTrue("$pts", m.alongM.isFinite() && m.distanceM.isFinite())
                    l.parts(p, 60.0, a, b).forEach { assertTrue(it.alongM.isFinite()) }
                }
                l.matchEarliest(p)
                l.alongsInOrder(listOf(p, p))
            }
            for (a in listOf(-10.0, 0.0, 5.0, 1e9)) {
                l.pointAt(a); l.restFrom(a); l.segmentAt(a)
                if (l.isUsable) l.aheadBearing(a)
            }
            NavLine.sameRoute(pts, pts.reversed())
        }
    }

    @Test fun detectorOnDegenerateLinesNeverThrows() {
        for (pts in degenerate) {
            val d = OffRouteDetector(40.0).apply { setLine(NavLine(pts)) }
            for (f in walk(-20.0 to 0.0, 150.0 to 0.0)) {
                val s = d.onFix(f)
                assertTrue("$pts", d.progressM.isFinite() && finite(s.distanceM) && finite(d.shownAlongM))
            }
            d.gpsLost()
            d.forceCheck(fix(500.0, 500.0, 1_000_000))
        }
    }

    @Test fun duplicatePointsLineIsFollowed() {
        // a route with a run of identical points (router joins, dedupe failures) is walked like any other
        val base = line(0.0 to 0.0, 500.0 to 0.0, 500.0 to 500.0)
        val dup = base.take(50) + List(5) { base[50] } + base.drop(50)
        val d = OffRouteDetector(40.0).apply { setLine(NavLine(dup)) }
        val s = walk(0.0 to 2.0, 500.0 to 2.0, 502.0 to 500.0).map { d.onFix(it) }
        assertTrue(s.none { it.confirmedNow })
        assertEquals(1000.0, d.progressM, 15.0)
    }

    @Test fun farAwayAndJumpingFixes() {
        val l = line(0.0 to 0.0, 1000.0 to 0.0)
        val d = OffRouteDetector(40.0).apply { setLine(NavLine(l)) }
        var t = 0L
        walk(0.0 to 0.0, 300.0 to 0.0).forEach { d.onFix(it); t = it.timeMs }
        // one fix 80 km away, then a zig-zag of wild jumps, then good again
        d.onFix(fix(80_000.0, 0.0, t + 1000))
        for (k in 0 until 10) d.onFix(fix(310.0 + k, if (k % 2 == 0) 200.0 else -200.0, t + 2000 + k * 1000L))
        assertTrue(d.progressM.isFinite())
        walk(320.0 to 0.0, 600.0 to 0.0, t0Ms = t + 20_000).forEach { d.onFix(it) }
        assertEquals(OffRouteDetector.State.ON_ROUTE, d.state)
        assertEquals(600.0, d.progressM, 20.0)
    }

    @Test fun clockGoingBackwardsIsHarmless() {
        val l = line(0.0 to 0.0, 1000.0 to 0.0)
        val d = OffRouteDetector(40.0).apply { setLine(NavLine(l)) }
        walk(0.0 to 0.0, 200.0 to 0.0, t0Ms = 1_000_000).forEach { d.onFix(it) }
        walk(200.0 to 0.0, 400.0 to 0.0, t0Ms = 0).forEach { d.onFix(it) }
        assertEquals(OffRouteDetector.State.ON_ROUTE, d.state)
        assertEquals(400.0, d.progressM, 15.0)
    }

    @Test fun lineRemovedAndReplacedMidWalk() {
        val l = line(0.0 to 0.0, 1000.0 to 0.0)
        val d = OffRouteDetector(40.0).apply { setLine(NavLine(l)) }
        walk(0.0 to 0.0, 300.0 to 0.0).forEach { d.onFix(it) }
        d.setLine(null)
        val s = d.onFix(fix(310.0, 0.0, 400_000))
        assertNull(s.distanceM)
        assertNull(d.shownAlongM)
        // an empty plan line, then a real one again (the walk continues on it)
        d.setLine(NavLine(emptyList()))
        d.onFix(fix(320.0, 0.0, 401_000))
        val other = OffRouteDetector(40.0).apply { adoptHeading(d) }
        other.setLine(NavLine(line(300.0 to 0.0, 900.0 to 0.0)))
        walk(330.0 to 0.0, 500.0 to 0.0, t0Ms = 402_000).forEach { other.onFix(it) }
        assertEquals(OffRouteDetector.State.ON_ROUTE, other.state)
        assertEquals(200.0, other.shownAlongM!!, 15.0)
    }

    @Test fun navigatorOnZeroLengthLine() {
        val p = pt(0.0, 0.0)
        val n = Navigator(NavSession(NavKind.PLAN, "x", Activity.HIKE, 3, listOf(p, p)))
        walk(0.0 to 0.0, 200.0 to 0.0).forEach { n.onFix(it) }
        assertTrue(n.state.progressM.isFinite())
    }

    @Test fun followProgressAtEdges() {
        assertNull(FollowProgress(listOf(pt(0.0, 0.0))).at(0.0, null))
        val two = FollowProgress(listOf(pt(0.0, 0.0), pt(0.0, 0.0)))
        assertEquals(0.0, two.at(5.0, null)!!.distanceM, 0.0)
        val l = FollowProgress(line(0.0 to 0.0, 1000.0 to 0.0))
        assertEquals(1000.0, l.at(-50.0, 3.0)!!.distanceM, 5.0)
        assertEquals(0.0, l.at(5000.0, null)!!.distanceM, 0.0)
        assertEquals(l.at(1000.0, null)!!.index, l.at(999.9, null)!!.index)
    }

    @Test fun tripPaceNoNaNOnDegenerateLines() {
        val trip = TripPace()
        for (pts in degenerate.filter { it.isNotEmpty() }) {
            trip.setLine(RouteTimes.of(pts, Activity.HIKE), null)
            trip.onFix(10.0, 1_000_000, 46.68, 11.13, 0.0, true)
            trip.onFix(20.0, 1_010_000, 46.68, 11.13, null, false)
            trip.tick(200.0, 1_200_000)
            val s = trip.snapshot()
            assertTrue(finite(s.toGoS?.toDouble()))
        }
        trip.setLine(null, null)
        trip.onFix(300.0, 1_300_000, 46.68, 11.13, 50.0, true)
        assertNull(trip.snapshot().toGoS)
    }

    @Test fun tripPaceNotOnRouteThenMatched() {
        val l = line(0.0 to 0.0, 2000.0 to 0.0)
        val trip = TripPace()
        trip.setLine(RouteTimes.of(l, Activity.HIKE), null)
        trip.onFix(1.0, 1_000_000, 46.68, 11.13, null, false)
        assertEquals(com.onpath.app.time.EstimateState.NOT_ON_ROUTE, trip.snapshot().state)
        assertNull(trip.snapshot().toGoS)
        trip.onFix(2.0, 1_001_000, 46.68, 11.13, 100.0, true)
        val s = trip.snapshot()
        assertEquals(com.onpath.app.time.EstimateState.OK, s.state)
        assertNotNull(s.toGoS)
        assertFalse(s.toGoS!!.toDouble().isNaN())
        // no fix for 2 min (not in a break) → paused, no GPS
        trip.tick(130.0, 1_130_000)
        assertEquals(com.onpath.app.time.EstimateState.PAUSED, trip.snapshot().state)
    }
}

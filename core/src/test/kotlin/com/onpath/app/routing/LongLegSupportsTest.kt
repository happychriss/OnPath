package com.onpath.app.routing

import com.onpath.app.data.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Support points on long legs (owner 2026-11-27, „Lange Browser-Touren auf dem Handy“): a leg over
 * [PlanAnchors.SUPPORT_LEG_M] gets hidden via points on its line about every [PlanAnchors.SUPPORT_EVERY_M], so a device
 * that did not route it routes short pieces – and the device that did cuts them out of its cached leg, nothing routed.
 */
class LongLegSupportsTest {
    /** A straight line along lat 47, a point every km, [km] long, 1e-6° values (BRouter's grid). */
    private fun line(km: Int): List<GeoPoint> {
        val step = 1000.0 / Geo.mPerDegLon(47.0)
        return List(km + 1) { GeoPoint(47.0, Math.round((11.0 + it * step) * 1e6) / 1e6) }
    }

    private fun alongKm(l: List<GeoPoint>, g: GeoPoint) = Geo.cumulative(l)[l.indexOf(g)] / 1000.0

    @Test fun aShortLegGetsNone() {
        val l = line(140)
        assertEquals(emptyList<GeoPoint>(), PlanAnchors.supportsFor(l, l.first(), l.last()))
    }

    @Test fun a630KmLegGetsTwelveEvenlySpreadExactLinePoints() {
        val l = line(630)
        val s = PlanAnchors.supportsFor(l, l.first(), l.last())
        assertEquals(12, s.size)
        val km = s.map { alongKm(l, it) }
        assertEquals(km.sorted(), km) // route order
        for ((k, x) in km.withIndex()) assertEquals("support ${k + 1}", 630.0 / 13 * (k + 1), x, 1.0)
        assertTrue(s.none { it == l.first() || it == l.last() })
        assertTrue(s.all { it in l }) // exact line points, never interpolated
    }

    @Test fun aVeryLongLegIsCappedAtTwiceTheSideLimit() {
        val l = line(1200)
        val s = PlanAnchors.supportsFor(l, l.first(), l.last())
        assertEquals(16, s.size)
        val km = s.map { alongKm(l, it) }
        assertEquals(1200.0 / 17, km.first(), 1.0)
    }

    private val profile = AnchorProfile.of(Activity.BIKE_TOURING, 0)

    @Test fun withSupportsSplitsTheLegBetweenItsTwoEnds() {
        val l = line(630)
        val pts = listOf(l.first(), l.last())
        val set = PlanStitch.withSupports(AnchorSet.NONE, VisibleLines(pts, profile, listOf(l)))
        assertEquals(profile, set.profile)
        assertEquals(6, set.anchors[0]!!.after.size)
        assertEquals(6, set.anchors[1]!!.before.size)
        val e = PlanAnchors.expand(pts, set.anchors, emptySet())
        assertEquals(14, e.points.size)
        assertEquals(pts, e.visible)
    }

    @Test fun aLegWithAStitchOrAnotherProfileIsLeftAlone() {
        val l = line(630)
        val pts = listOf(l.first(), l.last())
        val stitched = AnchorSet(mapOf(0 to Anchors(after = listOf(l[300]))), profile)
        assertEquals(stitched, PlanStitch.withSupports(stitched, VisibleLines(pts, profile, listOf(l))))
        val other = AnchorSet(mapOf(0 to Anchors(after = listOf(l[300]))), AnchorProfile.of(Activity.HIKE, 2))
        assertEquals(other, PlanStitch.withSupports(other, VisibleLines(pts, profile, listOf(l))))
        val short = line(70)
        assertEquals(AnchorSet.NONE, PlanStitch.withSupports(AnchorSet.NONE, VisibleLines(listOf(short.first(), short.last()), profile, listOf(short))))
    }

    /** Draws a straight line of km points between the two ends; counts the calls. */
    private class Engine : RoutingEngine {
        var calls = 0
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls++
            val a = waypoints.first(); val b = waypoints.last()
            val n = (Geo.distance(a, b) / 1000.0).toInt().coerceAtLeast(1)
            val l = (0..n).map { i ->
                val t = i.toDouble() / n
                GeoPoint(a.lat + (b.lat - a.lat) * t, Math.round((a.lon + (b.lon - a.lon) * t) * 1e6) / 1e6, 100.0)
            }
            val d = Geo.length(l)
            return RouteResult(l, d, 0.0, 0.0, d / 5.0, matched = listOf(l.first(), l.last()))
        }
    }

    @Test fun theDeviceWithTheLegCachedCutsThePiecesOutNothingRouted() {
        val e = Engine(); val cache = LegCache()
        val a = GeoPoint(47.0, 11.0); val b = GeoPoint(47.0, 11.0 + 290_000.0 / Geo.mPerDegLon(47.0))
        val act = Activity.BIKE_TOURING
        val first = Planner(e, legs = cache).plan(listOf(a, b), act, 0)
        assertEquals(1, e.calls)
        val lines = PlanStitch.linesOf(first, PlanAnchors.expand(listOf(a, b), emptyMap(), emptySet()), AnchorProfile.of(act, 0))!!
        val set = PlanStitch.withSupports(AnchorSet.NONE, lines)
        assertEquals(5, set.anchors.values.sumOf { it.size }) // 290 km: five, about every 48 km
        val expanded = PlanAnchors.expand(listOf(a, b), set.anchors, emptySet())
        assertEquals(6, LegSplit.seed(cache, expanded, act, 0))
        val again = Planner(e, legs = cache).plan(expanded, act, 0)
        assertEquals("all six pieces from the cached leg", 1, e.calls)
        assertEquals(first.distanceM, again.distanceM, 1.0)
    }
}

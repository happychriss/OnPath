package com.onpath.app.routing

import com.onpath.app.data.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A point put in ON the routed line (a night „Übernachtung hier“, a stop at a line point – owner 2026-11-20: the app cuts
 * like the planner): [LegSplit] cuts the known leg there – no router call, two legs whose joined line, distance, climb,
 * time and breakdown are the old leg's. A point beside the line is routed as before.
 */
class LegSplitTest {
    /** Records every request; the line between the two points bends gently, has elevations and two kinds of way. */
    private class Engine : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            val a = waypoints.first(); val b = waypoints.last()
            val n = 40
            val line = (0..n).map { i ->
                val t = i.toDouble() / n
                val bend = 0.004 * Math.sin(t * Math.PI)
                val way = if (i < n / 3) WayAttrs("path", "ground") else WayAttrs("track", "gravel")
                GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t + bend, 400.0 + 300.0 * Math.sin(t * 3.0),
                    if (i < n) way else null)
            }
            val len = Geo.length(line)
            val d = len * 1.03 // BRouter's metres are not the drawn ones
            val path = d / 3.0
            return RouteResult(line, d, 412.0, 177.0, d / 1.2,
                wayTypes = listOf(Share("track", d - path), Share("path", path)),
                surfaces = listOf(Share("gravel", d - path), Share("ground", path)),
                matched = listOf(line.first(), line.last()))
        }
    }

    private val a = GeoPoint(46.60, 11.10)
    private val c = GeoPoint(46.65, 11.12)
    private val d = GeoPoint(46.70, 11.15)
    private val act = Activity.HIKE
    private val sac = 3

    private fun plan(e: Engine, cache: LegCache, vararg pts: GeoPoint) = Planner(e, legs = cache).plan(pts.toList(), act, sac)

    private fun assertSameRoute(before: PlannedRoute, after: PlannedRoute) {
        assertEquals(before.points, after.points)
        assertEquals(before.distanceM, after.distanceM, 1e-6)
        assertEquals(before.ascentM, after.ascentM, 1e-6)
        assertEquals(before.descentM, after.descentM, 1e-6)
        assertEquals(before.durationS, after.durationS, 1e-6)
        for ((x, y) in listOf(before.wayTypes to after.wayTypes, before.surfaces to after.surfaces)) {
            assertEquals(x.map { it.key }.toSet(), y.map { it.key }.toSet())
            for (s in x) assertEquals(s.key, s.meters, y.first { it.key == s.key }.meters, 1e-6)
        }
    }

    @Test fun aPointOnTheLineCutsTheLegWithoutRouting() {
        val e = Engine(); val cache = LegCache()
        val before = plan(e, cache, a, c, d)
        val spot = before.legs[0].points[13] // a night at a line point of the first leg
        e.calls.clear()
        assertEquals(1, LegSplit.seed(cache, listOf(a, spot, c, d), act, sac))
        val after = plan(e, cache, a, spot, c, d)
        assertTrue("no router call: ${e.calls}", e.calls.isEmpty())
        assertSameRoute(before, after)
        assertEquals(3, after.legs.size)
        // the two halves join exactly at the spot and together are the old leg
        assertEquals(spot, after.legs[0].points.last())
        assertEquals(spot, after.legs[1].points.first())
        assertEquals(before.legs[0].points, after.legs[0].points + after.legs[1].points.drop(1))
    }

    /**
     * Owner 2026-11-27 (Hilden – Venedig, a point set ON the route): before a run is weighed, the halves are cut from
     * MEMORY – so the long-leg rule sees them as routed; a leg only in the store is left to the run's own [LegSplit.seed].
     */
    @Test fun beforeTheRunThePointOnTheLineIsCutFromMemory() {
        val e = Engine(); val cache = LegCache()
        val before = plan(e, cache, a, c, d)
        val spot = before.legs[0].points[13]
        val expanded = PlanAnchors.expand(listOf(a, spot, c, d), emptyMap(), emptySet())
        assertEquals(1, LegSplit.seedBeforeRun(cache, expanded, act, sac))
        assertNotNull(cache.inMemory(a, spot, act, sac)); assertNotNull(cache.inMemory(spot, c, act, sac))
        e.calls.clear()
        assertSameRoute(before, plan(e, cache, a, spot, c, d))
        assertTrue("no router call: ${e.calls}", e.calls.isEmpty())
        assertEquals(0, LegSplit.seedBeforeRun(cache, expanded, act, sac)) // nothing left to cut
    }

    /**
     * Step 1c (owner 2026-11-27, Hilden – Venedig): the app's long-leg rule asks about a point set ON a long known leg as
     * two new long legs – until the leg is cut before it is weighed; then nothing is asked (the maps' question for new
     * points and the run weigh the same, PlanRecalcState.longAsk).
     */
    @Test fun aPointOnALongKnownLegIsNotAskedOnceCut() {
        val cache = LegCache()
        val far = GeoPoint(49.12, 11.10) // ~280 km: under the phone's 300 km, each half ~140 km asks on its own
        cache.put(a, far, act, sac, Engine().route(listOf(a, far), act, sac))
        val spot = cache.inMemory(a, far, act, sac)!!.points[20]
        val rule = PhoneLongRule({ RouteCost.DEFAULT_FACTOR }, { x, y, _ -> cache.inMemory(x, y, act, sac) != null }, lowEnd = true)
        val pts = listOf(a, spot, far)
        assertNotNull("not cut: the halves look new and long", rule.ask(pts, act))
        assertEquals(1, LegSplit.seedBeforeRun(cache, PlanAnchors.expand(pts, emptyMap(), emptySet()), act, sac))
        assertNull("cut: nothing to ask", rule.ask(pts, act))
    }

    @Test fun beforeTheRunAnEmptyMemoryCutsNothing() {
        val cache = LegCache()
        val leg = Engine().route(listOf(a, c), act, sac)
        val spot = leg.points[13]
        assertEquals(0, LegSplit.seedBeforeRun(cache, PlanAnchors.expand(listOf(a, spot, c), emptyMap(), emptySet()), act, sac))
        assertNull(cache.inMemory(a, spot, act, sac))
    }

    @Test fun theHalvesAddUpToTheWhole() {
        val leg = Engine().route(listOf(a, c), act, sac)
        val (x, y) = LegSplit.split(leg, leg.points[25])!!
        assertEquals(leg.distanceM, x.distanceM + y.distanceM, 1e-9)
        assertEquals(leg.ascentM, x.ascentM + y.ascentM, 1e-9)
        assertEquals(leg.descentM, x.descentM + y.descentM, 1e-9)
        assertEquals(leg.durationS, x.durationS + y.durationS, 1e-9)
        // in proportion to the line's length on each side
        val f = Geo.length(leg.points.take(26)) / Geo.length(leg.points)
        assertEquals(leg.distanceM * f, x.distanceM, 1e-6)
        for (k in listOf("path", "track")) assertEquals(k, leg.wayTypes.first { it.key == k }.meters,
            (x.wayTypes.firstOrNull { it.key == k }?.meters ?: 0.0) + (y.wayTypes.firstOrNull { it.key == k }?.meters ?: 0.0), 1e-6)
        // the cut at line point 25 lies in the track part: the second half has no path
        assertTrue(y.wayTypes.none { it.key == "path" })
        assertEquals(listOf(leg.points.first(), leg.points[25]), x.matched)
        assertEquals(listOf(leg.points[25], leg.points.last()), y.matched)
    }

    @Test fun withinHalfAMetreOfALinePointIsOnTheLine() {
        val e = Engine(); val cache = LegCache()
        val before = plan(e, cache, a, c)
        val q = before.points[20]
        val near = GeoPoint(q.lat + 0.3 / 111_195.0, q.lon) // 0.3 m north
        e.calls.clear()
        assertEquals(1, LegSplit.seed(cache, listOf(a, near, c), act, sac))
        val after = plan(e, cache, a, near, c)
        assertTrue(e.calls.isEmpty())
        assertSameRoute(before, after)
    }

    @Test fun aPointBesideTheLineIsRoutedAsBefore() {
        val e = Engine(); val cache = LegCache()
        val before = plan(e, cache, a, c)
        val q = before.points[20]
        val beside = GeoPoint(q.lat + 5.0 / 111_195.0, q.lon) // 5 m off: the router may take another way through it
        assertNull(LegSplit.split(e.route(listOf(a, c), act, sac), beside))
        e.calls.clear()
        assertEquals(0, LegSplit.seed(cache, listOf(a, beside, c), act, sac))
        plan(e, cache, a, beside, c)
        assertEquals(listOf(listOf(a, beside), listOf(beside, c)), e.calls)
    }

    @Test fun anEndOfTheLegOrAKnownLegIsNotCut() {
        val e = Engine(); val cache = LegCache()
        val before = plan(e, cache, a, c)
        // the leg's own ends are no cut
        assertNull(LegSplit.split(cache.get(a, c, act, sac)!!, before.points.first()))
        assertNull(LegSplit.split(cache.get(a, c, act, sac)!!, before.points.last()))
        // a point whose legs are known already: left as they are
        val spot = before.points[10]
        val own = RouteResult(listOf(a, spot), 1.0, 0.0, 0.0, 1.0)
        cache.put(a, spot, act, sac, own)
        assertEquals(0, LegSplit.seed(cache, listOf(a, spot, c), act, sac))
        assertEquals(own, cache.get(a, spot, act, sac))
        assertNull(cache.get(spot, c, act, sac))
    }

    @Test fun bothHalvesAreStoredSoAReloadGivesTheSameLine() {
        val store = object : LegStore {
            val legs = HashMap<LegCache.Key, RouteResult>()
            override fun get(key: LegCache.Key) = legs[key]?.let { LegStore.Stored(it, fresh = true) }
            override fun put(key: LegCache.Key, from: GeoPoint, to: GeoPoint, leg: RouteResult) { legs[key] = leg }
        }
        val e = Engine()
        val cache = LegCache(store = store)
        plan(e, cache, a, c)
        val spot = cache.get(a, c, act, sac)!!.points[30]
        LegSplit.seed(cache, listOf(a, spot, c), act, sac)
        val split = plan(e, cache, a, spot, c)
        assertNotNull(store.legs[LegCache.key(a, spot, act, sac)])
        assertNotNull(store.legs[LegCache.key(spot, c, act, sac)])
        // after an app restart: a fresh memory, the same store – no router call, the same line
        e.calls.clear()
        val reloaded = plan(e, LegCache(store = store), a, spot, c)
        assertTrue(e.calls.isEmpty())
        assertSameRoute(split, reloaded)
    }
}

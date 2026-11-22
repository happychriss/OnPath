package com.onpath.app.ui.plan

import com.onpath.app.routing.Activity
import com.onpath.app.routing.AnchorSet
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.RouteCancel
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.ProblemsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The plan on the map as a reader of detour anchors (docs/design/detour-stitch.md §6.3): kept, routed, never cut, never made. */
class PlanAnchorsReaderTest {
    @get:Rule val problems = ProblemsRule()

    private class Store : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
        override var anchorsJson: String? = null
    }

    private fun g(lat: Double) = GeoPoint(lat, 11.0)
    private val a = g(46.10); private val x = g(46.15); private val p = g(46.20); private val y = g(46.25); private val c = g(46.30)
    private val anchors = mapOf(1 to Anchors(listOf(x), listOf(y)))

    private fun loaded(store: Store = Store()) = PlanDocument(store).also { d ->
        d.arriving(listOf(a, p, c), AnchorSet.of(anchors, Activity.HIKE, 2))
        d.edit { clear(); addAll(listOf(a, p, c)) }
    }

    private fun run(pts: List<GeoPoint>, a: Map<Int, Anchors>) = PlanRecalcState.Run(gen = 1, pts = pts, pins = emptySet(),
        act = Activity.HIKE, level = 2, rounds = 1, askAtLimit = false, f = com.onpath.app.time.Factors(), started = 0L,
        changeBefore = null, standBefore = null, anchors = a)

    private fun legs(e: FakeRoutingEngine) = e.calls.map { it.waypoints.first() to it.waypoints.last() }

    @Test fun aLoadedPlanHasItsAnchorsAndTheyAreStored() {
        val store = Store()
        val d = loaded(store)
        assertEquals(anchors, d.anchorSet.anchors)
        assertEquals(anchors, PlanDocument(store).anchorSet.anchors) // the next run of the app
    }

    @Test fun aRestartWithOtherPointsDropsThem() {
        val store = Store()
        loaded(store)
        store.points = listOf(a, g(46.21), c) // the points were written, the anchors not (a kill between two writes)
        assertEquals(emptyMap<Int, Anchors>(), PlanDocument(store).anchorSet.anchors)
    }

    @Test fun theRouterGoesThroughTheAnchors() {
        val e = FakeRoutingEngine()
        val r = PlanRecalcState.route(run(listOf(a, p, c), anchors), e, LegCache(), RouteCancel(), { _, _ -> })
        assertEquals(listOf(a to x, x to p, p to y, y to c), legs(e))
        assertEquals("the plan keeps its three visible points", 3, r.result.points.size)
    }

    @Test fun aKnownLongLegIsNotCutForAPlanWithAnchors() {
        val cache = LegCache()
        val e = FakeRoutingEngine()
        // the old base and its plain halves are known: a plain plan would take them, an anchored one routes its own legs
        PlanRecalcState.route(run(listOf(a, c), emptyMap()), e, cache, RouteCancel(), { _, _ -> })
        e.calls.clear()
        PlanRecalcState.route(run(listOf(a, p, c), anchors), e, cache, RouteCancel(), { _, _ -> })
        assertEquals(listOf(a to x, x to p, p to y, y to c), legs(e))
    }

    @Test fun aPlanWithoutAnchorsStillCutsAtAPointOnItsLine() {
        val cache = LegCache()
        val e = FakeRoutingEngine()
        PlanRecalcState.route(run(listOf(a, c), emptyMap()), e, cache, RouteCancel(), { _, _ -> })
        val onLine = cache.get(a, c, Activity.HIKE, 2)!!.points.let { it[it.size / 2] }
        e.calls.clear()
        PlanRecalcState.route(run(listOf(a, onLine, c), emptyMap()), e, cache, RouteCancel(), { _, _ -> })
        assertTrue("nothing routed for a point on the line", e.calls.isEmpty())
    }

    @Test fun aRoutedPlanIsReadFromTheCacheTheSecondTime() {
        val cache = LegCache()
        val e = FakeRoutingEngine()
        PlanRecalcState.route(run(listOf(a, p, c), anchors), e, cache, RouteCancel(), { _, _ -> })
        e.calls.clear()
        PlanRecalcState.route(run(listOf(a, p, c), anchors), e, cache, RouteCancel(), { _, _ -> })
        assertTrue(e.calls.isEmpty())
    }

    @Test fun anotherProfileRoutesPlainAndTheWayBackHasThemAgain() {
        val store = Store()
        val d = loaded(store)
        assertEquals(emptyMap<Int, Anchors>(), d.anchorsFor(Activity.HIKE, 3))
        assertEquals(anchors, d.anchorsFor(Activity.HIKE, 2))
        assertEquals("a restart keeps which profile they lie on", emptyMap<Int, Anchors>(), PlanDocument(store).anchorsFor(Activity.BIKE_TOURING, 2))
        assertEquals(anchors, PlanDocument(store).anchorsFor(Activity.HIKE, 2))
    }

    @Test fun aSnappedPointKeepsItsAnchors() {
        val store = Store()
        val d = loaded(store)
        val moved = GeoPoint(46.2001, 11.0)
        d.moveSnapped(listOf(a, p, c), listOf(a, moved, c))
        assertEquals(anchors, d.anchorSet.anchors)
        assertEquals("stored under the moved point", anchors, PlanDocument(store).anchorSet.anchors)
    }
}

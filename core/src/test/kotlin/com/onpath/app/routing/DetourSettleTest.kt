package com.onpath.app.routing

import com.onpath.app.data.LegFiles
import com.onpath.app.debug.Problems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "The line settles" (docs/design/detour-stitch.md §8.15): a plan WITH anchors routes the legs that are not the router's own
 * answer for their exact 1e-6° ends (cut legs, legs kept under a moved point) once more, so every device ends on one line.
 */
class DetourSettleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val act = Activity.BIKE_TOURING
    private val sac = 2
    private val v0 = GeoPoint(51.0, 7.0)
    private val a = GeoPoint(51.2, 7.0)
    private val v1 = GeoPoint(51.4, 7.0)
    private val b = GeoPoint(51.6, 7.0)
    private val v2 = GeoPoint(51.8, 7.0)
    private val visible = listOf(v0, v1, v2)
    private val anchors = mapOf(1 to Anchors(listOf(a), listOf(b)))
    private val stitched get() = PlanAnchors.expand(visible, anchors, emptySet())

    @Before fun setUp() { Problems.clear() }

    /** The line of a request goes a little aside (so a router run differs from a cut piece); 3 points = a short-trip retry: direct. */
    private class Engine(val throwing: (List<GeoPoint>) -> Exception? = { null }) : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        var onCall: () -> Unit = {}
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            onCall()
            throwing(waypoints)?.let { throw it }
            val f = waypoints.first(); val t = waypoints.last()
            if (waypoints.size == 3) return RouteResult(listOf(f, t), 600.0, 1.0, 1.0, 100.0, matched = listOf(f, waypoints[1], t))
            val mid = GeoPoint((f.lat + t.lat) / 2, (f.lon + t.lon) / 2 + 0.01)
            val line = if (Math.abs(f.lat - t.lat) < 0.01) listOf(f, GeoPoint(f.lat, f.lon + 0.05), t) else listOf(f, mid, t)
            return RouteResult(line, if (line.size == 3 && Math.abs(f.lat - t.lat) < 0.01) 10_000.0 else 1000.0, 5.0, 5.0, 60.0, matched = listOf(f, t))
        }
    }

    private fun cutLeg(x: GeoPoint, y: GeoPoint) = RouteResult(listOf(x, y), 900.0, 1.0, 1.0, 50.0, matched = listOf(x, y))

    /** The cache as a stitch leaves it: a cut leg under every cut key of [plan]. */
    private fun cutCache(plan: ExpandedPlan = stitched): LegCache {
        val c = LegCache()
        for (i in 0 until plan.points.size - 1) c.put(plan.points[i], plan.points[i + 1], act, sac, cutLeg(plan.points[i], plan.points[i + 1]), cut = true)
        return c
    }

    // ---- PlanAnchors.exact ----

    @Test fun exactIsWhatAPlanCodeHandsBack() {
        val raw = listOf(GeoPoint(47.123456789, 11.987654321), GeoPoint(47.2000004, 11.5000005), GeoPoint(47.3333333333, 11.9999995),
            GeoPoint(47.4, 12.0000004), GeoPoint(47.5, 12.1))
        val withAnchors = mapOf(2 to Anchors(listOf(GeoPoint(47.31, 11.98)), listOf(GeoPoint(47.35, 12.0))))
        val back = PlanCode.decode(PlanCode.encode(SharedPlan(raw, Activity.HIKE, 3, anchors = withAnchors)))!!
        assertTrue("the plan carries anchors", back.anchors.isNotEmpty())
        assertEquals(raw.size, back.points.size)
        for (i in raw.indices) {
            val e = PlanAnchors.exact(raw[i])
            assertEquals(java.lang.Double.doubleToLongBits(back.points[i].lat), java.lang.Double.doubleToLongBits(e.lat))
            assertEquals(java.lang.Double.doubleToLongBits(back.points[i].lon), java.lang.Double.doubleToLongBits(e.lon))
        }
        assertTrue(PlanAnchors.isNormal(PlanAnchors.exact(raw[0])))
        assertFalse(PlanAnchors.isNormal(raw[0]))
    }

    // ---- settle ----

    @Test fun aPlanWithoutAnchorsRoutesNothingAndLeavesTheCache() {
        val e = Engine(); val c = LegCache()
        val plain = PlanAnchors.expand(visible, emptyMap(), emptySet())
        val planner = Planner(e, legs = c)
        planner.plan(plain, act, sac)
        val before = e.calls.size; val size = c.size
        val s = planner.settle(plain, act, sac)
        assertEquals(0, s.routed); assertEquals(0, s.failed); assertFalse(s.cancelled)
        assertEquals(before, e.calls.size); assertEquals(size, c.size)
        // no cache: nothing either
        val none = Planner(e, legs = null).settle(stitched, act, sac)
        assertEquals(0, none.routed); assertEquals(before, e.calls.size)
    }

    @Test fun afterSettlingAStitchedPlanTheLineEqualsAColdRun() {
        val e = Engine(); val c = cutCache()
        val exp = stitched
        val cutLine = Planner(e, legs = c).plan(exp, act, sac).points
        assertEquals(0, e.calls.size)
        val s = Planner(e, legs = c).settle(exp, act, sac)
        assertEquals(4, s.routed)
        e.calls.clear()
        val warm = Planner(e, legs = c).plan(exp, act, sac)
        assertEquals("the plan after settling routes nothing", 0, e.calls.size)
        val cold = Planner(Engine(), legs = LegCache()).plan(exp, act, sac)
        assertEquals(cold.points, warm.points)
        assertNotEquals(cutLine, warm.points)
    }

    @Test fun anExactLegBeatsACutLegInLookupAnchored() {
        val c = LegCache()
        val cut = cutLeg(v0, a); val plainLeg = cutLeg(v0, a).copy(distanceM = 1.0); val exact = cutLeg(v0, a).copy(distanceM = 2.0)
        c.put(v0, a, act, sac, plainLeg)
        c.put(v0, a, act, sac, cut, cut = true)
        assertSame(cut, c.lookupAnchored(v0, a, act, sac)!!.leg)
        c.put(v0, a, act, sac, exact, exact = true)
        assertSame(exact, c.lookupAnchored(v0, a, act, sac)!!.leg)
        assertTrue(c.isExact(v0, a, act, sac))
        // a plain lookup never reads the cut leg and an exact leg is a plain leg
        assertSame(exact, c.get(v0, a, act, sac))
    }

    @Test fun anExactCutLegIsAProgrammingError() {
        try { LegCache().put(v0, a, act, sac, cutLeg(v0, a), cut = true, exact = true); org.junit.Assert.fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun cutLegsAndAliasedLegsAreRoutedOnceAndASecondSettleRoutesNothing() {
        val e = Engine(); val c = cutCache()
        val p = Planner(e, legs = c)
        assertEquals(4, p.settle(stitched, act, sac).routed)
        assertEquals(0, p.settle(stitched, act, sac).routed)
        assertEquals(4, e.calls.size)
        // aliased: the plan was routed, then a visible point moved onto the way (alias keeps the legs under the new key)
        val e2 = Engine(); val c2 = LegCache()
        val old = stitched
        Planner(e2, legs = c2).plan(old, act, sac)
        val moved = PlanAnchors.expand(listOf(v0, GeoPoint(v1.lat + 0.00001, v1.lon), v2), anchors, emptySet())
        assertTrue(c2.aliasExpanded(old, moved, act, sac) > 0)
        e2.calls.clear()
        val p2 = Planner(e2, legs = c2)
        val s = p2.settle(moved, act, sac)
        assertEquals("the two legs at the moved point", 2, s.routed)
        assertEquals(2, e2.calls.size)
        assertEquals(0, p2.settle(moved, act, sac).routed)
    }

    @Test fun aliasExpandedDoesNotOverwriteAnExactLeg() {
        val e = Engine(); val c = LegCache()
        val old = stitched
        Planner(e, legs = c).plan(old, act, sac)
        val moved = PlanAnchors.expand(listOf(v0, GeoPoint(v1.lat + 0.00001, v1.lon), v2), anchors, emptySet())
        Planner(e, legs = c).settle(moved, act, sac)
        val before = (0 until moved.points.size - 1).map { c.get(moved.points[it], moved.points[it + 1], act, sac) }
        c.aliasExpanded(old, moved, act, sac)
        val after = (0 until moved.points.size - 1).map { c.get(moved.points[it], moved.points[it + 1], act, sac) }
        for (i in 1..2) assertSame("leg $i keeps its exact line", before[i], after[i])
        assertTrue(c.isExact(moved.points[1], moved.points[2], act, sac))
        // a plain alias copy is never exact
        val c3 = LegCache(); val leg = cutLeg(v0, a)
        c3.put(v0, a, act, sac, leg, exact = true)
        assertTrue(c3.alias(v0, a, v0.copy(lat = v0.lat + 0.001), a, act, sac))
        assertFalse(c3.isExact(v0.copy(lat = v0.lat + 0.001), a, act, sac))
    }

    @Test fun evictionOverwriteAndClearDropTheMark() {
        val c = LegCache(capacity = 2)
        val pts = (0..3).map { GeoPoint(50.0 + it, 8.0) }
        c.put(pts[0], pts[1], act, sac, cutLeg(pts[0], pts[1]), exact = true)
        c.put(pts[1], pts[2], act, sac, cutLeg(pts[1], pts[2]), exact = true)
        c.put(pts[2], pts[3], act, sac, cutLeg(pts[2], pts[3]), exact = true)
        assertFalse("evicted", c.isExact(pts[0], pts[1], act, sac))
        assertTrue(c.isExact(pts[2], pts[3], act, sac))
        c.put(pts[0], pts[1], act, sac, cutLeg(pts[0], pts[1]), exact = true) // back in, marked
        assertTrue(c.isExact(pts[0], pts[1], act, sac))
        c.put(pts[0], pts[1], act, sac, cutLeg(pts[0], pts[1])) // overwritten plain
        assertFalse(c.isExact(pts[0], pts[1], act, sac))
        c.put(pts[2], pts[3], act, sac, cutLeg(pts[2], pts[3]), exact = true)
        c.clear()
        assertFalse(c.isExact(pts[2], pts[3], act, sac))
        c.put(pts[2], pts[3], act, sac, cutLeg(pts[2], pts[3]))
        assertFalse(c.isExact(pts[2], pts[3], act, sac))
    }

    // ---- LegFiles ----

    private fun files() = LegFiles(tmp.root.resolve("legs"), { "fp1" }, { "1" })

    @Test fun anExactLegSurvivesARestartThroughLegFiles() {
        val first = LegCache(store = files())
        first.put(v0, a, act, sac, cutLeg(v0, a), exact = true)
        first.put(a, v1, act, sac, cutLeg(a, v1))
        val second = LegCache(store = files())
        assertTrue(second.isExact(v0, a, act, sac))
        assertEquals(true, LegCache(store = files()).lookupAnchored(v0, a, act, sac)!!.stored)
        assertFalse(LegCache(store = files()).isExact(a, v1, act, sac))
        // a plain write over an exact file clears the mark
        first.put(v0, a, act, sac, cutLeg(v0, a))
        assertFalse(LegCache(store = files()).isExact(v0, a, act, sac))
    }

    @Test fun theHeaderLineIsOnlyWrittenForExactLegsAndAnOldFileReadsAsNotExact() {
        val key = LegCache.key(v0, a, act, sac)
        val plain = LegFiles.Entry(key, "fp1", "1", 5L, cutLeg(v0, a))
        val plainText = LegFiles.encode(plain)
        assertFalse(plainText.contains("exact"))
        assertEquals(plainText, LegFiles.encode(plain.copy(exact = false)))
        val exactText = LegFiles.encode(plain.copy(exact = true))
        assertTrue(exactText.contains("\nexact\t1\npoints\t"))
        assertEquals(plainText, exactText.replace("exact\t1\n", ""))
        assertTrue(LegFiles.decode(exactText).exact)
        assertFalse(LegFiles.decode(plainText).exact)
        assertFalse(LegFiles.decode(exactText.replace("exact\t1\n", "")).exact)
    }

    // ---- requests ----

    @Test fun requestsUseTheNormalizedPoints() {
        val raw = listOf(GeoPoint(51.0000004, 7.0000004), GeoPoint(51.2000004, 7.0000004), GeoPoint(51.4000004, 7.0000004), GeoPoint(51.6000004, 7.0000004), GeoPoint(51.8000004, 7.0000004))
        val exp = PlanAnchors.expand(listOf(raw[0], raw[2], raw[4]), mapOf(1 to Anchors(listOf(raw[1]), listOf(raw[3]))), emptySet())
        val e = Engine(); val c = cutCache(exp)
        Planner(e, legs = c).settle(exp, act, sac)
        assertEquals(4, e.calls.size)
        for (i in 0..3) assertEquals(listOf(PlanAnchors.exact(raw[i]), PlanAnchors.exact(raw[i + 1])), e.calls[i])
        // and the plan on those points is read from the exact legs
        val n = e.calls.size
        Planner(e, legs = c).plan(PlanAnchors.expand(exp.visible.map { PlanAnchors.exact(it) }, mapOf(1 to Anchors(listOf(PlanAnchors.exact(raw[1])), listOf(PlanAnchors.exact(raw[3])))), emptySet()), act, sac)
        assertEquals(n, e.calls.size)
    }

    @Test fun aRoutedLegIsMarkedExactOnlyWhenBothPointsAreNormal() {
        val e = Engine(); val c = LegCache()
        val n1 = GeoPoint(51.0, 7.0); val n2 = GeoPoint(51.5, 7.5); val odd = GeoPoint(51.7000004, 7.5)
        Planner(e, legs = c).plan(listOf(n1, n2, odd), act, sac)
        assertTrue(c.isExact(n1, n2, act, sac))
        assertFalse(c.isExact(n2, odd, act, sac))
        assertNotNull(c.get(n2, odd, act, sac))
    }

    @Test fun aShortTripRetryUsesTheMidpointOfTheNormalizedPoints() {
        val s0 = GeoPoint(51.0000004, 7.0); val s1 = GeoPoint(51.0, 7.0060004) // ~420 m apart: the fake engine routes it as a long detour
        val exp = PlanAnchors.expand(listOf(GeoPoint(50.9, 7.0), s0, s1, GeoPoint(51.1, 7.1)), mapOf(1 to Anchors(listOf(GeoPoint(50.95, 7.0)), emptyList())), emptySet())
        val e = Engine(); val c = LegCache()
        val i = exp.points.indexOf(s0)
        c.put(exp.points[i], exp.points[i + 1], act, sac, cutLeg(exp.points[i], exp.points[i + 1]), cut = true)
        Planner(e, legs = c).settle(exp, act, sac)
        val ea = PlanAnchors.exact(s0); val eb = PlanAnchors.exact(s1)
        val retry = e.calls.single { it.size == 3 }
        assertEquals(listOf(ea, Planner.midpoint(ea, eb), eb), retry)
    }

    // ---- failures ----

    @Test fun aFailedLegKeepsItsLegIsReportedAndTheRestGoesOn() {
        val c = cutCache(); val exp = stitched
        val second = cutLeg(exp.points[1], exp.points[2])
        val e = Engine(throwing = { w -> if (w.first() == v1 && w.last() == b) RoutingException("No route found.") else null })
        val s = Planner(e, legs = c).settle(exp, act, sac)
        assertEquals(3, s.routed); assertEquals(1, s.failed); assertFalse(s.cancelled)
        assertEquals(4, e.calls.size)
        // the failed leg still reads its cut leg
        assertNotNull(c.lookupAnchored(v1, b, act, sac))
        assertFalse(c.isExact(v1, b, act, sac))
        assertTrue(Problems.snapshot().any { it.area == "routing" && it.level == Problems.Level.WARN })
        assertNotNull(second)
    }

    @Test fun aCancelStopsAndKeepsWhatIsDone() {
        val cancel = RouteCancel(); val e = Engine(); val c = cutCache()
        e.onCall = { cancel.cancel() }
        val s = Planner(e, cancel = cancel, legs = c).settle(stitched, act, sac)
        assertTrue(s.cancelled); assertEquals(1, e.calls.size)
        assertEquals(1, s.routed); assertEquals(0, s.failed)
        assertTrue(c.isExact(v0, a, act, sac))
        assertFalse(c.isExact(a, v1, act, sac))
    }

    @Test fun aTimeoutStopsTheRun() {
        val e = Engine(throwing = { RoutingException("took too long", timedOut = true) }); val c = cutCache()
        val s = Planner(e, legs = c).settle(stitched, act, sac)
        assertEquals(1, e.calls.size); assertEquals(1, s.failed); assertEquals(0, s.routed); assertFalse(s.cancelled)
    }

    @Test fun anyOtherExceptionIsAnErrorAndTheRunGoesOn() {
        val e = Engine(throwing = { w -> if (w.last() == a) IllegalStateException("boom") else null }); val c = cutCache()
        val s = Planner(e, legs = c).settle(stitched, act, sac)
        assertEquals(3, s.routed); assertEquals(1, s.failed)
        assertTrue(Problems.snapshot().any { it.level == Problems.Level.ERROR })
    }
}

package com.onpath.web

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** The app's plan-editing chain builds and runs over the planner's ports (WebPlanChain.probe, the same call the Wasm check makes). */
class WebPlanChainTest {
    @Test
    fun aCancelledEditIsUndone() {
        val out = WebPlanChain.probe()
        assertFalse(out, out.contains("\"error\""))
        assertTrue(out, out.contains("\"afterEdit\":3,\"points\":2,\"canRedo\":true"))
        assertTrue(out, out.contains("recalc"))
    }

    private val a = com.onpath.app.routing.GeoPoint(50.0, 7.0)
    private val b = com.onpath.app.routing.GeoPoint(50.1, 7.1)
    private val c = com.onpath.app.routing.GeoPoint(50.05, 7.2)

    /** The page's commit + Undo / Redo through the shared PlanEditState, on the worker's one history. */
    @Test
    fun recordUndoRedo() {
        val h = com.onpath.app.domain.PlanHistory()
        val chain = WebPlanChain(history = h)
        chain.record(listOf(a, b), listOf(a, c, b))
        assertTrue(h.canUndo)
        org.junit.Assert.assertEquals(listOf(a, b), chain.step(undo = true, now = listOf(a, c, b)))
        org.junit.Assert.assertEquals(listOf(a, c, b), chain.step(undo = false, now = listOf(a, b)))
        org.junit.Assert.assertNull(chain.step(undo = false, now = listOf(a, c, b))) // nothing to redo
    }

    /** Cancel takes back the change that started the run (PlanStatus.onCancel via PlanEditState.cancelRun). */
    @Test
    fun cancelTakesBackTheCause() {
        val h = com.onpath.app.domain.PlanHistory()
        val chain = WebPlanChain(history = h)
        chain.record(listOf(a, b), listOf(a, c, b))
        org.junit.Assert.assertEquals("UNDO" to listOf(a, b), chain.cancel("edit", listOf(a, c, b), null, 2))
        // the undo of that edit, cancelled: redone
        org.junit.Assert.assertEquals("REDO" to listOf(a, c, b), chain.cancel("undo", listOf(a, b), null, 2))
        org.junit.Assert.assertEquals("RESTORE_ACTIVITY" to null,
            chain.cancel("activity", listOf(a, c, b), com.onpath.app.routing.Activity.HIKE, 3))
        org.junit.Assert.assertEquals("KEEP_POINTS" to null, chain.cancel("other", listOf(a, c, b), null, 2))
    }

    /** A history restored in a fresh worker (historyImport) is what a new chain reads: Cancel can undo across it. */
    @Test
    fun aRestoredHistoryCanBeUndone() {
        val h = com.onpath.app.domain.PlanHistory()
        WebPlanChain(history = h).record(listOf(a, b), listOf(a, c, b))
        val (u, r) = h.snapshot()
        val h2 = com.onpath.app.domain.PlanHistory().apply { restore(u, r) }
        org.junit.Assert.assertEquals("UNDO" to listOf(a, b), WebPlanChain(history = h2).cancel("edit", listOf(a, c, b), null, 2))
    }

    // ---- the run's end feeds the app's PlanRecalcState.taken (stitch lines, snapped points) - writers part 1

    /** A router whose every leg bulges north at its middle: a routed line that is not the straight way. */
    private val bulging = object : com.onpath.app.routing.RoutingEngine {
        override fun route(waypoints: List<com.onpath.app.routing.GeoPoint>, activity: com.onpath.app.routing.Activity, sacLimit: Int) =
            route(waypoints, activity, sacLimit, com.onpath.app.routing.RouteLimits(30_000))
        override fun route(waypoints: List<com.onpath.app.routing.GeoPoint>, activity: com.onpath.app.routing.Activity, sacLimit: Int,
                           limits: com.onpath.app.routing.RouteLimits): com.onpath.app.routing.RouteResult {
            val pts = ArrayList<com.onpath.app.routing.GeoPoint>()
            for (i in 0 until waypoints.size - 1) {
                val x = waypoints[i]; val y = waypoints[i + 1]
                pts += line(x, y).dropLast(1)
            }
            pts += waypoints.last()
            val d = com.onpath.app.data.Geo.length(pts)
            return com.onpath.app.routing.RouteResult(pts, d, 0.0, 0.0, d / 1.2, matched = waypoints.toList())
        }
    }
    /** The leg's line: x up to a peak 0.05 degrees north of the middle and down to y, 40 points a side. */
    private fun line(x: com.onpath.app.routing.GeoPoint, y: com.onpath.app.routing.GeoPoint): List<com.onpath.app.routing.GeoPoint> {
        val peak = com.onpath.app.routing.GeoPoint((x.lat + y.lat) / 2 + 0.05, (x.lon + y.lon) / 2)
        fun side(p: com.onpath.app.routing.GeoPoint, q: com.onpath.app.routing.GeoPoint) =
            (0 until 40).map { com.onpath.app.routing.GeoPoint(p.lat + (q.lat - p.lat) * it / 40, p.lon + (q.lon - p.lon) * it / 40) }
        return side(x, peak) + side(peak, y) + y
    }
    /** A point 550 m off the line of the leg x-y, a quarter of the way: put in there it gets its own stitch. */
    private fun bulge(x: com.onpath.app.routing.GeoPoint, y: com.onpath.app.routing.GeoPoint) =
        line(x, y)[20].let { com.onpath.app.routing.GeoPoint(it.lat + 0.005, it.lon) }

    private fun routed(pts: List<com.onpath.app.routing.GeoPoint>): com.onpath.app.routing.PlanResult {
        val act = com.onpath.app.routing.Activity.HIKE
        val exp = com.onpath.app.routing.PlanAnchors.expand(pts, emptyMap(), emptySet())
        val planner = com.onpath.app.routing.Planner(bulging, com.onpath.app.routing.RouteCancel(), legs = com.onpath.app.routing.LegCache())
        return com.onpath.app.routing.PlanResult.plan(planner, exp, act, 2, emptySet(), WebPace.factors(act))
    }

    private fun run(chain: WebPlanChain, pts: List<com.onpath.app.routing.GeoPoint>): Int =
        (chain.head(pts, com.onpath.app.routing.Activity.HIKE, 2, false) as WebPlanChain.Head.Route).gen

    private val la = com.onpath.app.routing.GeoPoint(50.0, 7.0)
    private val lb = com.onpath.app.routing.GeoPoint(50.0, 8.5)
    private val hike = com.onpath.app.routing.Activity.HIKE

    /** A finished run is taken: its line is what the next edit stitches from - a point put on the routed leg keeps its detour. */
    @Test
    fun aTakenRunMakesTheNextEditStitch() {
        val pts = listOf(la, lb)
        val chain = WebPlanChain(pts)
        val gen = run(chain, pts)
        val res = routed(pts)
        chain.end(gen, "ok", 1) { res }
        chain.record(pts, listOf(la, bulge(la, lb), lb))
        assertTrue("the line was taken: the point on it brought detour points", chain.plan.hasDetours(hike, 2))
        assertTrue(chain.recalcRowShown(hike, 2))
        assertFalse("another profile has none", chain.recalcRowShown(hike, 3))
    }

    @Test
    fun withoutATakenRunThereIsNoStitchAndNoRow() {
        val pts = listOf(la, lb)
        val chain = WebPlanChain(pts)
        run(chain, pts) // never ended
        chain.record(pts, listOf(la, bulge(la, lb), lb))
        assertFalse(chain.plan.hasDetours(hike, 2))
        assertFalse(chain.recalcRowShown(hike, 2))
    }

    @Test
    fun aFailedRunTakesTheLinesAway() {
        val pts = listOf(la, lb)
        val chain = WebPlanChain(pts)
        chain.end(run(chain, pts), "ok", 1) { routed(pts) }
        chain.end(run(chain, pts), "failed", 1)
        chain.record(pts, listOf(la, bulge(la, lb), lb))
        assertFalse("no stitch from an older run", chain.plan.hasDetours(hike, 2))
    }

    /** The row's tap clears the detour points in one undo step and asks for a run; Undo brings them back. */
    @Test
    fun recalcFromScratchClearsAndUndoBringsThemBack() {
        val pts = listOf(la, lb)
        val chain = WebPlanChain(pts)
        chain.end(run(chain, pts), "ok", 1) { routed(pts) }
        val three = listOf(la, bulge(la, lb), lb)
        chain.record(pts, three)
        assertTrue(chain.recalcFromScratch(three, hike, 2))
        assertFalse(chain.plan.hasDetours(hike, 2))
        assertTrue("a run was asked for", "recalc" in chain.did)
        assertFalse("no row any more", chain.recalcRowShown(hike, 2))
        assertFalse("nothing to clear a second time", chain.recalcFromScratch(three, hike, 2))
        chain.step(undo = true, now = three)
        assertTrue(chain.plan.hasDetours(hike, 2))
    }

    /** The saved route's detour points ride in the store: a cleared saved route is unsaved again on a new document. */
    @Test
    fun savedAnchorsRoundTripThroughTheStore() {
        val pts = listOf(a, c, b)
        val store = WebPlanStore(pts)
        val set = com.onpath.app.routing.AnchorSet.of(mapOf(1 to com.onpath.app.routing.Anchors(listOf(a), listOf(b))), hike, 2)
        val doc = com.onpath.app.ui.plan.PlanDocument(store)
        doc.arriving(pts, set)
        doc.edit { clear(); addAll(pts) }
        doc.setSaved("Tour", 5L, pts)
        assertFalse(doc.unsaved)
        assertTrue(doc.clearDetours())
        assertTrue(doc.unsaved)
        assertTrue("written to the store", store.savedAnchorsJson != null)
        assertTrue("still unsaved after a reload", com.onpath.app.ui.plan.PlanDocument(store).unsaved)
    }

    // ---- the idle settling (PlanSettle) of a taken run with detour points - writers part 2

    /** A stitched plan [la] - n - [lb] whose run was taken (the line of a plan with detour points), ready to settle. */
    private fun takenWithAnchors(): Pair<WebPlanChain, List<com.onpath.app.routing.GeoPoint>> {
        val two = listOf(la, lb)
        val chain = WebPlanChain(two)
        chain.end(run(chain, two), "ok", 1) { routed(two) }
        val three = listOf(la, bulge(la, lb), lb)
        chain.record(two, three)
        assertTrue(chain.plan.hasDetours(hike, 2))
        val gen = run(chain, three)
        val exp = com.onpath.app.routing.PlanAnchors.expand(three, chain.plan.anchorsFor(hike, 2), emptySet())
        val planner = com.onpath.app.routing.Planner(bulging, com.onpath.app.routing.RouteCancel(), legs = com.onpath.app.routing.LegCache())
        val res = com.onpath.app.routing.PlanResult.plan(planner, exp, hike, 2, emptySet(), WebPace.factors(hike))
        chain.end(gen, "ok", 1) { res }
        return chain to three
    }

    /** A taken run with detour points settles: its legs routed on their own, the line shown again quietly from the cache. */
    @Test
    fun aTakenRunWithDetourPointsSettlesAndTakesTheQuietLine() {
        val (chain, _) = takenWithAnchors()
        val quiet = chain.settleNow(bulging)
        assertTrue("the settled line is taken", quiet != null)
        assertTrue("the plan keeps its detour points", chain.plan.hasDetours(hike, 2))
        assertTrue("it is asked for once", chain.settleNow(bulging) == null)
    }

    /** No detour points, nothing to settle. */
    @Test
    fun aPlainRunHasNothingToSettle() {
        val two = listOf(la, lb)
        val chain = WebPlanChain(two)
        chain.end(run(chain, two), "ok", 1) { routed(two) }
        assertTrue(chain.settleNow(bulging) == null)
    }

    /** Every edit (begin) stops the settling: the work is gone. */
    @Test
    fun anEditStopsTheSettling() {
        val (chain, three) = takenWithAnchors()
        run(chain, three) // a new run begins (an edit, Undo, activity switch)
        assertTrue(chain.settleNow(bulging) == null)
    }

    /** A request that routes nothing the engine can answer (a leg missing) shows no worse line: the taken line stays. */
    @Test
    fun aLegTheEngineCannotRouteLeavesTheLine() {
        val (chain, _) = takenWithAnchors()
        val refusing = object : com.onpath.app.routing.RoutingEngine {
            override fun route(waypoints: List<com.onpath.app.routing.GeoPoint>, activity: com.onpath.app.routing.Activity, sacLimit: Int) =
                throw com.onpath.app.routing.RoutingException("no route")
            override fun route(waypoints: List<com.onpath.app.routing.GeoPoint>, activity: com.onpath.app.routing.Activity, sacLimit: Int,
                               limits: com.onpath.app.routing.RouteLimits) = route(waypoints, activity, sacLimit)
        }
        assertTrue(chain.settleNow(refusing) == null)
    }

    /** A plan with POI-pinned points: the run carries the page's pins, so it stitches and settles like any other. */
    @Test
    fun aPlanWithPinnedPointsStitchesAndSettles() {
        val two = listOf(la, lb)
        val chain = WebPlanChain(two)
        fun routedPinned(pts: List<com.onpath.app.routing.GeoPoint>, pins: Set<Int>): com.onpath.app.routing.PlanResult {
            val exp = com.onpath.app.routing.PlanAnchors.expand(pts, chain.plan.anchorsFor(hike, 2), pins)
            val planner = com.onpath.app.routing.Planner(bulging, com.onpath.app.routing.RouteCancel(), legs = com.onpath.app.routing.LegCache())
            return com.onpath.app.routing.PlanResult.plan(planner, exp, hike, 2, emptySet(), WebPace.factors(hike))
        }
        val g1 = (chain.head(two, hike, 2, false, setOf(0, 1)) as WebPlanChain.Head.Route).gen
        org.junit.Assert.assertEquals("the run has the page's pins", setOf(0, 1), chain.plan.pinnedIndices())
        chain.end(g1, "ok", 1) { routedPinned(two, setOf(0, 1)) }
        val three = listOf(la, bulge(la, lb), lb)
        chain.record(two, three)
        assertTrue("the pinned plan stitched", chain.plan.hasDetours(hike, 2))
        val g2 = (chain.head(three, hike, 2, false, setOf(0, 2)) as WebPlanChain.Head.Route).gen
        org.junit.Assert.assertEquals(setOf(0, 2), chain.plan.pinnedIndices().intersect(setOf(0, 2)))
        chain.end(g2, "ok", 1) { routedPinned(three, setOf(0, 2)) }
        assertTrue("and settles", chain.settleNow(bulging) != null)
    }
}

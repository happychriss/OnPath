package com.onpath.app.routing

import com.onpath.app.data.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Piece 2b of the detour stitch (docs/design/detour-stitch.md §8): the leg cache and the line over an [ExpandedPlan] –
 * [LegSplit.seed] cuts a stitch's chain out of a known longer leg, [LegCache.aliasExpanded], [StoredLine] on an expanded
 * plan, and [PlanAnchors.followInsert] (an insert between a point and its anchors, by line position).
 */
class DetourStitchPieceTwoBTest {
    private val act = Activity.BIKE_TOURING
    private val sac = 0

    /** Records every request; the line between two points is straight, a point every 0.001° (~110 m), ends exact. */
    private class Engine : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            val line = ArrayList<GeoPoint>()
            for (i in 0 until waypoints.size - 1) {
                val a = waypoints[i]; val b = waypoints[i + 1]
                val n = maxOf(2, Math.round(maxOf(Math.abs(b.lat - a.lat), Math.abs(b.lon - a.lon)) / 0.001).toInt())
                for (k in (if (i == 0) 0 else 1)..n) line += if (k == n) GeoPoint(b.lat, b.lon) else
                    GeoPoint(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n)
            }
            val d = Geo.length(line)
            return RouteResult(line, d, 10.0, 20.0, d / 1.2, matched = listOf(line.first(), line.last()))
        }
    }

    private val prev = GeoPoint(51.0, 7.0)
    private val next = GeoPoint(52.0, 7.0)

    /** The old leg prev → next in the cache; its line points are what the anchors are taken from. */
    private class Old(val e: Engine = Engine(), val cache: LegCache = LegCache()) {
        val leg: RouteResult
        init { Planner(e, legs = cache).plan(listOf(GeoPoint(51.0, 7.0), GeoPoint(52.0, 7.0)), Activity.BIKE_TOURING, 0); leg = cache.get(GeoPoint(51.0, 7.0), GeoPoint(52.0, 7.0), Activity.BIKE_TOURING, 0)!!; e.calls.clear() }
        fun at(i: Int) = GeoPoint(leg.points[i].lat, leg.points[i].lon)
    }

    private fun expand(visible: List<GeoPoint>, anchors: Map<Int, Anchors>) = PlanAnchors.expand(visible, anchors, emptySet())

    private fun known(c: LegCache, plan: ExpandedPlan, i: Int) = c.lookupLeg(plan, i, act, sac)?.leg

    // ---- LegSplit.seed over an ExpandedPlan ----

    @Test fun theChainOfAStitchIsCutFromTheOldLegUnderTheCutKey() {
        val old = Old(); val a = old.at(200); val b = old.at(300); val p = GeoPoint(51.25, 7.05)
        val plan = expand(listOf(prev, p, next), mapOf(1 to Anchors(listOf(a), listOf(b))))
        assertEquals(2, LegSplit.seed(old.cache, plan, act, sac))
        assertNotNull(old.cache.inMemory(prev, a, act, sac, cut = true))
        assertNotNull(old.cache.inMemory(b, next, act, sac, cut = true))
        // nothing under a plain key: a plain plan [prev, a] does not see them
        assertNull(old.cache.get(prev, a, act, sac))
        assertNull(old.cache.get(b, next, act, sac))
        assertNull(old.cache.lookup(prev, a, act, sac))
        assertEquals(old.leg.points.take(201), known(old.cache, plan, 0)!!.points)
        assertEquals(old.leg.points.drop(300), known(old.cache, plan, 3)!!.points)
        // the detour itself is not cut
        assertNull(known(old.cache, plan, 1)); assertNull(known(old.cache, plan, 2))
    }

    @Test fun aPointOnTheLineCutsAllFourLegsAndTheNumbersAddUp() {
        val old = Old(); val a = old.at(200); val b = old.at(300); val p = old.at(250)
        val plan = expand(listOf(prev, p, next), mapOf(1 to Anchors(listOf(a), listOf(b))))
        assertEquals(4, LegSplit.seed(old.cache, plan, act, sac))
        val legs = (0..3).map { known(old.cache, plan, it)!! }
        assertEquals(old.leg.distanceM, legs.sumOf { it.distanceM }, 1e-6)
        assertEquals(old.leg.ascentM, legs.sumOf { it.ascentM }, 1e-6)
        assertEquals(old.leg.descentM, legs.sumOf { it.descentM }, 1e-6)
        assertEquals(old.leg.durationS, legs.sumOf { it.durationS }, 1e-6)
        assertEquals(old.leg.points.size + 3, legs.sumOf { it.points.size })
    }

    @Test fun aSecondStitchComposesFromTheCutLeg() {
        val old = Old(); val e = old.e
        val a1 = old.at(200); val b1 = old.at(300); val p1 = GeoPoint(51.25, 7.05)
        val first = expand(listOf(prev, p1, next), mapOf(1 to Anchors(listOf(a1), listOf(b1))))
        LegSplit.seed(old.cache, first, act, sac)
        Planner(e, legs = old.cache).plan(first, act, sac)
        assertEquals(listOf(listOf(a1, p1), listOf(p1, b1)), e.calls)
        e.calls.clear()
        val a2 = old.at(500); val b2 = old.at(600); val p2 = GeoPoint(51.55, 6.95)
        val second = expand(listOf(prev, p1, p2, next), mapOf(1 to Anchors(listOf(a1), listOf(b1)), 2 to Anchors(listOf(a2), listOf(b2))))
        assertEquals(listOf(prev, a1, p1, b1, a2, p2, b2, next), second.points)
        assertEquals(2, LegSplit.seed(old.cache, second, act, sac))
        assertNotNull(old.cache.inMemory(b1, a2, act, sac, cut = true))
        assertNotNull(old.cache.inMemory(b2, next, act, sac, cut = true))
        Planner(e, legs = old.cache).plan(second, act, sac)
        assertEquals(listOf(listOf(a2, p2), listOf(p2, b2)), e.calls)
    }

    @Test fun aPointTheLinePassesTwiceIsNotCut() {
        // x is on the line twice (a loop): never guess which pass – the piece around it is routed
        val x = GeoPoint(51.5, 7.0)
        val line = listOf(prev, GeoPoint(51.2, 7.0), x, GeoPoint(51.6, 7.1), GeoPoint(51.7, 7.1), x, GeoPoint(51.8, 7.0), next)
        val cache = LegCache(); cache.put(prev, next, act, sac, RouteResult(line, Geo.length(line), 0.0, 0.0, 100.0))
        val p = GeoPoint(51.3, 7.2)
        val plan = expand(listOf(prev, p, next), mapOf(1 to Anchors(emptyList(), listOf(x))))
        assertEquals(listOf(prev, p, x, next), plan.points)
        assertEquals(0, LegSplit.seed(cache, plan, act, sac))
        assertNull(cache.inMemory(x, next, act, sac, cut = true))
        // the same x once: cut
        val once = line.take(3) + line.drop(6)
        val c2 = LegCache(); c2.put(prev, next, act, sac, RouteResult(once, Geo.length(once), 0.0, 0.0, 100.0))
        assertEquals(1, LegSplit.seed(c2, plan, act, sac))
        assertNotNull(c2.inMemory(x, next, act, sac, cut = true))
    }

    @Test fun anAnchorOutOfOrderIsNotCut() {
        val old = Old(); val a = old.at(300); val b = old.at(200); val p = GeoPoint(51.25, 7.05)
        // a is later on the line than b, but listed first: the second one counts as off the line
        val plan = expand(listOf(prev, p, next), mapOf(1 to Anchors(listOf(a), listOf(b))))
        assertEquals(1, LegSplit.seed(old.cache, plan, act, sac))
        assertNotNull(old.cache.inMemory(prev, a, act, sac, cut = true))
        assertNull(old.cache.inMemory(b, next, act, sac, cut = true))
    }

    @Test fun aVisiblePieceOfACutParentIsNotStored() {
        val old = Old(); val e = old.e
        val p0 = GeoPoint(51.0, 7.0); val v2 = GeoPoint(51.7, 7.0)
        val p = GeoPoint(51.1, 7.05); val b = old.at(300); val v1 = old.at(500)
        // [p0, p, b, v1, v2]: the cut leg b → v2 holds v1 on its line; b → v1 is cut, v1 → v2 (two visible ends) is not
        val plan = expand(listOf(p0, p, v1, v2), mapOf(1 to Anchors(emptyList(), listOf(b))))
        assertEquals(listOf(p0, p, b, v1, v2), plan.points)
        old.cache.put(b, v2, act, sac, e.route(listOf(b, v2), act, sac), cut = true)
        assertEquals(1, LegSplit.seed(old.cache, plan, act, sac))
        assertNotNull(old.cache.inMemory(b, v1, act, sac, cut = true))
        assertNull(old.cache.lookup(v1, v2, act, sac))
        assertNull(old.cache.inMemory(v1, v2, act, sac, cut = true))
        // from a plain parent that touches an anchor it is no better: a visible-visible piece only in the old seed's case (§8.9)
        val plain = LegCache(); plain.put(b, v2, act, sac, e.route(listOf(b, v2), act, sac))
        assertEquals(1, LegSplit.seed(plain, plan, act, sac))
        assertNull(plain.inMemory(v1, v2, act, sac))
    }

    @Test fun parentsAreReadFromMemoryOnly() {
        val store = object : LegStore {
            val legs = HashMap<LegCache.Key, RouteResult>()
            override fun get(key: LegCache.Key) = legs[key]?.let { LegStore.Stored(it, fresh = true) }
            override fun put(key: LegCache.Key, from: GeoPoint, to: GeoPoint, leg: RouteResult) { legs[key] = leg }
        }
        val first = LegCache(store = store)
        Planner(Engine(), legs = first).plan(listOf(prev, next), act, sac)
        val a = first.get(prev, next, act, sac)!!.points[200]
        val plan = expand(listOf(prev, GeoPoint(51.25, 7.05), next), mapOf(1 to Anchors(listOf(GeoPoint(a.lat, a.lon)), emptyList())))
        val restarted = LegCache(store = store)
        assertEquals(0, LegSplit.seed(restarted, plan, act, sac))
        assertEquals(0, restarted.size)
    }

    @Test fun aPlanWithoutAnchorsSeedsAsTheOldSeed() {
        val e = Engine()
        fun run(expanded: Boolean): Pair<Int, LegCache> {
            val c = LegCache()
            Planner(e, legs = c).plan(listOf(prev, next), act, sac)
            val spot = c.get(prev, next, act, sac)!!.points[400]
            val pts = listOf(prev, spot, next)
            val n = if (expanded) LegSplit.seed(c, expand(pts, emptyMap()), act, sac) else LegSplit.seed(c, pts, act, sac)
            return n to c
        }
        val (n1, c1) = run(false); val (n2, c2) = run(true)
        assertEquals(1, n1); assertEquals(n1, n2)
        assertEquals(c1.size, c2.size)
        val spot = c1.get(prev, next, act, sac)!!.points[400]
        assertEquals(c1.get(prev, spot, act, sac), c2.get(prev, spot, act, sac))
        assertEquals(c1.get(spot, next, act, sac), c2.get(spot, next, act, sac))
    }

    // ---- LegCache.aliasExpanded ----

    @Test fun aliasKeepsTheKindAndCarriesTheFullLeg() {
        val old = Old(); val a = old.at(200); val b = old.at(300); val p = GeoPoint(51.25, 7.05)
        val oldPlan = expand(listOf(prev, p, next), mapOf(1 to Anchors(listOf(a), listOf(b))))
        val c = old.cache
        LegSplit.seed(c, oldPlan, act, sac) // prev → a, b → next: cut legs
        Planner(old.e, legs = c).plan(oldPlan, act, sac) // a → p, p → b: routed, plain
        c.put(prev, p, act, sac, old.e.route(listOf(prev, p), act, sac)) // the full leg of visible 0 → 1
        val p2 = GeoPoint(51.25, 7.0501) // P snapped a little
        val newPlan = expand(listOf(prev, p2, next), mapOf(1 to Anchors(listOf(a), listOf(b))))
        // changed ends: a → p, p → b (the full prev → p too), nothing else
        assertEquals(3, c.aliasExpanded(oldPlan, newPlan, act, sac))
        assertNotNull(c.inMemory(a, p2, act, sac)); assertNull(c.inMemory(a, p2, act, sac, cut = true))
        assertNotNull(c.inMemory(p2, b, act, sac))
        assertNotNull(c.inMemory(prev, p2, act, sac))
        // a cut leg stays cut
        val moved = GeoPoint(a.lat, a.lon + 1e-5)
        val cutPlan = expand(listOf(prev, p, next), mapOf(1 to Anchors(listOf(moved), listOf(b))))
        assertEquals(2, c.aliasExpanded(oldPlan, cutPlan, act, sac)) // prev → a (cut) and a → p (plain)
        assertNotNull(c.inMemory(prev, moved, act, sac, cut = true)); assertNull(c.inMemory(prev, moved, act, sac))
        assertNotNull(c.inMemory(moved, p, act, sac)); assertNull(c.inMemory(moved, p, act, sac, cut = true))
    }

    @Test fun aliasOnAnotherLayoutCopiesNothing() {
        val old = Old(); val a = old.at(200); val b = old.at(300); val p = GeoPoint(51.25, 7.05)
        val withBoth = expand(listOf(prev, p, next), mapOf(1 to Anchors(listOf(a), listOf(b))))
        val withOne = expand(listOf(prev, GeoPoint(51.25, 7.0501), next), mapOf(1 to Anchors(listOf(a), emptyList())))
        LegSplit.seed(old.cache, withBoth, act, sac)
        assertEquals(0, old.cache.aliasExpanded(withBoth, withOne, act, sac))
        val other = expand(listOf(prev, p, next), mapOf(1 to Anchors(emptyList(), listOf(a), ), 2 to Anchors(listOf(b), emptyList())))
        assertEquals(0, old.cache.aliasExpanded(withBoth, other, act, sac))
    }

    // ---- StoredLine over an ExpandedPlan ----

    private class Shelf : LegStore {
        val legs = HashMap<LegCache.Key, RouteResult>(); val stale = HashSet<LegCache.Key>()
        override fun get(key: LegCache.Key) = legs[key]?.let { LegStore.Stored(it, fresh = key !in stale) }
        override fun put(key: LegCache.Key, from: GeoPoint, to: GeoPoint, leg: RouteResult) { legs[key] = leg }
    }

    private val stitched: ExpandedPlan
        get() = expand(listOf(prev, GeoPoint(51.25, 7.05), next), mapOf(1 to Anchors(listOf(GeoPoint(51.2, 7.0)), listOf(GeoPoint(51.3, 7.0)))))

    @Test fun allFreshLooksAtTheCutKeyWhereALegTouchesAnAnchor() {
        val plan = stitched
        val c = LegCache()
        assertFalse(StoredLine.allFresh(plan, act, sac, c))
        for (i in 0..3) {
            val leg = Engine().route(listOf(plan.points[i], plan.points[i + 1]), act, sac)
            c.put(plan.points[i], plan.points[i + 1], act, sac, leg, cut = plan.touchesAnchor(i))
        }
        assertTrue(StoredLine.allFresh(plan, act, sac, c))
        assertNull(StoredLine.preview(plan, act, sac, c, emptyList()))
        // a plain plan of the visible points does not see the cut legs
        assertFalse(StoredLine.allFresh(expand(plan.visible, emptyMap()), act, sac, c))
    }

    @Test fun aStaleLegIsTheStandInAndCountsAsStale() {
        val plan = stitched; val shelf = Shelf(); val c = LegCache(store = shelf)
        for (i in 0..3) {
            val leg = Engine().route(listOf(plan.points[i], plan.points[i + 1]), act, sac)
            shelf.put(LegCache.key(plan.points[i], plan.points[i + 1], act, sac, plan.touchesAnchor(i)), plan.points[i], plan.points[i + 1], leg)
        }
        shelf.stale += LegCache.key(plan.points[0], plan.points[1], act, sac, cut = true) // the cut leg is stale
        val pre = StoredLine.preview(plan, act, sac, c, emptyList())!!
        assertEquals(1, pre.stale); assertEquals(0, pre.fromLine)
        assertEquals(4, pre.route.legs.size) // legs of the expanded list
        assertEquals(51.0, pre.route.points.first().lat, 0.0); assertEquals(52.0, pre.route.points.last().lat, 0.0)
    }

    @Test fun legsWithoutAStoredLegComeFromTheLine() {
        val plan = stitched
        val line = Planner(Engine(), legs = LegCache()).plan(plan, act, sac).points
        val pre = StoredLine.preview(plan, act, sac, LegCache(), line)!!
        assertEquals(0, pre.stale); assertEquals(4, pre.fromLine)
        assertEquals(4, pre.route.legs.size)
        // no line, no leg: no stand-in
        assertNull(StoredLine.preview(plan, act, sac, LegCache(), emptyList()))
    }

    // ---- PlanAnchors.followInsert ----

    private val p0 = GeoPoint(51.0, 7.0)
    private val p1 = GeoPoint(52.0, 7.0)
    private val line: List<GeoPoint> get() = Engine().route(listOf(p0, p1), act, sac).points
    private fun on(lat: Double) = line.minByOrNull { Math.abs(it.lat - lat) }!!.let { GeoPoint(it.lat, it.lon) }

    private fun insert(n: GeoPoint, anchors: Map<Int, Anchors>, l: List<GeoPoint> = line, at: Int = 1): Map<Int, Anchors> =
        PlanAnchors.followInsert(listOf(p0, p1), listOf(p0, n, p1), anchors, at, l)

    private val a1 get() = on(51.2)
    private val a2 get() = on(51.3)
    private val b1 get() = on(51.6)
    private val both get() = mapOf(0 to Anchors(emptyList(), listOf(a1, a2)), 1 to Anchors(listOf(b1), emptyList()))

    @Test fun nBeforeAllAnchorsTakesTheAnchorsOfItsLeftSideAsItsAfter() {
        val r = insert(on(51.1), both)
        assertEquals(mapOf(1 to Anchors(emptyList(), listOf(a1, a2)), 2 to Anchors(listOf(b1), emptyList())), r)
    }

    @Test fun nBetweenTwoAnchorsOfTheLeftSplitsThem() {
        val r = insert(on(51.25), both)
        assertEquals(mapOf(0 to Anchors(emptyList(), listOf(a1)), 1 to Anchors(emptyList(), listOf(a2)),
            2 to Anchors(listOf(b1), emptyList())), r)
    }

    @Test fun nBetweenTheLastAnchorAndTheRightOnesKeepsBothSides() {
        val r = insert(on(51.45), both)
        assertEquals(mapOf(0 to Anchors(emptyList(), listOf(a1, a2)), 2 to Anchors(listOf(b1), emptyList())), r)
    }

    @Test fun nBetweenARightAnchorAndItsPointTakesItAsItsBefore() {
        val r = insert(on(51.8), both)
        assertEquals(mapOf(0 to Anchors(emptyList(), listOf(a1, a2)), 1 to Anchors(listOf(b1), emptyList())), r)
    }

    @Test fun nBetweenAnAnchorOfEachSide() {
        val a = mapOf(0 to Anchors(emptyList(), listOf(a1, a2, on(51.4))), 1 to Anchors(listOf(on(51.5), b1), emptyList()))
        val r = insert(on(51.45), a) // between 51.4 and 51.5
        assertEquals(Anchors(emptyList(), listOf(a1, a2, on(51.4))), r[0])
        assertNull(r[1])
        assertEquals(Anchors(listOf(on(51.5), b1), emptyList()), r[2])
        val s = insert(on(51.25), a) // between a1 and a2: both lists split
        assertEquals(Anchors(emptyList(), listOf(a1)), s[0])
        assertEquals(Anchors(emptyList(), listOf(a2, on(51.4))), s[1])
        assertEquals(Anchors(listOf(on(51.5), b1), emptyList()), s[2])
        val t = insert(on(51.55), a) // between 51.5 and b1: the right list splits
        assertEquals(Anchors(emptyList(), listOf(a1, a2, on(51.4))), t[0])
        assertEquals(Anchors(listOf(on(51.5)), emptyList()), t[1])
        assertEquals(Anchors(listOf(b1), emptyList()), t[2])
    }

    @Test fun anOffLineNGetsItsOwnStitchAndTheOldAnchorsBesideItGiveWay() {
        val n = GeoPoint(on(51.25).lat, 7.01)
        val own = PlanAnchors.forInsert(line, p0, p1, n)!!
        val r = insert(n, both)
        // a1 (51.2) lies behind N's before-anchor (~51.16) side of the stitch, a2 (51.3) before its after-anchor (~51.34): both go;
        // b1 (51.6) lies behind the after-anchor and stays
        assertNull(r[0])
        assertEquals(Anchors(own.before, own.after), r[1])
        assertEquals(Anchors(listOf(b1), emptyList()), r[2])
    }

    @Test fun anAnchorNotFoundOnTheLineFallsBackToFollow() {
        val off = mapOf(0 to Anchors(emptyList(), listOf(a1, GeoPoint(51.3, 7.01))), 1 to Anchors(listOf(b1), emptyList()))
        val n = on(51.25)
        assertEquals(PlanAnchors.follow(listOf(p0, p1), listOf(p0, n, p1), off), insert(n, off))
        // out of order is the same
        val swapped = mapOf(0 to Anchors(emptyList(), listOf(a2, a1)))
        assertEquals(PlanAnchors.follow(listOf(p0, p1), listOf(p0, n, p1), swapped), insert(n, swapped))
    }

    @Test fun moreThanEightOnASideFallsBackToFollow() {
        val many = (1..9).map { on(51.0 + it * 0.02) }
        val a = mapOf(0 to Anchors(emptyList(), many))
        val n = on(51.5)
        assertEquals(PlanAnchors.follow(listOf(p0, p1), listOf(p0, n, p1), a), insert(n, a))
    }

    @Test fun aWrongShapeOrRangeFallsBackToFollow() {
        val n = on(51.25)
        val after = listOf(p0, n, GeoPoint(53.0, 7.0))
        assertEquals(PlanAnchors.follow(listOf(p0, p1), after, both), PlanAnchors.followInsert(listOf(p0, p1), after, both, 1, line))
        for (at in listOf(0, 2, 5)) assertEquals(PlanAnchors.follow(listOf(p0, p1), listOf(p0, n, p1), both),
            insert(n, both, at = at))
    }

    @Test fun theOuterSidesStayAsPlainFollowGivesThem() {
        val q0 = GeoPoint(50.9, 7.0); val q3 = GeoPoint(52.1, 7.0); val o1 = GeoPoint(50.95, 7.0); val o2 = GeoPoint(52.05, 7.0)
        val before = listOf(q0, p0, p1, q3)
        val anchors = mapOf(1 to Anchors(listOf(o1), listOf(a1)), 2 to Anchors(listOf(b1), listOf(o2)))
        val n = on(51.4)
        val after = listOf(q0, p0, n, p1, q3)
        val r = PlanAnchors.followInsert(before, after, anchors, 2, line)
        val plain = PlanAnchors.follow(before, after, anchors)
        assertEquals(plain[1]!!.before, r[1]!!.before); assertEquals(listOf(o1), r[1]!!.before)
        assertEquals(plain[3]!!.after, r[3]!!.after); assertEquals(listOf(o2), r[3]!!.after)
        assertEquals(listOf(a1), r[1]!!.after)
        assertEquals(listOf(b1), r[3]!!.before)
        assertNull(r[2])
    }

    @Test fun anOffLineNOnALegWithoutAnchorsGetsItsStitchAndAnOnLineNGetsNone() {
        val n = GeoPoint(on(51.5).lat, 7.01)
        val own = PlanAnchors.forInsert(line, p0, p1, n)!!
        assertEquals(mapOf(1 to own), insert(n, emptyMap()))
        assertEquals(emptyMap<Int, Anchors>(), insert(on(51.5), emptyMap()))
        // too far off the line: no stitch, nothing kept
        assertEquals(emptyMap<Int, Anchors>(), insert(GeoPoint(51.5, 7.5), both))
    }
}

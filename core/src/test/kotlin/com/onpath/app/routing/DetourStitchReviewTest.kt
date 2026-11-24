package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.strings.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Findings of the piece 2b review (docs/design/detour-stitch.md §8): each test asserts the correct behaviour. The fuzz runs
 * warm (seed, cache) against cold (empty cache) on a road grid where a piece of a best route is the best route.
 */
class DetourStitchReviewTest {
    private val act = Activity.BIKE_TOURING
    private val sac = 0
    private fun g(p: GeoPoint) = GeoPoint(p.lat, p.lon)

    private fun straight(a: GeoPoint, b: GeoPoint): List<GeoPoint> {
        val n = maxOf(2, Math.round(maxOf(Math.abs(b.lat - a.lat), Math.abs(b.lon - a.lon)) / 0.001).toInt())
        return (0..n).map { k -> if (k == n) g(b) else GeoPoint(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n) }
    }

    private fun path(vararg c: GeoPoint): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        for (i in 0 until c.size - 1) { val s = straight(c[i], c[i + 1]); out += if (i == 0) s else s.drop(1) }
        return out
    }

    private fun res(l: List<GeoPoint>): RouteResult { val d = Geo.length(l); return RouteResult(l, d, 10.0, 10.0, d / 5.0, matched = listOf(l.first(), l.last())) }

    /** Sub-path consistent by construction: two points on [base] in order = exactly the piece of [base]. */
    private class Base(val base: List<GeoPoint>, val mk: (List<GeoPoint>) -> RouteResult, val line: (GeoPoint, GeoPoint) -> List<GeoPoint>) : RoutingEngine {
        val calls = ArrayList<List<GeoPoint>>()
        private fun idx(p: GeoPoint) = base.indexOfFirst { it.lat == p.lat && it.lon == p.lon }
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            if (waypoints.size == 2) {
                val i = idx(waypoints[0]); val j = idx(waypoints[1])
                if (i >= 0 && j > i) return mk(base.subList(i, j + 1).toList())
                return mk(line(waypoints[0], waypoints[1]))
            }
            val out = ArrayList<GeoPoint>()
            for (k in 0 until waypoints.size - 1) { val s = line(waypoints[k], waypoints[k + 1]); out += if (k == 0) s else s.drop(1) }
            val r = mk(out)
            return r.copy(matched = waypoints)
        }
    }

    // 1. the Planner's short-trip retry is applied on a cold device to prev → a, never to the warm cut
    @Test fun aShortTripDetourPieceIsRoutedNotCut() {
        val prev = GeoPoint(51.0, 7.0); val next = GeoPoint(52.0, 7.2)
        val base = path(prev, GeoPoint(51.03, 7.0), GeoPoint(51.03, 7.0143), GeoPoint(51.0, 7.0143), GeoPoint(51.0, 7.2), next)
        val e = Base(base, ::res, ::straight)
        val p = GeoPoint(51.003, 7.157)
        val anchors = PlanAnchors.forInsert(base, prev, next, p)!!
        val a = anchors.before.single()
        println("P1 anchor a=$a straight prev-a=${Geo.distance(prev, a).toInt()} m, along=${Geo.length(base.subList(0, base.indexOfFirst { it.lat == a.lat && it.lon == a.lon } + 1)).toInt()} m")
        val plan = PlanAnchors.expand(listOf(prev, p, next), mapOf(1 to anchors), emptySet())
        val warmCache = LegCache()
        Planner(e, legs = warmCache).plan(listOf(prev, next), act, sac)
        LegSplit.seed(warmCache, plan, act, sac)
        val warm = Planner(e, legs = warmCache).plan(plan, act, sac)
        val cold = Planner(Base(base, ::res, ::straight), legs = LegCache()).plan(plan, act, sac)
        println("P1 warm ${warm.points.size} pts ${warm.distanceM.toInt()} m; cold ${cold.points.size} pts ${cold.distanceM.toInt()} m")
        assertEquals(warm.points.map { it.lat to it.lon }, cold.points.map { it.lat to it.lon })
    }

    // 2. a plain plan must not see a piece cut for a plan with anchors (§8.9)
    @Test fun aPlainPlanSeesNoPieceCutForAnAnchoredPlan() {
        val a = GeoPoint(51.0, 7.0); val b = GeoPoint(52.0, 7.0)
        val line = straight(a, b); val v = g(line[300]); val w = g(line[600])
        val c = LegCache(); c.put(a, b, act, sac, res(line))
        val q = GeoPoint(52.2, 7.0); val x = GeoPoint(52.1, 7.05)
        val anchored = PlanAnchors.expand(listOf(a, v, w, b, q), mapOf(4 to Anchors(listOf(x), emptyList())), emptySet())
        LegSplit.seed(c, anchored, act, sac)
        // the same cache, a plain plan of the first four points (no anchors at all)
        println("P2 plain V→W known after the anchored seed: ${c.get(v, w, act, sac) != null}")
        val clean = LegCache(); clean.put(a, b, act, sac, res(line))
        LegSplit.seed(clean, listOf(a, v, w, b), act, sac)
        println("P2 plain V→W known after the plain seed of the same points: ${clean.get(v, w, act, sac) != null}")
        assertNull("a leg with no anchor at an end must not be a cut", c.get(v, w, act, sac))
    }

    // 3. N far off the line must not be tied to anchors around its projection
    @Test fun aFarOffNKeepsNoAnchorsBesideItsProjection() {
        val p0 = GeoPoint(51.0, 7.0); val p1 = GeoPoint(52.0, 7.0)
        val line = straight(p0, p1)
        val a = g(line[400]); val b = g(line[600]); val n = GeoPoint(51.5, 7.5) // ~35 km east of the line
        val r = PlanAnchors.followInsert(listOf(p0, p1), listOf(p0, n, p1), mapOf(0 to Anchors(emptyList(), listOf(a)), 1 to Anchors(listOf(b), emptyList())), 1, line)
        val exp = PlanAnchors.expand(listOf(p0, n, p1), r, emptySet()).points
        val forced = (1 until exp.size).sumOf { Geo.distance(exp[it - 1], exp[it]) }
        val free = Geo.distance(p0, n) + Geo.distance(n, p1)
        println("P3 anchors=$r; forced ${forced.toInt()} m vs free ${free.toInt()} m")
        assertTrue("N ${Geo.distance(n, line[500]).toInt()} m off the line keeps anchors beside its projection", r.isEmpty())
    }

    // 4. a second stitch close behind the first: forInsert + followInsert give a list that runs backwards along the line
    @Test fun aSecondStitchNearTheFirstStaysInLineOrder() {
        val p1 = GeoPoint(51.0, 7.0); val next = GeoPoint(52.0, 7.0)
        val line = straight(p1, next)
        fun km(k: Double) = g(line[Math.round(k * 9).toInt()])
        val b1 = km(10.0)
        val p2 = GeoPoint(km(15.0).lat, 7.002)
        val own = PlanAnchors.forInsert(line, p1, next, p2)!!
        val moved = PlanAnchors.followInsert(listOf(p1, next), listOf(p1, p2, next), mapOf(0 to Anchors(emptyList(), listOf(b1))), 1, line)
        val merged = moved
        val exp = PlanAnchors.expand(listOf(p1, p2, next), merged, emptySet()).points
        println("P4 expanded lats: ${exp.map { "%.4f".format(it.lat) }}")
        val withAnchors = Planner(Base(line, ::res, ::straight), legs = LegCache()).plan(PlanAnchors.expand(listOf(p1, p2, next), merged, emptySet()), act, sac)
        val plainRoute = Planner(Base(line, ::res, ::straight), legs = LegCache()).plan(listOf(p1, p2, next), act, sac)
        println("P4 routed through the anchors ${withAnchors.distanceM.toInt()} m, without them ${plainRoute.distanceM.toInt()} m")
        val lats = exp.filter { it.lon == 7.0 }.map { it.lat }
        assertEquals("line points of the expanded list are in line order", lats.sorted(), lats)
    }

    // 5. the stand-in prefers a stale cut leg over a fresh plain leg of the same two points
    @Test fun standInTakesTheFreshLegOverAStaleCut() {
        val shelf = object : LegStore {
            val legs = HashMap<LegCache.Key, RouteResult>(); val stale = HashSet<LegCache.Key>()
            override fun get(key: LegCache.Key) = legs[key]?.let { LegStore.Stored(it, key !in stale) }
            override fun put(key: LegCache.Key, from: GeoPoint, to: GeoPoint, leg: RouteResult) { legs[key] = leg }
        }
        val prev = GeoPoint(51.0, 7.0); val a = GeoPoint(51.2, 7.0); val p = GeoPoint(51.25, 7.05); val next = GeoPoint(52.0, 7.0)
        val plan = PlanAnchors.expand(listOf(prev, p, next), mapOf(1 to Anchors(listOf(a), emptyList())), emptySet())
        val oldLine = path(prev, GeoPoint(51.1, 7.3), a); val newLine = straight(prev, a)
        shelf.legs[LegCache.key(prev, a, act, sac, cut = true)] = res(oldLine)
        shelf.stale += LegCache.key(prev, a, act, sac, cut = true)
        shelf.legs[LegCache.key(prev, a, act, sac)] = res(newLine)
        shelf.legs[LegCache.key(a, p, act, sac)] = res(straight(a, p))
        shelf.legs[LegCache.key(p, next, act, sac)] = res(straight(p, next))
        shelf.stale += LegCache.key(p, next, act, sac) // another leg is stale: the plan needs a stand-in
        val c = LegCache(store = shelf)
        val pre = StoredLine.preview(plan, act, sac, c, emptyList())
        println("P5 preview=${pre != null} stale=${pre?.stale}; stand-in leg 0 max lon=${pre?.route?.legs?.get(0)?.points?.maxOf { it.lon }}")
        assertEquals(7.0, pre!!.route.legs[0].points.maxOf { it.lon }, 1e-9)
    }

    // 7. a sub-leg that fails with a permanent engine error is no "no route": no second request for the visible leg
    @Test fun aSetupFailureIsNoNoRouteButARealOneIs() {
        for (m in listOf(Txt.s(Res.string.plan_err_profiles), Txt.s(Res.string.plan_err_profile_folder), Txt.s(Res.string.plan_err_coordinates),
            Txt.s(Res.string.plan_err_set_start_dest), Txt.s(Res.string.plan_err_set_start_end), Txt.s(Res.string.plan_err_profile_install, "trekking"),
            Txt.s(Res.string.plan_err_memory), Txt.s(Res.string.plan_err_complex), Txt.s(Res.string.plan_err_engine),
            Txt.s(Res.string.plan_err_too_long), Txt.s(Res.string.plan_err_unexpected))) assertTrue(m, !RoutingException(m).isNoRoute)
        assertTrue(!RoutingException("x", timedOut = true).isNoRoute)
        for (m in listOf(Txt.s(Res.string.plan_err_no_route_between, 1, 2), Txt.s(Res.string.plan_err_no_route_for, "Bike"),
            "No route found.", Txt.s(Res.string.plan_err_start_point))) assertTrue(m, RoutingException(m).isNoRoute)
    }

    // 8. two neighbouring anchors on the same line point (second stitch 20 km behind the first)
    @Test fun noZeroLengthLegBetweenTwoStitches() {
        val p1 = GeoPoint(51.0, 7.0); val next = GeoPoint(52.0, 7.0)
        val line = straight(p1, next)
        val at20 = line.indices.minByOrNull { Math.abs(Geo.length(line.subList(0, it + 1)) - 20_000.0) }!!
        val p2 = GeoPoint(line[at20].lat, 7.002)
        val own = PlanAnchors.forInsert(line, p1, next, p2)!!
        val b1 = own.before.single() // the first stitch's after-anchor sits on the same line point
        val moved = PlanAnchors.followInsert(listOf(p1, next), listOf(p1, p2, next), mapOf(0 to Anchors(emptyList(), listOf(b1))), 1, line)
        val merged = moved
        val exp = PlanAnchors.expand(listOf(p1, p2, next), merged, emptySet()).points
        val zero = (1 until exp.size).count { exp[it - 1].lat == exp[it].lat && exp[it - 1].lon == exp[it].lon }
        println("P8 zero-length legs in the expanded list: $zero")
        assertEquals(0, zero)
    }

    // ---- fuzz: a road grid with unique shortest paths, so "a piece of a best route IS the best route" holds exactly ----
    private class Grid(seed: Long) : RoutingEngine {
        val w = 80; val h = 80
        private val r = java.util.Random(seed)
        private val east = Array(w) { DoubleArray(h) { 1.0 + r.nextDouble() * 0.7 } }
        private val north = Array(w) { DoubleArray(h) { 1.0 + r.nextDouble() * 0.7 } }
        fun node(i: Int, j: Int) = GeoPoint(50.0 + j * 0.015, 7.0 + i * 0.02)
        fun id(p: GeoPoint) = Math.round((p.lon - 7.0) / 0.02).toInt() * h + Math.round((p.lat - 50.0) / 0.015).toInt()
        private val memo = HashMap<Long, List<GeoPoint>>()
        private fun path(s: Int, t: Int): List<GeoPoint> = memo.getOrPut(s.toLong() * 100000 + t) {
            val dist = DoubleArray(w * h) { Double.MAX_VALUE }; val from = IntArray(w * h) { -1 }
            val q = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first }); dist[s] = 0.0; q += 0.0 to s
            while (q.isNotEmpty()) {
                val (d, u) = q.poll(); if (d > dist[u]) continue; if (u == t) break
                val i = u / h; val j = u % h
                fun go(v: Int, c: Double) { if (d + c < dist[v]) { dist[v] = d + c; from[v] = u; q += (d + c) to v } }
                if (i + 1 < w) go(u + h, east[i][j]); if (i > 0) go(u - h, east[i - 1][j])
                if (j + 1 < h) go(u + 1, north[i][j]); if (j > 0) go(u - 1, north[i][j - 1])
            }
            val out = ArrayList<Int>(); var c = t; while (c != -1) { out += c; if (c == s) break; c = from[c] }
            out.reverse(); out.map { node(it / h, it % h) }
        }
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            val s = id(waypoints.first()); val t = id(waypoints.last())
            val l = if (s == t) listOf(waypoints.first(), waypoints.first()) else path(s, t)
            val d = Geo.length(l)
            return RouteResult(l, d, 0.0, 0.0, d / 5.0, matched = listOf(l.first(), l.last()))
        }
    }

    private fun linePts(r: PlannedRoute) = r.points.map { Math.round(it.lat * 1e6) to Math.round(it.lon * 1e6) }
        .fold(ArrayList<Pair<Long, Long>>()) { o, x -> if (o.lastOrNull() != x) o += x; o }

    private fun visibleLeg(route: PlannedRoute, plan: ExpandedPlan, v: Int): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        for (e in plan.expandedIndex(v) until plan.expandedIndex(v + 1)) { val l = route.legs[e].points; out += if (out.isEmpty()) l else l.drop(1) }
        return out
    }

    private val FUZZ_SEEDS = 150L

    @Test fun fuzzWarmEqualsCold() {
        var mismatches = 0; var runs = 0; var lineChanged = 0; var stitches = 0; var transfers = 0; val first = ArrayList<String>()
        for (seed in 1L..FUZZ_SEEDS) {
            val e = Grid(seed); val rnd = java.util.Random(seed * 7919)
            val cache = LegCache()
            var pts = listOf(e.node(2, 2), e.node(70, 75)); var anchors: Map<Int, Anchors> = emptyMap()
            val hist = ArrayList<Pair<List<GeoPoint>, Map<Int, Anchors>>>()
            val log = ArrayList<String>()
            fun warmRun(): Pair<ExpandedPlan, PlannedRoute> {
                val plan = PlanAnchors.expand(pts, anchors, emptySet())
                LegSplit.seed(cache, plan, act, sac)
                return plan to Planner(e, legs = cache, shortDetourRetry = false).plan(plan, act, sac)
            }
            var (plan, route) = warmRun()
            for (step in 0 until 14) {
                val before = pts; val beforeA = anchors; val oldLine = linePts(route)
                var onLineInsert = false
                when (rnd.nextInt(7)) {
                    0 -> { // stitch on a leg without anchors
                        val v = rnd.nextInt(pts.size - 1)
                        val l = visibleLeg(route, plan, v)
                        if (l.size > 8) {
                            val c = l[2 + rnd.nextInt(l.size - 4)]
                            val d = rnd.nextInt(3) - 1; val alongLon = rnd.nextBoolean() // one grid step beside the line (1.4–1.7 km): inside the 2 km of a stitch
                            val i = (e.id(c) / e.h + if (alongLon) d else 0).coerceIn(0, e.w - 1); val j = (e.id(c) % e.h + if (alongLon) 0 else d).coerceIn(0, e.h - 1)
                            val n = e.node(i, j)
                            val next = pts.toMutableList().also { it.add(v + 1, n) }
                            anchors = PlanAnchors.followInsert(pts, next, anchors, v + 1, l)
                            pts = next; if (anchors[v + 1] != null) stitches++; log += "stitch@${v + 1}"
                        }
                    }
                    1 -> { // a point ON the line (a night): the line must not change
                        val v = rnd.nextInt(pts.size - 1)
                        val l = visibleLeg(route, plan, v)
                        val taken = plan.points.map { it.lat to it.lon }.toSet()
                        val c = l.drop(1).dropLast(1).filter { (it.lat to it.lon) !in taken }.let { if (it.isEmpty()) null else it[rnd.nextInt(it.size)] }
                        if (c != null && l.count { it.lat == c.lat && it.lon == c.lon } == 1) {
                            val n = GeoPoint(c.lat, c.lon)
                            val next = pts.toMutableList().also { it.add(v + 1, n) }
                            anchors = PlanAnchors.followInsert(pts, next, anchors, v + 1, l)
                            pts = next; onLineInsert = true; transfers++; log += "onLine@${v + 1}"
                        }
                    }
                    2 -> if (pts.size > 2) { val k = rnd.nextInt(pts.size); val next = pts.toMutableList().also { it.removeAt(k) }; anchors = PlanAnchors.follow(pts, next, anchors); pts = next; log += "del@$k" }
                    3 -> { val next = pts.reversed(); anchors = PlanAnchors.reversed(anchors, pts.size); pts = next; log += "reverse" }
                    4 -> if (hist.isNotEmpty()) { val (p, a) = hist.removeAt(hist.lastIndex); pts = p; anchors = a; log += "undo" }
                    5 -> if (pts.first() != pts.last()) { val next = pts + pts.first(); anchors = PlanAnchors.follow(pts, next, anchors); pts = next; log += "roundTrip" }
                    else -> { val k = rnd.nextInt(pts.size); val c = pts[k]
                        val n = e.node((e.id(c) / e.h + 1).coerceAtMost(e.w - 1), e.id(c) % e.h)
                        val next = pts.toMutableList().also { it[k] = n }; anchors = PlanAnchors.follow(pts, next, anchors); pts = next; log += "move@$k" }
                }
                if (pts != before || anchors != beforeA) hist += before to beforeA
                val w = warmRun(); plan = w.first; route = w.second
                val cold = Planner(e, legs = LegCache(), shortDetourRetry = false).plan(plan, act, sac)
                runs++
                if (linePts(route) != linePts(cold) || Math.abs(route.distanceM - cold.distanceM) > 1.0) {
                    mismatches++
                    if (first.size < 3) first += "seed $seed step $step ops=$log warm=${linePts(route).size}pts/${route.distanceM.toInt()} cold=${linePts(cold).size}pts/${cold.distanceM.toInt()} anchors=${anchors.values.sumOf { it.size }}"
                    break
                }
                if (onLineInsert && linePts(route) != oldLine) {
                    lineChanged++
                    if (first.size < 3) first += "line changed by an on-line insert: seed $seed step $step ops=$log old=${oldLine.size} new=${linePts(route).size}"
                    break
                }
            }
        }
        println("P9 runs=$runs stitches=$stitches onLine=$transfers warm≠cold=$mismatches lineChangedByOnLineInsert=$lineChanged")
        first.forEach { println("P9 $it") }
        assertEquals(0, mismatches + lineChanged)
    }
}

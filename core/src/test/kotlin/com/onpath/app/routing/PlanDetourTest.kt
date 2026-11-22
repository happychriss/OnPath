package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.app.domain.PlanGaps
import com.onpath.app.time.Factors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.sqrt

/**
 * The red line "② macht 4,2 km Umweg" (owner 2026-11-21, „Hinweis, du entscheidest“): only when the T level causes the
 * detour – the next harder level is clearly shorter AND the difference belongs to this point. A summit planned on purpose
 * as a spur is the same at both levels and must show nothing. The route itself never changes.
 */
class PlanDetourTest {
    private val a = GeoPoint(46.7000, 11.1000)
    private val p = GeoPoint(46.7000, 11.1100)
    private val b = GeoPoint(46.7000, 11.1200)

    /** A way of [length] metres from one point to another: out to a bend and back to the line. */
    private fun line(from: GeoPoint, to: GeoPoint, length: Double): List<GeoPoint> {
        val d = Geo.distance(from, to)
        val h = sqrt(((length / 2) * (length / 2) - (d / 2) * (d / 2)).coerceAtLeast(1.0))
        return listOf(from, GeoPoint((from.lat + to.lat) / 2 + h / 111_195.0, (from.lon + to.lon) / 2), to)
    }

    /** Answers from [km]: (from, to, T level) → km; null = no way. [before] runs before each answer. */
    private inner class Table(val km: (GeoPoint, GeoPoint, Int) -> Double?, val before: (Int) -> Unit = {}) : RoutingEngine {
        val calls = mutableListOf<Triple<GeoPoint, GeoPoint, Int>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
            route(waypoints, activity, sacLimit, RouteLimits(30_000))
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
            val from = waypoints.first(); val to = waypoints.last()
            calls += Triple(from, to, sacLimit)
            before(sacLimit)
            val m = (km(from, to, sacLimit) ?: throw RoutingException("No route found near this point")) * 1000
            val l = line(from, to, m)
            return RouteResult(l, Geo.length(l), 0.0, 0.0, 60.0, matched = listOf(from, to))
        }
    }

    private fun plan(e: RoutingEngine, pts: List<GeoPoint> = listOf(a, p, b), act: Activity = Activity.HIKE, sac: Int = 1) =
        PlanResult.plan(e, pts, act, sac, emptySet(), emptySet(), Factors())

    /** T1: 3 + 3 km via ②; T2: 1 + 1 km; the two neighbours directly: 1.6 km at both. */
    private fun levelCaused(from: GeoPoint, to: GeoPoint, t: Int): Double? = when {
        from == a && to == b -> 1.6
        else -> if (t == 1) 3.0 else 1.0
    }

    private fun <T> inGerman(block: () -> T): T {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try { return block() } finally { Locale.setDefault(before) }
    }

    @Test fun aDetourTheLevelCausesIsFoundWithItsRealDifference() {
        val e = Table(::levelCaused)
        val found = PlanDetour.find(e, plan(e))
        val d = found.single()
        assertEquals(1, d.index)
        assertEquals("via T1 6 km − via T2 2 km, the neighbours the same at both", 4000.0, d.extraM, 60.0)
        assertTrue("the way shown on a tap is the drawn route around the point", d.way.size >= 3)
        assertEquals("② adds a 4.0 km detour", PlanGaps.detourText(found))
        assertEquals("② macht 4,0 km Umweg", inGerman { PlanGaps.detourText(found) })
    }

    @Test fun aSummitSpurTheSameAtBothLevelsShowsNothing() {
        val e = Table({ from, to, _ -> if (from == a && to == b) 1.6 else 3.0 })
        val res = plan(e); e.calls.clear()
        assertEquals(emptyList<PlanDetour>(), PlanDetour.find(e, res))
        assertEquals("only the same points one level up: no more routing once it is the same",
            listOf(Triple(a, p, 2), Triple(p, b, 2)), e.calls)
    }

    @Test fun aStretchLongAtThisLevelWithOrWithoutThePointShowsNothing() {
        // T1 is long whether ② is in the plan or not: deleting ② would save nothing, so "② macht … Umweg" would be untrue
        val e = Table({ from, to, t -> if (from == a && to == b) (if (t == 1) 5.8 else 1.8) else if (t == 1) 3.0 else 1.0 })
        assertEquals(emptyList<PlanDetour>(), PlanDetour.find(e, plan(e)))
    }

    @Test fun t5AndBikeShowNothingAndRouteNothingMore() {
        val t5 = Table({ _, _, _ -> 3.0 })
        val hard = plan(t5, sac = 5); t5.calls.clear()
        assertEquals(emptyList<PlanDetour>(), PlanDetour.find(t5, hard))
        assertEquals(emptyList<Triple<GeoPoint, GeoPoint, Int>>(), t5.calls)
        for (bike in Activity.entries - Activity.HIKE) {
            val e = Table({ _, _, _ -> 3.0 })
            val res = plan(e, act = bike); e.calls.clear()
            assertEquals(bike.name, emptyList<PlanDetour>(), PlanDetour.find(e, res))
            assertEquals(bike.name, emptyList<Triple<GeoPoint, GeoPoint, Int>>(), e.calls)
        }
    }

    @Test fun aFailedCheckShowsNothingAndLeavesTheRouteAsItIs() {
        val e = Table(::levelCaused, before = { t -> if (t == 2) throw IllegalStateException("router broke") })
        val res = plan(e)
        val line = res.route.points.toList()
        assertEquals(emptyList<PlanDetour>(), PlanDetour.find(e, res))
        assertEquals(line, res.route.points)
        val noWay = Table({ from, to, t -> if (t == 2) null else levelCaused(from, to, t) })
        assertEquals(emptyList<PlanDetour>(), PlanDetour.find(noWay, plan(noWay)))
    }

    @Test fun aCancelledCheckShowsNothing() {
        val cancel = RouteCancel()
        val e = Table(::levelCaused, before = { t -> if (t == 2) cancel.cancel() })
        assertEquals(emptyList<PlanDetour>(), PlanDetour.find(e, plan(e), cancel))
        val before = Table(::levelCaused)
        val res = plan(before); before.calls.clear()
        assertEquals(emptyList<PlanDetour>(), PlanDetour.find(before, res, RouteCancel().also { it.cancel() }))
        assertEquals("cancelled before it began: nothing routed", 0, before.calls.size)
    }

    @Test fun aPointNotReachedAtTheHarderLevelIsNoEvidence() {
        val e = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                val from = waypoints.first(); val to = waypoints.last()
                val km = levelCaused(from, to, sacLimit)!!
                val l = line(from, to, km * 1000)
                // at T2 the leg stops 500 m short of ②: its shorter length says nothing about reaching it
                val end = if (sacLimit == 2 && to == p) GeoPoint(p.lat + 500 / 111_195.0, p.lon) else to
                return RouteResult(l, Geo.length(l), 0.0, 0.0, 60.0, matched = listOf(from, end))
            }
        }
        assertEquals(emptyList<PlanDetour>(), PlanDetour.find(e, plan(e)))
    }

    @Test fun onlyAFewPointsAreCheckedTheWorstFirst() {
        val pts = (0..8).map { GeoPoint(46.7, 11.10 + it * 0.01) }
        // every interior point looks like a detour; the larger its index, the longer its legs
        val e = Table({ from, to, t ->
            val i = pts.indexOf(from); val j = pts.indexOf(to)
            if (j == i + 2) 1.6 else if (t == 1) 2.5 + i * 0.1 else 1.0
        })
        val res = plan(e, pts); e.calls.clear()
        val found = PlanDetour.find(e, res)
        assertEquals(PlanDetour.MAX_POINTS, found.size)
        assertEquals("the worst ones, in plan order", listOf(5, 6, 7), found.map { it.index })
        assertTrue("bounded routing: ${e.calls.size}", e.calls.size <= PlanDetour.MAX_POINTS * 4)
        assertEquals("⑥ adds a ${com.onpath.app.data.Format.distance(found[0].extraM)} detour (+2 more)", PlanGaps.detourText(found))
    }

    @Test fun aPlanWithAGapIsNotChecked() {
        val far = GeoPoint(p.lat + 1000 / 111_195.0, p.lon)
        val e = object : RoutingEngine {
            var calls = 0
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                calls++
                val from = waypoints.first(); val to = if (waypoints.last() == far) p else waypoints.last()
                val l = line(from, to, 3000.0)
                return RouteResult(l, Geo.length(l), 0.0, 0.0, 60.0, matched = listOf(from, to))
            }
        }
        val res = plan(e, listOf(a, far, b))
        assertTrue("the plan has its gap", res.gaps.isNotEmpty())
        val before = e.calls
        assertEquals(emptyList<PlanDetour>(), PlanDetour.find(e, res))
        assertEquals(before, e.calls)
    }

    @Test fun theDifferenceBelongsToThePointAndIsClear() {
        // the clean case: only the way via the point changes
        assertEquals(4000.0, PlanDetour.extra(6000.0, 2000.0, 1600.0, 1600.0)!!, 0.1)
        // part of the saving is the stretch itself: only the point's part counts
        assertEquals(2000.0, PlanDetour.extra(6000.0, 2000.0, 3600.0, 1600.0)!!, 0.1)
        // never more than the harder level really saves, even when its direct way is the longer one
        assertEquals(3000.0, PlanDetour.extra(6000.0, 3000.0, 2000.0, 3000.0)!!, 0.1)
        assertNull("below the minimum", PlanDetour.extra(4000.0, 3100.0, 1000.0, 1000.0))
        assertNull("over a km but under a fifth of the way", PlanDetour.extra(20_000.0, 18_500.0, 2000.0, 2000.0))
        assertNull("the same at both levels", PlanDetour.extra(6000.0, 6000.0, 1600.0, 1600.0))
        assertNotNull("exactly the minimum", PlanDetour.extra(5000.0, 4000.0, 1000.0, 1000.0))
    }
}

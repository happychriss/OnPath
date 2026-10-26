package com.onpath.app.ui

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WayAttrs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Safety audit batch B (owner 2026-10-22): the amber line's spots, its text and the "route changed" rule. */
class RouteWarningsTest {
    /** ~10 m per step northwards. */
    private val stepDeg = 10.0 / 111_195.0

    private val road = WayAttrs("quiet_road", "asphalt")
    private val track = WayAttrs("track", "gravel")
    private fun path(sac: Int = 0, mtb: Int = -1) = WayAttrs("path", "natural_trail", sac, mtb)
    private val footway = WayAttrs("footway", "paved")
    private val steps = WayAttrs("steps", "paved")

    /** A route of 10 m spans: each (way, metres, grade %) piece in order; elevations follow the grades. */
    private fun route(vararg pieces: Triple<WayAttrs, Int, Double>): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var ele = 500.0
        var i = 0
        for ((w, m, g) in pieces) repeat(m / 10) {
            out += GeoPoint(46.6 + i * stepDeg, 11.1, ele, w)
            ele += g / 100 * 10; i++
        }
        out += GeoPoint(46.6 + i * stepDeg, 11.1, ele, pieces.last().first)
        return out
    }

    private fun p(w: WayAttrs, m: Int, g: Double = 0.0) = Triple(w, m, g)

    @Test fun trekkingBikePushingStepsSteep() {
        val r = route(p(road, 1000), p(footway, 300), p(road, 200), p(steps, 30), p(track, 500), p(track, 300, 18.0), p(road, 1000))
        val spots = RouteWarnings.detect(r, Activity.BIKE_TREKKING, 3)
        assertEquals(listOf(SpotKind.PUSH, SpotKind.STEPS, SpotKind.STEEP), spots.map { it.kind })
        assertEquals(300.0, spots[0].lengthM, 1.0)
        assertEquals(1000.0, spots[0].fromM, 1.0)
        assertEquals(18, spots[2].worst)
        assertEquals("300 m pushing · steps · 1 steep section", RouteWarnings.text(spots, 3))
    }

    @Test fun pushingOnHardPathsDependsOnTheBike() {
        val r = route(p(road, 500), p(path(sac = 2), 400), p(path(sac = 4), 200), p(road, 500))
        val trek = RouteWarnings.detect(r, Activity.BIKE_TREKKING, 3)
        assertEquals(listOf(SpotKind.PUSH), trek.map { it.kind })
        assertEquals(600.0, trek.single().lengthM, 1.0) // T2 + T4 in one piece
        val mtb = RouteWarnings.detect(r, Activity.MTB, 3)
        assertEquals(200.0, mtb.single().lengthM, 1.0) // MTB: only T4+
        assertEquals("200 m pushing", RouteWarnings.text(mtb, 3))
    }

    @Test fun hikeAboveTLevelAndSteep() {
        val r = route(p(path(2), 800), p(path(4), 150, 10.0), p(path(2), 300), p(path(3), 100), p(path(5), 60), p(path(2), 200, 30.0), p(path(2), 300))
        val t2 = RouteWarnings.detect(r, Activity.HIKE, 2)
        assertEquals(listOf(SpotKind.ABOVE_T, SpotKind.ABOVE_T, SpotKind.STEEP), t2.map { it.kind })
        assertEquals(4, t2[0].worst)
        assertEquals(5, t2[1].worst) // T3 + T5 next to each other: one spot, worst T5
        assertEquals("2 sections above T2 · 1 steep section", RouteWarnings.text(t2, 2))
        assertEquals("T5 – above your T2", RouteWarnings.rowTitle(t2[1], 2))
        // footways and steps are normal for hikers; 18 % is not steep on foot
        val easy = route(p(footway, 300), p(steps, 50), p(path(2), 300, 18.0), p(road, 300))
        assertTrue(RouteWarnings.detect(easy, Activity.HIKE, 3).isEmpty())
        assertNull(RouteWarnings.text(emptyList(), 3))
    }

    @Test fun shortBitsAndUnknownWaysAreQuiet() {
        // 20 m of footway (a crossing), 40 m of 20 % (DEM bump): nothing to say
        val r = route(p(road, 500), p(footway, 20), p(road, 500), p(road, 40, 20.0), p(road, 500))
        assertTrue(RouteWarnings.detect(r, Activity.BIKE_TOURING, 3).isEmpty())
        // no way attributes, no elevations (straight legs, old saved lines)
        val bare = (0..100).map { GeoPoint(46.6 + it * stepDeg, 11.1) }
        assertTrue(RouteWarnings.detect(bare, Activity.BIKE_TOURING, 3).isEmpty())
        assertTrue(RouteWarnings.detect(bare.take(1), Activity.HIKE, 3).isEmpty())
    }

    @Test fun bikeAccessDecidesPushing() {
        val open = listOf("yes", "designated", "permissive").map { WayAttrs("footway", "paved", bicycle = it) } +
            WayAttrs("path", "natural_trail", 3, bicycle = "yes")
        for (w in open) {
            val r = route(p(road, 300), p(w, 300), p(road, 300))
            assertTrue("${w.wayType} bicycle=${w.bicycle}", RouteWarnings.detect(r, Activity.BIKE_TREKKING, 3).isEmpty())
        }
        // dismount: pushing on any way, also a plain road / track
        for (w in listOf(WayAttrs("footway", "paved", bicycle = "dismount"), WayAttrs("quiet_road", "paved", bicycle = "dismount"))) {
            val r = route(p(road, 300), p(w, 300), p(road, 300))
            val s = RouteWarnings.detect(r, Activity.MTB, 3).single()
            assertEquals(SpotKind.PUSH, s.kind)
            assertEquals(300.0, s.lengthM, 1.0)
        }
        // bicycle=no on an easy path: pushing even for an MTB
        val no = route(p(road, 300), p(WayAttrs("path", "ground", bicycle = "no"), 200), p(road, 300))
        assertEquals(SpotKind.PUSH, RouteWarnings.detect(no, Activity.MTB, 3).single().kind)
        // hikers: bike access is not their business
        val hike = route(p(road, 300), p(WayAttrs("footway", "paved", bicycle = "dismount"), 300), p(road, 300))
        assertTrue(RouteWarnings.detect(hike, Activity.HIKE, 3).isEmpty())
    }

    /**
     * Owner decision 2026-10-25: a footway without a bicycle tag that is part of a signed cycle route is
     * ridden – the touring / trekking profiles cost it like a cycleway, so no "pushing" there. A footway off a route,
     * or with bicycle=no / dismount, stays pushing.
     */
    @Test fun footwayOnACycleRouteIsNotPushing() {
        val onRoute = WayAttrs("footway", "paved", cycleRoute = true)
        for (a in listOf(Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.EBIKE)) {
            assertTrue("$a", RouteWarnings.detect(route(p(road, 300), p(onRoute, 300), p(road, 300)), a, 3).isEmpty())
        }
        val off = route(p(road, 300), p(WayAttrs("footway", "paved"), 300), p(road, 300))
        assertEquals(SpotKind.PUSH, RouteWarnings.detect(off, Activity.BIKE_TREKKING, 3).single().kind)
        for (b in listOf("no", "dismount")) {
            val signed = route(p(road, 300), p(onRoute.copy(bicycle = b), 300), p(road, 300))
            assertEquals(b, SpotKind.PUSH, RouteWarnings.detect(signed, Activity.BIKE_TOURING, 3).single().kind)
        }
    }

    /**
     * Review 2026-10-26: the exception follows the routing profile. mtb.brf still costs a footway on a cycle route as
     * pushing, so MTB / e-MTB keep the warning there (the line says what the router assumed).
     */
    @Test fun footwayOnACycleRouteStaysPushingForMtb() {
        val onRoute = route(p(road, 300), p(WayAttrs("footway", "paved", cycleRoute = true), 300), p(road, 300))
        for (a in listOf(Activity.MTB, Activity.EMTB)) {
            assertEquals("$a", SpotKind.PUSH, RouteWarnings.detect(onRoute, a, 3).single().kind)
        }
    }

    /**
     * Review 2026-10-26: a pedestrian zone (highway=pedestrian) is no footway to ride on a cycle route – no cycling there
     * unless signed (German law). Without bicycle=yes / designated it stays "pushing", with it it is ridden.
     */
    @Test fun pedestrianZoneOnACycleRouteStaysPushing() {
        val zone = WayAttrs("footway", "paving_stones", cycleRoute = true, pedestrian = true)
        for (a in listOf(Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.EBIKE)) {
            val r = route(p(road, 300), p(zone, 300), p(road, 300))
            assertEquals("$a", SpotKind.PUSH, RouteWarnings.detect(r, a, 3).single().kind)
            for (b in listOf("yes", "designated")) {
                val open = route(p(road, 300), p(zone.copy(bicycle = b), 300), p(road, 300))
                assertTrue("$a $b", RouteWarnings.detect(open, a, 3).isEmpty())
            }
        }
    }

    @Test fun gapsBelowThirtyMetresJoin() {
        val r = route(p(road, 300), p(footway, 100), p(road, 20), p(footway, 100), p(road, 300))
        val s = RouteWarnings.detect(r, Activity.EBIKE, 3).single()
        assertEquals(220.0, s.lengthM, 1.0)
    }

    @Test fun textKeepsThreeItemsThenPlusN() {
        val spots = listOf(RouteSpot(SpotKind.PUSH, 0, 1, 0.0, 1234.0), RouteSpot(SpotKind.STEPS, 1, 2, 10.0, 20.0),
            RouteSpot(SpotKind.STEPS, 2, 3, 30.0, 40.0), RouteSpot(SpotKind.ABOVE_T, 3, 4, 50.0, 90.0, 4),
            RouteSpot(SpotKind.STEEP, 4, 5, 100.0, 300.0, 20))
        assertEquals("1.2 km pushing · 2× steps · 1 section above T3 +1", RouteWarnings.text(spots, 3))
    }

    @Test fun rowsInPlainWords() {
        assertEquals("350 m · at the start", RouteWarnings.rowDetail(RouteSpot(SpotKind.PUSH, 0, 1, 0.0, 350.0)))
        assertEquals("150 m · after 3.4 km", RouteWarnings.rowDetail(RouteSpot(SpotKind.STEEP, 0, 1, 3420.0, 3570.0, 28)))
        assertEquals("Steep, up to 28 %", RouteWarnings.rowTitle(RouteSpot(SpotKind.STEEP, 0, 1, 0.0, 100.0, 28), 3))
    }

    @Test fun showTargets() {
        val r = route(p(road, 500), p(steps, 40), p(road, 1000), p(footway, 600), p(road, 100))
        val spots = RouteWarnings.detect(r, Activity.BIKE_TREKKING, 3)
        val (at, zoom) = RouteWarnings.centerFor(r, spots[0])!!
        assertEquals(17.0, zoom, 0.0)
        assertTrue(at.lat > r[50].lat && at.lat < r[54].lat)
        assertNull(RouteWarnings.centerFor(r, spots[1])) // long piece: fitted
        assertEquals(61, RouteWarnings.showPoints(r, spots[1]).size)
    }

    @Test fun changedTextNamesTheActivity() {
        assertEquals("New route for Trekking bike – check it", RouteWarnings.changedText(Activity.BIKE_TREKKING, 3))
        assertEquals("New route for Hike T4 – check it", RouteWarnings.changedText(Activity.HIKE, 4))
    }

    // ---------------- "route changed" ----------------

    private val east = 10.0 / (111_195.0 * Math.cos(Math.toRadians(46.6)))
    private fun line(n: Int, bump: IntRange = IntRange.EMPTY, bumpM: Double = 0.0) =
        (0..n).map { GeoPoint(46.6 + it * stepDeg, 11.1 + if (it in bump) east * bumpM / 10 else 0.0) }

    @Test fun sameRouteIsNotChanged() {
        val a = line(500)
        assertFalse(RouteWarnings.changedNoticeably(a, 5000.0, 300.0, a, 5000.0, 300.0))
        // small differences: 5 % length, 20 m ascent, 30 m shift
        assertFalse(RouteWarnings.changedNoticeably(a, 5000.0, 100.0, line(500, 100..200, 30.0), 5250.0, 120.0))
    }

    @Test fun lengthAscentOrMovedLineIsChanged() {
        val a = line(500)
        assertTrue(RouteWarnings.changedNoticeably(a, 5000.0, 300.0, a, 5600.0, 300.0)) // +12 % length
        assertTrue(RouteWarnings.changedNoticeably(a, 5000.0, 300.0, a, 5000.0, 380.0)) // +80 m ascent
        assertFalse(RouteWarnings.changedNoticeably(a, 5000.0, 50.0, a, 5000.0, 70.0)) // +40 % but only 20 m
        // 20 % of the line 200 m to the side, same length
        assertTrue(RouteWarnings.changedNoticeably(a, 5000.0, 300.0, line(500, 200..300, 200.0), 5000.0, 300.0))
        // 5 % moved: not noticeable
        assertFalse(RouteWarnings.changedNoticeably(a, 5000.0, 300.0, line(500, 200..225, 200.0), 5000.0, 300.0))
        assertFalse(RouteWarnings.changedNoticeably(emptyList(), 0.0, 0.0, a, 5000.0, 300.0))
    }
}

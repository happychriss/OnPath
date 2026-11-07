package com.onpath.app.ui

import com.onpath.app.data.TourKind
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** My routes' small route preview and the overview's region (owner 2026-11-07). */
class RouteShapeTest {
    private fun line(n: Int, dLat: Double = 0.01, dLon: Double = 0.02) =
        (0 until n).map { GeoPoint(46.6 + dLat * it / n, 11.1 + dLon * it / n) }

    @Test fun nothingToDrawGivesNoShape() {
        // a GPX without points, a 0 m tour, a GPX with one point: the quiet placeholder (the kind's sign)
        assertNull(RouteShape.of(emptyList(), 52f, 52f, 8f))
        assertNull(RouteShape.of(listOf(emptyList()), 52f, 52f, 8f))
        assertNull(RouteShape.of(listOf(listOf(GeoPoint(46.6, 11.1))), 52f, 52f, 8f))
        assertNull("the same spot twice is no line", RouteShape.of(listOf(listOf(GeoPoint(46.6, 11.1), GeoPoint(46.6, 11.1))), 52f, 52f, 8f))
    }

    @Test fun theShapeFitsTheTileInsideThePadding() {
        val s = RouteShape.of(listOf(line(50)), 52f, 52f, 8f)
        assertNotNull(s)
        s!!.lines.forEach { l ->
            l.forEachIndexed { i, v -> assertTrue("coordinate $i = $v inside 8..44", v in 7.99f..44.01f) }
        }
        // the wider side fills the room (36 px), the other is centred
        val xs = s.lines.flatMap { l -> l.filterIndexed { i, _ -> i % 2 == 0 } }
        assertEquals(36f, xs.max() - xs.min(), 0.5f)
    }

    @Test fun northIsUpAndTheAspectIsKept() {
        // a line going north-east: x grows, y shrinks (screen y down); a square in metres stays square
        val k = Math.cos(Math.toRadians(46.6))
        val square = listOf(GeoPoint(46.6, 11.1), GeoPoint(46.61, 11.1 + 0.01 / k))
        val s = RouteShape.of(listOf(square), 100f, 100f, 0f)!!
        assertTrue(s.endX > s.startX); assertTrue(s.endY < s.startY)
        assertEquals(abs(s.endX - s.startX), abs(s.endY - s.startY), 0.5f)
    }

    @Test fun aLongTrackIsCappedButKeepsItsEnds() {
        val long = line(5_000)
        val s = RouteShape.of(listOf(long), 52f, 52f, 8f)!!
        assertTrue("≤ ~${RouteShape.MAX_POINTS} points, was ${RouteShape.drawnPoints(s)}", RouteShape.drawnPoints(s) <= RouteShape.MAX_POINTS + 1)
        val l = s.lines.single()
        assertEquals(s.endX, l[l.size - 2], 0.01f); assertEquals(s.endY, l[l.size - 1], 0.01f)
    }

    @Test fun segmentsStaySeparate() {
        val s = RouteShape.of(listOf(line(10), line(10, dLat = -0.01)), 52f, 52f, 8f)!!
        assertEquals(2, s.lines.size)
    }

    @Test fun colourByKind() {
        assertEquals(RouteShape.Tint.PLAN, RouteShape.tint(TourKind.PLANNED))
        assertEquals("an imported GPX is a route one follows", RouteShape.Tint.PLAN, RouteShape.tint(TourKind.IMPORTED))
        assertEquals(RouteShape.Tint.DONE, RouteShape.tint(TourKind.RECORDED))
    }

    @Test fun theOverviewRegionHasAMinimumSpan() {
        assertNull(OverviewRegion.of(emptyList()))
        val one = OverviewRegion.of(listOf(GeoPoint(46.6, 11.1)))!!
        assertEquals(OverviewRegion.MIN_SPAN_DEG, one[2] - one[0], 1e-9)
        assertEquals(OverviewRegion.MIN_SPAN_DEG, one[3] - one[1], 1e-9)
        val big = OverviewRegion.of(line(10, dLat = 0.1, dLon = 0.2))!!
        assertEquals("a big route keeps its own bounds", 46.6, big[0], 1e-9)
    }

    // ⤢ badge (owner 2026-11-07): 20 px of the 26 px badge lie inside the 52 px tile, plus the goal dot's ring
    private val inside = 20f
    private val corner = RouteShape.cornerFor(inside, 5f)
    private fun noPointUnderTheBadge(s: RouteShape.Shape) {
        val x0 = 52f - inside; val y0 = 52f - inside
        assertTrue("the goal is not under the badge", !(s.endX > x0 && s.endY > y0))
        assertTrue("the start is not under the badge", !(s.startX > x0 && s.startY > y0))
        s.lines.forEach { l ->
            var i = 0
            while (i < l.size) { assertTrue("point (${l[i]}, ${l[i + 1]}) under the badge", !(l[i] > x0 && l[i + 1] > y0)); i += 2 }
        }
    }

    @Test fun aGoalInTheBadgeCornerIsDrawnBesideIt() {
        // north-west -> south-east: without the corner the goal lands right in the badge's corner
        val diag = (0..40).map { GeoPoint(46.61 - 0.01 * it / 40, 11.10 + 0.014 * it / 40) }
        val plain = RouteShape.of(listOf(diag), 52f, 52f, 8f)!!
        assertTrue("the test line reaches the corner", RouteShape.hits(plain, 52f - inside, 52f - inside))
        val s = RouteShape.of(listOf(diag), 52f, 52f, 8f, corner = corner)!!
        noPointUnderTheBadge(s)
    }

    @Test fun aLineAwayFromTheCornerKeepsItsSize() {
        // south-west -> north-east: the badge corner (bottom right) stays free, the fit is the same as without it
        val arc = (0..40).map { GeoPoint(46.60 + 0.01 * it / 40, 11.10 + 0.014 * it / 40) }
        val plain = RouteShape.of(listOf(arc), 52f, 52f, 8f)!!
        val s = RouteShape.of(listOf(arc), 52f, 52f, 8f, corner = corner)!!
        noPointUnderTheBadge(s)
        assertEquals(plain.endX, s.endX, 0.01f); assertEquals(plain.endY, s.endY, 0.01f)
    }

    @Test fun manyShapesNeverUnderTheBadge() {
        val r = java.util.Random(7)
        repeat(200) {
            val pts = (0..30).map { GeoPoint(46.6 + r.nextGaussian() * 0.01, 11.1 + r.nextGaussian() * 0.01) }
            noPointUnderTheBadge(RouteShape.of(listOf(pts), 52f, 52f, 8f, corner = corner)!!)
        }
    }
}

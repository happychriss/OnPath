package com.onpath.app.ui

import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WaypointSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-10-22 (screenshot: a message box "The route passes 180 m away from point 19: the last part is too difficult
 * for Bike – trekking, closed or has no path. OK"): no box; one red sheet line while it holds, one reason only.
 */
class PlanGapsTest {
    private val a = GeoPoint(46.6800, 11.1500)
    private val b = GeoPoint(46.6900, 11.1600)
    /** ~180 m north of [b]. */
    private val bFar = GeoPoint(46.69162, 11.1600)

    @Test fun ownerCaseLastPointWithName() {
        val gaps = PlanGaps.of(listOf(a, bFar), listOf(a, b))
        assertEquals(1, gaps.size)
        assertEquals(1, gaps[0].index)
        assertEquals(b, gaps[0].routeEnd)
        assertEquals("Last 180 m to \"Taser Alm\" not possible by trekking bike",
            PlanGaps.text(gaps, Activity.BIKE_TREKKING) { if (it == 1) "Taser Alm" else null })
    }

    @Test fun withoutNameThePointNumber() {
        val pts = List(18) { GeoPoint(46.60 + it * 0.002, 11.10) } + bFar
        val snapped: List<GeoPoint?> = pts.dropLast(1) + b
        assertEquals("Last 180 m to point 19 not possible by hike", PlanGaps.text(PlanGaps.of(pts, snapped), Activity.HIKE) { null })
        assertEquals("Last 180 m to point 19 not possible by hike", PlanGaps.text(PlanGaps.of(pts, snapped), Activity.HIKE) { "  " })
    }

    @Test fun startAndSeveralGaps() {
        val start = GeoPoint(46.6700, 11.1500); val startOn = GeoPoint(46.6700, 11.1530) // ~230 m
        val gaps = PlanGaps.of(listOf(start, a, bFar), listOf(startOn, a, b))
        assertEquals(listOf(0, 2), gaps.map { it.index })
        assertEquals("First 230 m from point 1 not possible by MTB (+1 more)", PlanGaps.text(gaps, Activity.MTB) { null })
    }

    @Test fun nearPointsAndUnknownPositionsAreNoGap() {
        val near = GeoPoint(46.6905, 11.1600) // ~55 m: normal snapping
        assertTrue(PlanGaps.of(listOf(a, near), listOf(a, b)).isEmpty())
        assertTrue(PlanGaps.of(listOf(a, bFar), listOf(a, null)).isEmpty())
        assertTrue(PlanGaps.of(listOf(a, bFar), emptyList()).isEmpty()) // straight-line engine: nothing known
        assertTrue(PlanGaps.of(listOf(a, bFar, b), listOf(a, b)).isEmpty()) // route of other points
        assertNull(PlanGaps.text(emptyList(), Activity.HIKE) { null })
    }

    @Test fun plainActivityNames() {
        assertEquals(listOf("touring bike", "trekking bike", "e-bike", "MTB", "e-MTB", "hike"),
            Activity.entries.map { PlanGaps.activityWords(it) })
    }

    /** A far point keeps its place (the gap stays visible); near points still snap onto the way. */
    @Test fun farPointsAreNotSnapped() {
        val near = GeoPoint(46.6905, 11.1600)
        val pts = listOf(a, near, bFar)
        val snapped = listOf(a, b, b)
        val far = PlanGaps.farIndices(pts, snapped)
        assertEquals(setOf(2), far)
        val after = WaypointSnap.apply(pts, snapped, keep = far)!!
        assertEquals(b, after[1])
        assertEquals(bFar, after[2])
    }

    @Test fun showGoesToTheMissingPiece() {
        val g = PlanGaps.of(listOf(a, bFar), listOf(a, b)).single()
        val (at, zoom) = PlanGaps.showTarget(g)
        assertTrue(Geo.distance(at, b) < 100 && Geo.distance(at, bFar) < 100)
        assertEquals(17.0, zoom, 0.0)
    }
}

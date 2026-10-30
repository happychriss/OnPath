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
 * A plan point the route does not reach (owner): no message box; one red sheet line while it holds, one reason only.
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
        assertEquals("Last 180 m to “Taser Alm”: no way for Gravelbike",
            PlanGaps.text(gaps, Activity.BIKE_TREKKING) { if (it == 1) "Taser Alm" else null })
    }

    @Test fun withoutNameThePointNumber() {
        val pts = List(18) { GeoPoint(46.60 + it * 0.002, 11.10) } + bFar
        val snapped: List<GeoPoint?> = pts.dropLast(1) + b
        assertEquals("Last 180 m to point 19: no way for Hike", PlanGaps.text(PlanGaps.of(pts, snapped), Activity.HIKE) { null })
        assertEquals("Last 180 m to point 19: no way for Hike", PlanGaps.text(PlanGaps.of(pts, snapped), Activity.HIKE) { "  " })
    }

    @Test fun startAndSeveralGaps() {
        val start = GeoPoint(46.6700, 11.1500); val startOn = GeoPoint(46.6700, 11.1530) // ~230 m
        val gaps = PlanGaps.of(listOf(start, a, bFar), listOf(startOn, a, b))
        assertEquals(listOf(0, 2), gaps.map { it.index })
        assertEquals("First 230 m from point 1: no way for MTB (+1 more)", PlanGaps.text(gaps, Activity.MTB) { null })
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
        assertEquals(listOf("Bike", "Gravelbike", "E-bike", "MTB", "E-MTB", "Hike"),
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

    /**
     * Field bug 2026-10-30: the route skipped stop 2 (matched on its path, line 47 m away) and the phone moved the stop
     * 47 m onto the line without a word. A skipped stop keeps its place and shows its gap – also under [PlanGaps.FAR_M].
     */
    @Test fun skippedStopKeepsItsPlaceAndShowsItsGap() {
        val stop = GeoPoint(46.68958, 11.1600)        // ~47 m south of b
        val pts = listOf(a, stop, GeoPoint(46.6900, 11.1700))
        val snapped = listOf(a, b, pts[2])           // the route passes b, 47 m from the stop
        assertTrue("not skipped: normal snapping, no gap", PlanGaps.of(pts, snapped).isEmpty())
        val gaps = PlanGaps.of(pts, snapped, skipped = setOf(1))
        assertEquals(1, gaps.size)
        assertEquals(1, gaps[0].index)
        assertTrue(gaps[0].skipped)
        assertEquals(b, gaps[0].routeEnd)
        assertEquals(47.0, gaps[0].distanceM, 1.0)
        assertEquals("Route misses point 2 by 50 m", PlanGaps.text(gaps, Activity.BIKE_TOURING) { null })
        assertEquals("Route misses “Steps” by 50 m", PlanGaps.text(gaps, Activity.BIKE_TOURING) { "Steps" })
        // AppViewModel.snapToRoute keeps every gap in place: the stop is not moved
        val far = PlanGaps.farIndices(pts, snapped, setOf(1))
        assertEquals(setOf(1), far)
        assertNull(WaypointSnap.apply(pts, snapped, keep = far))
    }

    /** A skipped stop the line passes within a few metres is snapping noise: no gap, it snaps as before. */
    @Test fun skippedStopCloserThanTheFloorIsNoGap() {
        val stop = GeoPoint(46.69006, 11.1600)        // ~7 m north of b
        val pts = listOf(a, stop, GeoPoint(46.6900, 11.1700))
        val snapped = listOf(a, b, pts[2])
        assertTrue(PlanGaps.of(pts, snapped, setOf(1)).isEmpty())
        assertEquals(b, WaypointSnap.apply(pts, snapped, keep = PlanGaps.farIndices(pts, snapped, setOf(1)))!![1])
    }
}

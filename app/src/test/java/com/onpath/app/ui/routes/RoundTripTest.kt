package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "↻ Rundtour" in My routes (owner 2026-11-13, variant A): certain for plans and followed plans, a guess for tracks. */
class RoundTripTest {
    private fun tour(kind: TourKind, w: List<GpxWaypoint> = emptyList(), roundTrip: Boolean? = null) =
        Tour(1, kind, "T", 1, "HIKE", 2, 5000.0, 0.0, 0.0, 0.0, "done", w, "u", roundTrip = roundTrip)
    private val a = GpxWaypoint(46.67, 11.16, null); private val b = GpxWaypoint(46.70, 11.20, null); private val c = GpxWaypoint(46.72, 11.14, null)

    @Test fun aPlannedRouteByItsPoints() {
        assertTrue(RoundTrip.known(tour(TourKind.PLANNED, listOf(a, b, c, a)))!!)
        assertFalse(RoundTrip.known(tour(TourKind.PLANNED, listOf(a, b, c)))!!)
        assertFalse("A → B → A is an out-and-back", RoundTrip.known(tour(TourKind.PLANNED, listOf(a, b, a)))!!)
        // its line does not matter: the plan says it
        assertFalse(RoundTrip.of(tour(TourKind.PLANNED, listOf(a, b)), listOf(loop(1000.0))))
    }

    @Test fun aRecordedTourByTheFollowedPlanElseByItsTrack() {
        assertTrue(RoundTrip.of(tour(TourKind.RECORDED, roundTrip = true), listOf(out())))
        assertFalse(RoundTrip.of(tour(TourKind.RECORDED, roundTrip = false), listOf(loop(1000.0))))
        assertNull(RoundTrip.known(tour(TourKind.RECORDED)))
        assertTrue("no plan: back within 200 m, 500 m out", RoundTrip.of(tour(TourKind.RECORDED), listOf(loop(1000.0))))
        assertFalse("a short ride ending where it began", RoundTrip.of(tour(TourKind.RECORDED), listOf(loop(300.0))))
        assertFalse(RoundTrip.of(tour(TourKind.IMPORTED), listOf(out())))
        assertTrue(RoundTrip.of(tour(TourKind.IMPORTED), listOf(loop(2000.0))))
        assertFalse(RoundTrip.of(tour(TourKind.IMPORTED), null))
    }

    /** A circle through the start, [m] across (≈ m metres at most from the start). */
    private fun loop(m: Double) = (0..60).map { i ->
        val t = i / 60.0 * 2 * Math.PI
        GeoPoint(46.67 + m / 2 / 111_000.0 * (1 - Math.cos(t)), 11.16 + m / 2 / 76_000.0 * Math.sin(t))
    }
    private fun out() = (0..20).map { GeoPoint(46.67 + it * 0.001, 11.16) }
}

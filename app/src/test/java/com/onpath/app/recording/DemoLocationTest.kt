package com.onpath.app.recording

import com.onpath.app.data.Geo
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.OffRouteDetector
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.time.NavReplay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Developer tools → Demo (DemoLocation): the switch between demo and real GPS, the simulated clock, and that the
 * recording / navigation pipeline takes demo fixes like real ones (point filter, NavReplay = the service's pipeline).
 */
class DemoLocationTest {
    private class Collect : DemoLocation.Sink {
        val fixes = ArrayList<Pair<DemoFix, Long>>()
        var ended = 0
        override fun onDemo(fix: DemoFix, timeMs: Long) { fixes += fix to timeMs }
        override fun onDemoEnd() { ended++ }
    }

    private val sinks = ArrayList<Collect>()
    private fun sink() = Collect().also { sinks += it; DemoLocation.register(it) }

    @After fun off() {
        DemoLocation.stop("test end")
        sinks.forEach { DemoLocation.unregister(it) }
    }

    /** A 3 km wiggly line going north-east (bends every ~100 m), heights rising and falling. */
    private val line: List<GeoPoint> = (0..300).map { i ->
        val d = i * 10.0
        GeoPoint(46.67 + d / 111_195.0 * 0.8, 11.16 + (d * 0.6 + 15 * sin(d / 80)) / 76_400.0, 600.0 + 40 * sin(d / 500))
    }

    private val t0 = 1_800_000_000_000L

    @Test fun realGpsIsIgnoredOnlyWhileADemoRuns() {
        assertTrue("no demo: real GPS", DemoLocation.takesRealFix())
        assertFalse(DemoLocation.active)
        DemoLocation.startRide(DemoPath.constant(line, 1.2)!!, "planned route", nowMs = t0, autoTick = false)
        assertTrue(DemoLocation.active)
        assertFalse("demo: real fixes ignored", DemoLocation.takesRealFix())
        DemoLocation.stop()
        assertTrue("stopped: real GPS again", DemoLocation.takesRealFix())
        assertNull(DemoLocation.state.value)
    }

    @Test fun trackersGetFixesWhileRunningAndTheEndWhenStopped() {
        val a = sink()
        DemoLocation.startRide(DemoPath.constant(line, 1.2)!!, "planned route", nowMs = t0, autoTick = false)
        assertEquals("the start at once", 1, a.fixes.size)
        assertEquals(line[0].lat, a.fixes[0].first.lat, 1e-9)
        // a tracker starting later (app back from the lock screen) gets the current position at once
        DemoLocation.tick(1000)
        val b = sink()
        assertEquals(1, b.fixes.size)
        assertEquals(a.fixes.last().first, b.fixes.last().first)
        repeat(3) { DemoLocation.tick(1000) }
        assertEquals(5, a.fixes.size); assertEquals(4, b.fixes.size)
        DemoLocation.stop()
        assertEquals(1, a.ended); assertEquals(1, b.ended)
        DemoLocation.tick(1000)
        assertEquals("nothing after the stop", 5, a.fixes.size)
        // a new tracker gets nothing from a stopped demo
        assertTrue(sink().fixes.isEmpty())
    }

    /**
     * Pixel review 2026-11-06: "DEMO" / "Sim ×1 · end" stayed on screen after the sim ride. A demo ride ends with its tour
     * (back to real GPS, the marks go); a demo standing at a place (screenshots) is no ride and stays.
     */
    @Test fun aDemoRideEndsWithTheTourAStandingDemoStays() {
        val a = sink()
        DemoLocation.startRide(DemoPath.constant(line, 1.2)!!, "followed route", nowMs = t0, autoTick = false)
        repeat(5_000) { DemoLocation.tick(1000) } // at the end, standing
        assertTrue(DemoLocation.state.value!!.ended)
        assertTrue(DemoLocation.endWithTour())
        assertNull("the ride is over: no DEMO, no Sim chip", DemoLocation.state.value)
        assertEquals(1, a.ended); assertTrue(DemoLocation.takesRealFix())
        assertFalse("nothing running: nothing to end", DemoLocation.endWithTour())
        DemoLocation.jumpTo(46.67, 11.16, null, "map centre", nowMs = t0, autoTick = false)
        assertFalse(DemoLocation.endWithTour())
        assertNotNull("a standing demo stays", DemoLocation.state.value)
    }

    @Test fun speedFactorMovesFasterOnASimulatedClock() {
        val a = sink()
        val p = DemoPath.constant(line, 1.0)!!
        DemoLocation.startRide(p, "planned route", nowMs = t0, autoTick = false)
        DemoLocation.tick(1000)
        assertEquals(1.0, a.fixes.last().first.alongM, 1e-6)
        assertEquals(t0 + 1000, a.fixes.last().second)
        DemoLocation.setFactor(20)
        DemoLocation.tick(1000)
        assertEquals(21.0, a.fixes.last().first.alongM, 1e-6)
        assertEquals("the fix time runs 20× – realistic speed for the point filter", t0 + 21_000, a.fixes.last().second)
        assertEquals(1.0, a.fixes.last().first.speedMs, 1e-6)
        DemoLocation.setFactor(99)
        assertEquals("only up to 20×", 20, DemoLocation.state.value!!.factor)
    }

    @Test fun standStillKeepsThePositionAndTheClockRuns() {
        val a = sink()
        DemoLocation.startRide(DemoPath.constant(line, 1.0)!!, "planned route", nowMs = t0, autoTick = false)
        repeat(10) { DemoLocation.tick(1000) }
        DemoLocation.setPaused(true)
        val at = a.fixes.last().first.alongM
        repeat(5) { DemoLocation.tick(1000) }
        val f = a.fixes.last()
        assertEquals(at, f.first.alongM, 1e-9)
        assertNull("standing: no bearing", f.first.bearingDeg)
        assertEquals(0.0, f.first.speedMs, 0.0)
        assertEquals(t0 + 15_000, f.second)
        DemoLocation.setPaused(false)
        DemoLocation.tick(1000)
        assertTrue(a.fixes.last().first.alongM > at)
    }

    @Test fun rideEndsStandingAtTheEnd() {
        val a = sink()
        val p = DemoPath.constant(line, 20.0)!!
        DemoLocation.startRide(p, "planned route", nowMs = t0, autoTick = false)
        repeat((p.durationS + 10).toInt()) { DemoLocation.tick(1000) }
        val st = DemoLocation.state.value!!
        assertTrue(st.ended)
        assertEquals(p.lengthM, st.alongM, 1e-6)
        val last = a.fixes.takeLast(5)
        assertTrue("fixes go on at the end (arrival needs them)", last.all { it.first.ended && it.first.alongM == p.lengthM })
        assertTrue("still running until stopped", DemoLocation.active)
    }

    /** Owner 2026-11-01 (Hilden): the followed line changed mid-ride – the Sim goes on along it from where it is. */
    @Test fun aNewLineIsRiddenOnFromTheNearestPoint() {
        val a = sink()
        DemoLocation.startRide(DemoPath.constant(line, 10.0)!!, "followed route", nowMs = t0, autoTick = false)
        repeat(50) { DemoLocation.tick(1000) } // ~500 m along
        val here = a.fixes.last().first
        // the new line: from 200 m along the old one, bending off east after 600 m
        val newLine = line.subList(20, 61) + (1..60).map { k -> GeoPoint(line[60].lat, line[60].lon + k * 10 / 76_400.0) }
        DemoLocation.switchLine(DemoPath.constant(newLine, 10.0)!!, "followed route")
        val st = DemoLocation.state.value!!
        assertEquals("about 300 m along the new line (500 − 200)", 300.0, st.alongM, 15.0)
        DemoLocation.tick(1000)
        val next = a.fixes.last().first
        assertTrue("no jump: the next fix is a step on", com.onpath.app.data.Geo.distance(here.lat, here.lon, next.lat, next.lon) < 25)
        // further on it follows the new line's eastward bend, not the old line
        repeat(60) { DemoLocation.tick(1000) }
        assertEquals(newLine.last().lat, a.fixes.last().first.lat, 1e-6)
    }

    @Test fun placeStandsStill() {
        val a = sink()
        DemoLocation.jumpTo(46.5, 11.3, null, "map centre", nowMs = t0, autoTick = false)
        repeat(3) { DemoLocation.tick(1000) }
        assertEquals(4, a.fixes.size)
        assertTrue(a.fixes.all { it.first.lat == 46.5 && it.first.lon == 11.3 && it.first.bearingDeg == null })
        assertEquals(DemoLocation.Kind.PLACE, DemoLocation.state.value!!.kind)
    }

    @Test fun neverStuckOn() {
        val a = sink()
        DemoLocation.jumpTo(46.5, 11.3, null, "map centre", nowMs = t0, autoTick = false)
        DemoLocation.tick(DemoLocation.MAX_RUN_MS + 1)
        assertNull("stops by itself after ${DemoLocation.MAX_RUN_MS} ms", DemoLocation.state.value)
        assertEquals(1, a.ended)
        // a new demo replaces a running one (the old one ends first)
        DemoLocation.jumpTo(46.5, 11.3, null, "map centre", nowMs = t0, autoTick = false)
        DemoLocation.startRide(DemoPath.constant(line, 1.0)!!, "planned route", nowMs = t0, autoTick = false)
        assertEquals(2, a.ended)
        assertEquals(DemoLocation.Kind.RIDE, DemoLocation.state.value!!.kind)
    }

    /** Fixes of a whole ride at [factor] as the service would store them (time = the fix's simulated clock). */
    private fun ride(path: DemoPath, factor: Int): List<TrackPoint> {
        val a = sink()
        DemoLocation.startRide(path, "planned route", factor = factor, nowMs = t0, autoTick = false)
        while (!DemoLocation.state.value!!.ended) DemoLocation.tick(1000)
        DemoLocation.stop()
        DemoLocation.unregister(a)
        return a.fixes.map { (f, t) -> TrackPoint(f.lat, f.lon, f.ele, t) }
    }

    @Test fun recordingKeepsEveryDemoFixAtEverySpeed() {
        for (activity in listOf(Activity.HIKE, Activity.BIKE_TOURING, Activity.EBIKE)) {
            val path = DemoPath.timed(line, activity)!!
            for (factor in DemoLocation.FACTORS) {
                val pts = ride(path, factor)
                // RecordingService: accuracy filter, then the jump / standing-still judge per fix
                assertTrue(PointFilter.accuracyOk(true, DemoLocation.ACCURACY_M))
                var prev: TrackPoint? = null
                var dist = 0.0
                var kept = 0
                var jumps = 0
                for (p in pts) {
                    val (v, d) = PointFilter.judge(prev, p, DemoLocation.ACCURACY_M.toDouble(), continuous = true)
                    if (v == PointFilter.Verdict.JUMP) jumps++
                    if (v == PointFilter.Verdict.ACCEPT) { dist += d; prev = p; kept++ }
                }
                assertEquals("$activity $factor×: no GPS jumps", 0, jumps)
                assertEquals("$activity $factor×: recorded distance = the line", path.lengthM, dist, path.lengthM * 0.02)
                assertTrue("$activity $factor×: $kept points", kept > 20)
            }
        }
    }

    @Test fun navigationFollowsADemoRideToTheEnd() {
        for (factor in DemoLocation.FACTORS) {
            val path = DemoPath.timed(line, Activity.HIKE)!!
            val r = NavReplay(line, Activity.HIKE, accuracyM = DemoLocation.ACCURACY_M.toDouble()).run(listOf(ride(path, factor)))
            assertEquals("$factor×: no off-route warning", 0, r.confirms)
            assertTrue("$factor×: never off route", r.frames.none { it.state == OffRouteDetector.State.OFF_ROUTE })
            val end = r.frames.last()
            assertEquals(OffRouteDetector.State.ON_ROUTE, end.state)
            assertNotNull(end.alongM)
            assertEquals("$factor×: at the end of the line", path.lengthM, end.alongM!!, 30.0)
            assertEquals(0.0, Geo.distance(end.point, line.last()), 1.0)
        }
    }

    /**
     * Field 2026-11-06 23:35 (Pixel, Hilden round trip whose first and last ~100 m are the same street): "▶ Sim" during
     * a tour 2.7 km along started the ride at ① again – a jump back that the navigator, keeping its progress, read as the
     * last metres of the loop walked backwards, so the next left turn of the way out was never said. A ride started
     * during a tour begins where the tour is along the line ([DemoLocation.startRide] startAlongM).
     */
    @Test fun aSimStartedDuringATourBeginsWhereTheTourIs() {
        val a = sink()
        val path = DemoPath.constant(line, 1.2)!!
        DemoLocation.startRide(path, "followed route", nowMs = t0, autoTick = false, startAlongM = 2_700.0)
        val first = a.fixes.first().first
        assertEquals("the first fix where the tour is, not at the start", 2_700.0, first.alongM, 5.0)
        assertEquals(2_700.0, DemoLocation.state.value!!.alongM, 5.0)
        DemoLocation.tick(1_000L)
        assertTrue("it goes on from there", a.fixes.last().first.alongM > 2_700.0)
        DemoLocation.stop()
        DemoLocation.startRide(path, "followed route", nowMs = t0, autoTick = false)
        assertEquals("without a tour: from the start as before", 0.0, a.fixes.last().first.alongM, 1e-6)
    }
}

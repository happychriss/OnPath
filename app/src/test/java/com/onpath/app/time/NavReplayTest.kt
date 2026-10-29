package com.onpath.app.time

import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.Trace
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Arrival / time to go through the service pipeline ([NavReplay]): the owner's recorded tours and synthetic walks for
 * the owner's rules (docs/plan-time-estimate.md).
 */
class NavReplayTest {

    // ---- owner's tours (trimmed fixtures) ----------------------------------------------------------------------

    private fun dir(name: String): File = listOf(File("src/test/resources/tracks"), File("app/src/test/resources/tracks"))
        .first { it.isDirectory }.let { File(it, name) }

    private fun line(f: File): List<GeoPoint> = GpxParser.parse(f.readBytes()).allSegments.flatten().map { it.toGeo() }
    private fun track(f: File): List<List<TrackPoint>> = GpxParser.parse(f.readBytes()).allSegments

    private fun report(name: String, r: NavReplay): String {
        val st = r.stateSeconds().entries.sortedBy { it.key }.joinToString(" ") { "%s %.0f min".format(Locale.US, it.key, it.value / 60) }
        val e = r.errors().joinToString("/") { if (it.isNaN()) "–" else "%.0f%%".format(Locale.US, it * 100) }
        return "== $name: to go err 25/50/75 " + e +
            " | frozen max %.0f m | %s | off-route warnings %d\n".format(Locale.US, r.frozenMaxM(), st, r.confirms) + r.timeline()
    }

    @Test fun ownerTour3ForwardsOnAB() {
        val d = dir("owner-2026-10-19-tour3")
        val r = NavReplay(line(File(d, "plan.gpx"))).run(track(File(d, "track.gpx")))
        println(report("tour 3, plan A-B", r))
        assertEquals(0, r.confirms)
        assertTrue(r.frozenMaxM() < 150)
        r.errors().forEach { assertTrue("error $it", it <= 0.30) }
        // learning per tour only (owner): the setting alone in the warm-up, the owner's pace after it
        val warm = r.frames.filter { it.time.movingS < 10 * 60 }
        assertTrue(warm.size > 50 && warm.all { it.factors == Factors.ONE })
        assertTrue(r.frames.last().factors != Factors.ONE)
        assertNull(r.frames.last().time.lineNote) // one line all along: nothing to explain
    }

    @Test fun ownerTour4BackOnTheRoundTripCountsDown() {
        // what the phone followed after "round trip" + Undo: A-B-A; the owner walked back from 3.7 km to A
        val d = dir("owner-2026-10-19-tour4")
        val r = NavReplay(line(File(d, "plan.gpx"))).run(track(File(d, "track.gpx")))
        println(report("tour 4, round trip A-B-A", r))
        assertTrue(r.frozenMaxM() < 150)
        // on the way back: the return half, counting down to A
        val onLine = r.frames.filter { it.alongM != null && it.walkedM in 300.0..3000.0 }
        assertTrue(onLine.all { it.alongM!! > r.frames.first().lineLengthM / 2 })
        val toGo = listOf(500.0, 1500.0, 2500.0).map { w -> r.frames.first { it.walkedM >= w }.shownToGoS!! }
        assertTrue("$toGo", toGo[0] > toGo[1] && toGo[1] > toGo[2])
        r.errors().drop(1).forEach { assertTrue("error $it", it <= 0.15) }
        // beyond A (off the plan): "not on route", no stale time
        assertEquals(EstimateState.NOT_ON_ROUTE, r.frames.last().time.state)
    }

    @Test fun ownerTour4BackwardsOnABWarnsAfter200m() {
        val d = dir("owner-2026-10-19-tour4")
        val r = NavReplay(line(File(d, "plan-ab.gpx"))).run(track(File(d, "track.gpx")))
        println(report("tour 4, plan A-B (walked backwards)", r))
        assertNotNull(r.firstPromptWalkedM)
        val first = r.firstPromptWalkedM!!
        assertTrue("first warning at $first m", first in 150.0..400.0)
        // the target stays: the time to go grows while walking away from it
        val toGo = listOf(300.0, 1500.0, 3000.0).map { w -> r.frames.first { it.walkedM >= w }.shownToGoS!! }
        assertTrue("$toGo", toGo[0] < toGo[1] && toGo[1] < toGo[2])
        assertEquals(EstimateState.NOT_ON_ROUTE, r.frames.last().time.state)
    }

    // ---- synthetic walks for the owner's rules -----------------------------------------------------------------

    /**
     * A walker through [corners] (local metres, [Trace]) at [speed] m/s, one fix every [everyS] s, ±3 m slowly drifting GPS error.
     * [stops]: after this many metres, stand for these minutes (a fix every 30 s, drifting a little).
     */
    private fun walk(vararg corners: Pair<Double, Double>, speed: Double = 1.2, everyS: Int = 2,
                     stops: List<Pair<Double, Double>> = emptyList(), t0: Long = 1_760_000_000_000L): List<List<TrackPoint>> {
        val out = ArrayList<TrackPoint>()
        var t = t0
        var walked = 0.0
        val pending = stops.toMutableList()
        var k = 0
        fun at(x: Double, y: Double) {
            val p = Trace.pt(x + 3 * sin(k * 0.07), y + 3 * sin(k * 0.11)); k++
            out += TrackPoint(p.lat, p.lon, null, t)
        }
        for (i in 0 until corners.size - 1) {
            val (x0, y0) = corners[i]; val (x1, y1) = corners[i + 1]
            val len = hypot(x1 - x0, y1 - y0)
            var s = 0.0
            while (s < len) {
                at(x0 + (x1 - x0) * s / len, y0 + (y1 - y0) * s / len)
                val step = speed * everyS
                s += step; walked += step; t += everyS * 1000L
                if (pending.isNotEmpty() && walked >= pending.first().first) {
                    val minutes = pending.removeAt(0).second
                    val (sx, sy) = x0 + (x1 - x0) * s / len to y0 + (y1 - y0) * s / len
                    val end = t + (minutes * 60_000).toLong()
                    while (t < end) { at(sx, sy); t += 30_000 }
                }
            }
        }
        at(corners.last().first, corners.last().second)
        return listOf(out)
    }

    private val straight = Trace.line(0.0 to 0.0, 3000.0 to 0.0)
    /** Out 2 km east and back on a path 8 m beside it. */
    private val outAndBack = Trace.line(0.0 to 0.0, 2000.0 to 0.0, 2000.0 to 8.0, 0.0 to 8.0)
    /** A–B and the round trip A–B–A on the same path (what "round trip" makes of a two-point plan). */
    private val ab = Trace.line(0.0 to 0.0, 2000.0 to 0.0)
    private val aba = Trace.line(0.0 to 0.0, 2000.0 to 0.0, 0.0 to 0.0)

    private fun NavReplay.at(walkedM: Double) = frames.first { it.walkedM >= walkedM }

    @Test fun outAndBackCountsDownOnTheWayBack() {
        // rule 3: out to the end and back; the way back is matched by the walking direction
        val r = NavReplay(outAndBack).run(walk(0.0 to 0.0, 2000.0 to 0.0, 2000.0 to 8.0, 0.0 to 8.0))
        println(report("out-and-back", r))
        assertEquals(0, r.confirms)
        assertTrue(r.frozenMaxM() < 150)
        assertTrue(r.at(1000.0).alongM!! < 1100)
        assertTrue(r.at(3000.0).alongM!! > 2900)
        val toGo = listOf(2500.0, 3200.0, 3800.0).map { r.at(it).shownToGoS!! }
        assertTrue("$toGo", toGo[0] > toGo[1] && toGo[1] > toGo[2])
    }

    @Test fun turningBackEarlyOnAnOutAndBackJumpsToTheWayBack() {
        // rule 3: turned around after 1.2 km of 2 km – the way back counts down, no warning
        val r = NavReplay(outAndBack).run(walk(0.0 to 0.0, 1200.0 to 0.0, 1200.0 to 8.0, 0.0 to 8.0))
        println(report("out-and-back, turned early", r))
        assertEquals(0, r.confirms)
        assertTrue(r.at(1600.0).alongM!! > 2700) // on the way back (1200 m from its end ≈ 2800)
        assertTrue(r.frames.last().time.toGoS!! <= 3 * 60.0)
        assertEquals(EstimateState.OK, r.frames.last().time.state)
    }

    @Test fun walkingBackwardsTimeGrowsThenWarns() {
        // rule 2: the target stays, the time to go grows, ~200 m backwards = off route
        val r = NavReplay(straight).run(walk(0.0 to 0.0, 1000.0 to 0.0, 500.0 to 0.0))
        println(report("backwards", r))
        val w = r.firstPromptWalkedM!!
        assertTrue("warned at $w", w in 1180.0..1260.0)
        assertTrue(r.at(1150.0).shownToGoS!! > r.at(1000.0).shownToGoS!! + 60)
        // still on the line: the time keeps counting from where the user is
        assertTrue(r.frames.last().alongM!! in 450.0..550.0)
        assertTrue(r.frames.last().shownToGoS!! > r.at(1150.0).shownToGoS!!)
    }

    @Test fun midRouteStartIsMatchedWhereTheUserIs() {
        // rule 9: start in the middle (also on the way back of an out-and-back)
        val r = NavReplay(straight).run(walk(1500.0 to 0.0, 2500.0 to 0.0))
        assertEquals(1500.0, r.frames.first { it.alongM != null }.alongM!!, 30.0)
        assertEquals(0, r.confirms)
        val r2 = NavReplay(outAndBack).run(walk(1000.0 to 8.0, 300.0 to 8.0))
        assertTrue(r2.at(200.0).alongM!! > 3000)
        assertEquals(0, r2.confirms)
        assertTrue(r2.frozenMaxM() < 150)
    }

    @Test fun offRouteShowsNotOnRouteNotAStaleTime() {
        // rule 6: walking 300 m away from the line, then back onto it further on
        val r = NavReplay(straight).run(walk(0.0 to 0.0, 600.0 to 0.0, 600.0 to 300.0, 900.0 to 300.0, 900.0 to 0.0, 1500.0 to 0.0))
        println(report("off route", r))
        assertEquals(EstimateState.NOT_ON_ROUTE, r.at(1000.0).time.state)
        assertNull(r.at(1000.0).alongM)
        assertEquals(EstimateState.OK, r.frames.last().time.state)
        assertEquals(1500.0, r.frames.last().alongM!!, 30.0)
    }

    @Test fun longBreakKeepsToGoAndMovesTheArrival() {
        // rule 7: 40 min at a hut – "to go" stays, the arrival clock moves later
        val r = NavReplay(straight).run(walk(0.0 to 0.0, 2000.0 to 0.0, stops = listOf(800.0 to 40.0)))
        val start = r.at(800.0)
        val inBreak = r.frames.filter { it.wallMs in start.wallMs + 180_000..start.wallMs + 39 * 60_000 }
        val first = inBreak.first(); val last = inBreak.last()
        assertTrue(last.activeS - first.activeS > 30 * 60)
        assertEquals(first.time.toGoS!!, last.time.toGoS!!, 61.0)
        assertTrue((last.time.arrivalMs!! - first.time.arrivalMs!!) / 60_000.0 > 30)
        assertTrue(inBreak.all { it.time.state == EstimateState.OK })
    }

    @Test fun planEditedMidWalkIsFollowedAtOnce() {
        // rule 1: A-B, after 1.2 km "round trip" (A-B-A), walk to B and back; on the way back "Undo"
        // (A-B again): the round trip counts down to A; after Undo the tour walks A-B backwards (rule 2)
        val track = walk(0.0 to 0.0, 2000.0 to 0.0, 300.0 to 0.0)
        val r = NavReplay(ab).run(track, listOf(NavReplay.Edit(1200.0, aba), NavReplay.Edit(3000.0, ab)))
        println(report("plan edited mid-walk", r))
        assertEquals(1200.0, r.frames.first { it.walkedM > 1210 && it.alongM != null }.alongM!!, 40.0) // not the way back
        assertTrue(r.at(2600.0).alongM!! in 2500.0..2700.0) // on the way back of A-B-A
        assertTrue(r.at(2200.0).shownToGoS!! > r.at(2900.0).shownToGoS!!)
        // after Undo: A-B, walking towards A = backwards → warning after ~200 m
        assertEquals(1000.0, r.frames.first { it.walkedM > 3010 && it.alongM != null }.alongM!!, 40.0)
        assertTrue(r.firstPromptWalkedM!! in 3150.0..3300.0)
        // the arrival moved by the round trip's extra way: said once, kept until the next change (Undo)
        val roundTrip = r.at(1400.0).time.lineNote!!
        assertTrue("$roundTrip", roundTrip.nowMs - roundTrip.wasMs > 15 * 60_000)
        assertEquals(roundTrip, r.at(2900.0).time.lineNote)
        assertTrue(r.frames.last().time.lineNote != roundTrip)
    }

    @Test fun movingWithoutProgressPausesTheEstimate() {
        // rule 8 (safety net): a GPS gap while walking → "estimate paused · no GPS", then back to normal
        val r = NavReplay(straight).run(walk(0.0 to 0.0, 2500.0 to 0.0), gpsGaps = listOf(1000.0 to 1300.0))
        assertEquals(PauseReason.NO_GPS, r.gapStates.firstOrNull { it.state == EstimateState.PAUSED }?.pausedBecause)
        assertEquals(EstimateState.OK, r.frames.last().time.state)
    }
}

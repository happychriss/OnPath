package com.onpath.app.navigation

import com.onpath.app.navigation.OffRouteDetector.State
import com.onpath.app.navigation.Trace.fix
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.walk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class OffRouteDetectorTest {

    private fun detector(l: List<com.onpath.app.routing.GeoPoint>) = OffRouteDetector(40.0).apply { setLine(NavLine(l)) }

    private fun run(d: OffRouteDetector, fixes: List<OffRouteDetector.Fix>) = fixes.map { d.onFix(it) }

    /** Route east 1 km, then north 1 km. */
    private val corner = line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 1000.0)

    @Test fun onRouteNeverOff() {
        val d = detector(corner)
        val s = run(d, walk(0.0 to 3.0, 1000.0 to 3.0, 1003.0 to 1000.0))
        assertTrue(s.none { it.confirmedNow })
        assertEquals(State.ON_ROUTE, d.state)
        assertEquals(2000.0, d.progressM, 15.0)
    }

    @Test fun missedTurnConfirmedAfterTimeAndDistance() {
        val d = detector(corner)
        val fixes = walk(0.0 to 0.0, 1000.0 to 0.0, 1300.0 to 0.0)
        val s = run(d, fixes)
        val i = s.indexOfFirst { it.confirmedNow }
        assertTrue("confirmed", i > 0)
        // the off distance (40 m + 8 m margin) is passed ~48 m after the corner, then 15 s more
        val x = (i - 1000 / 1.3) * 1.3
        assertTrue("confirmed at ~${x.toInt()} m past the turn", x in 60.0..100.0)
        assertEquals(State.OFF_ROUTE, d.state)
        // progress stays at the corner (where the user left the route)
        assertEquals(1000.0, d.progressM, 50.0)
        assertEquals(1, s.count { it.confirmedNow })
    }

    @Test fun shortcutRejoinsAhead() {
        // U-shaped route: east, north, west. The user cuts straight north across the U's opening… then meets the route.
        val u = line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 600.0, 0.0 to 600.0)
        val d = detector(u)
        val s = run(d, walk(0.0 to 0.0, 300.0 to 0.0, 600.0 to 300.0, 600.0 to 600.0, 300.0 to 600.0))
        assertTrue(s.any { it.confirmedNow })
        assertTrue(s.any { it.rejoinedNow })
        assertEquals(State.ON_ROUTE, d.state)
        assertTrue("progress ${d.progressM} beyond the cut part", d.progressM > 1900)
    }

    @Test fun gpsJumpIsIgnored() {
        val d = detector(corner)
        val fixes = walk(0.0 to 2.0, 400.0 to 2.0).toMutableList()
        // one wild fix 90 m off in the middle, then normal again
        val k = 150
        fixes[k] = fix(195.0, 90.0, fixes[k].timeMs, acc = 25.0)
        val s = run(d, fixes)
        assertTrue(s.none { it.confirmedNow })
        assertEquals(State.ON_ROUTE, d.state)
    }

    @Test fun tunnelGapNoAlarm() {
        val d = detector(corner)
        run(d, walk(0.0 to 0.0, 200.0 to 0.0))
        // leaves a little (GPS drifts in a canyon) then GPS lost
        run(d, (0 until 5).map { fix(205.0 + it, 70.0, 160_000L + it * 1000) })
        assertEquals(State.LEAVING, d.state)
        d.gpsLost()
        assertEquals(State.NO_GPS, d.state)
        // 60 s later back on the line further on
        val s = run(d, walk(300.0 to 0.0, 400.0 to 0.0, t0Ms = 230_000))
        assertTrue(s.none { it.confirmedNow })
        assertEquals(State.ON_ROUTE, d.state)
        assertEquals(400.0, d.progressM, 10.0)
    }

    @Test fun standingStillWithDriftNoAlarm() {
        // standing 45 m off the line with ±15 m drift for 5 minutes (photo break under trees)
        val d = detector(corner)
        run(d, walk(0.0 to 0.0, 300.0 to 0.0))
        val drift = (0 until 300).map { t ->
            val a = t * 0.7; val r = 15.0 * ((t * 37) % 10) / 10.0
            fix(300.0 + r * cos(a), 45.0 + r * sin(a), 300_000L + t * 1000, acc = 12.0)
        }
        val s = run(d, drift)
        assertTrue(s.none { it.confirmedNow })
    }

    @Test fun badAccuracyDriftNoAlarm() {
        // standing on the line, accuracy 25 m, drift up to 50 m away: margin + moving check keep quiet
        val d = detector(corner)
        run(d, walk(0.0 to 0.0, 300.0 to 0.0))
        val drift = (0 until 300).map { t -> fix(300.0 + 10 * sin(t * 0.3), 50.0 * ((t % 7) / 6.0), 300_000L + t * 1000, acc = 25.0) }
        assertTrue(run(d, drift).none { it.confirmedNow })
    }

    @Test fun walkingBackALittleStaysOnRoute() {
        val d = detector(corner)
        run(d, walk(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 500.0))
        assertEquals(1500.0, d.progressM, 10.0)
        // back 150 m (looking for a lost cap): progress goes back with you, no warning
        val s = run(d, walk(1000.0 to 500.0, 1000.0 to 350.0, t0Ms = 2_000_000))
        assertTrue(s.none { it.confirmedNow })
        assertEquals(State.ON_ROUTE, d.state)
        assertEquals(1350.0, d.shownAlongM!!, 10.0)
    }

    @Test fun walkingBackMoreThan200mIsOffRoute() {
        // owner rule: ~200 m backwards = the normal off-route warning; the position stays known (time grows)
        val d = detector(corner)
        run(d, walk(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 500.0))
        val s = run(d, walk(1000.0 to 500.0, 1000.0 to 0.0, 600.0 to 0.0, t0Ms = 2_000_000))
        assertEquals(1, s.count { it.confirmedNow })
        val at = s.indexOfFirst { it.confirmedNow }
        assertEquals(1300.0, s[at].progressM, 25.0)
        assertTrue(d.backward)
        assertEquals(600.0, d.shownAlongM!!, 15.0)
        assertEquals(1500.0, d.leftAtM, 15.0) // re-routing looks ahead of where the user turned
        // turning round again: back on the route after walking forwards a little
        val back = run(d, walk(600.0 to 0.0, 700.0 to 0.0, t0Ms = 3_000_000))
        assertTrue(back.any { it.rejoinedNow })
        assertEquals(State.ON_ROUTE, d.state)
        assertFalse(d.backward)
    }

    @Test fun earlyTurnOnOutAndBackJumpsToTheWayBack() {
        // out 1.5 km and back on a path 8 m beside it; the user turns after 600 m: the way back is the part running
        // their way – far ahead along the line, but no warning
        val ob = line(0.0 to 0.0, 1500.0 to 0.0, 1500.0 to 8.0, 0.0 to 8.0)
        val d = detector(ob)
        run(d, walk(0.0 to 0.0, 600.0 to 0.0))
        val s = run(d, walk(600.0 to 0.0, 600.0 to 8.0, 300.0 to 8.0, t0Ms = 1_000_000))
        assertTrue(s.none { it.confirmedNow })
        assertEquals(1500.0 + 8 + 1200.0, d.progressM, 20.0)
    }

    @Test fun shortSpurDoesNotJumpToTheEnd() {
        // a round trip whose start and end share a 50 m spur (walked out and back): the end of the line is no shortcut
        val l = line(0.0 to 0.0, 50.0 to 0.0, 0.0 to 0.0, -500.0 to 0.0, -500.0 to 500.0, 0.0 to 500.0, 0.0 to 0.0, 50.0 to 0.0, 0.0 to 0.0)
        val d = detector(l)
        run(d, walk(0.0 to 0.0, 50.0 to 0.0, 0.0 to 0.0, -300.0 to 0.0))
        assertEquals(400.0, d.progressM, 20.0)
        assertEquals(State.ON_ROUTE, d.state)
    }

    @Test fun hairpinsAreNotWalkingBackwards() {
        // zig-zag path up a slope: legs 10 m apart, turning every 100 m
        val zz = line(0.0 to 0.0, 100.0 to 0.0, 100.0 to 10.0, 0.0 to 10.0, 0.0 to 20.0, 100.0 to 20.0, 100.0 to 30.0, 0.0 to 30.0, 0.0 to 40.0, 100.0 to 40.0)
        val d = detector(zz)
        val s = run(d, walk(0.0 to 0.0, 100.0 to 0.0, 100.0 to 10.0, 0.0 to 10.0, 0.0 to 20.0, 100.0 to 20.0, 100.0 to 30.0, 0.0 to 30.0, 0.0 to 40.0, 100.0 to 40.0))
        assertTrue(s.none { it.confirmedNow })
        assertEquals(zz.let { NavLine(it).lengthM }, d.progressM, 15.0)
    }

    @Test fun midRouteStartTakesThePartRunningYourWay() {
        // started on the way back of an out-and-back: the first fix takes the earliest part, corrected once moving
        val ob = line(0.0 to 0.0, 1500.0 to 0.0, 1500.0 to 8.0, 0.0 to 8.0)
        val d = detector(ob)
        val s = run(d, walk(1000.0 to 8.0, 700.0 to 8.0))
        assertTrue(s.none { it.confirmedNow })
        assertEquals(1508.0 + 800.0, d.progressM, 20.0)
    }

    @Test fun roundTripStartsAtTheStartNotTheEnd() {
        val loop = line(0.0 to 0.0, 800.0 to 0.0, 800.0 to 800.0, 0.0 to 800.0, 0.0 to 0.0)
        val d = detector(loop)
        run(d, walk(0.0 to 0.0, 20.0 to 0.0))
        assertEquals(20.0, d.progressM, 10.0)
        // … and at the end it is at the end (window follows the progress)
        run(d, walk(20.0 to 0.0, 800.0 to 0.0, 800.0 to 800.0, 0.0 to 800.0, 0.0 to 10.0, t0Ms = 100_000))
        assertEquals(3190.0, d.progressM, 15.0)
    }

    @Test fun outAndBackKeepsTheLegYouAreOn() {
        // out east 1.5 km and back on a path 8 m beside it
        val ob = line(0.0 to 0.0, 1500.0 to 0.0, 1500.0 to 8.0, 0.0 to 8.0)
        val d = detector(ob)
        run(d, walk(0.0 to 0.0, 1200.0 to 0.0))
        assertEquals(1200.0, d.progressM, 10.0)
        run(d, walk(1200.0 to 0.0, 1500.0 to 0.0, 1500.0 to 8.0, 1200.0 to 8.0, t0Ms = 1_000_000))
        assertEquals(1808.0, d.progressM, 15.0)
        assertEquals(State.ON_ROUTE, d.state)
    }

    @Test fun rejoinNeedsThreeFixesInARow() {
        val d = detector(corner)
        run(d, walk(0.0 to 0.0, 300.0 to 0.0, 300.0 to 200.0))
        assertEquals(State.OFF_ROUTE, d.state)
        // two fixes on the line, then away again: still off
        run(d, listOf(fix(320.0, 2.0, 900_000), fix(322.0, 2.0, 901_000), fix(330.0, 120.0, 902_000)))
        assertEquals(State.OFF_ROUTE, d.state)
        val s = run(d, listOf(fix(340.0, 2.0, 910_000), fix(342.0, 2.0, 911_000), fix(344.0, 2.0, 912_000)))
        assertTrue(s.last().rejoinedNow)
        assertEquals(State.ON_ROUTE, d.state)
    }

    @Test fun gpsLostKeepsConfirmedState() {
        val d = detector(corner)
        run(d, walk(0.0 to 0.0, 300.0 to 0.0, 300.0 to 200.0))
        assertEquals(State.OFF_ROUTE, d.state)
        d.gpsLost()
        assertEquals(State.OFF_ROUTE, d.state)
    }

    @Test fun forceCheckWhenStartingAwayFromTheRoute() {
        // been on the route this tour (restart, NavSession.reached): away from it = off route at once
        val d = OffRouteDetector(40.0).apply { setLine(NavLine(corner), reached = true) }
        assertTrue(d.forceCheck(fix(500.0, 300.0, 0)))
        assertEquals(State.OFF_ROUTE, d.state)
        val d2 = detector(corner)
        assertFalse(d2.forceCheck(fix(500.0, 10.0, 0)))
        assertEquals(500.0, d2.progressM, 10.0)
        assertTrue(d2.reached)
    }

    /** Owner 2026-10-31: never on the route yet – away from it is not "off route", and the progress stays at the start. */
    @Test fun neverReachedIsNeverOffRouteAndKeepsTheStart() {
        val d = detector(corner)
        assertFalse(d.forceCheck(fix(500.0, 300.0, 0)))
        assertEquals(State.NO_GPS, d.state)
        val s = run(d, (0 until 60).map { fix(1_000.0 + 30_000.0 + (if (it % 2 == 0) 0.0 else 40.0), 1_000.0, it * 1000L) })
        assertTrue(s.none { it.confirmedNow })
        assertFalse(d.reached)
        assertEquals(0.0, d.progressM, 0.0)
        assertEquals(0.0, d.leftAtM, 0.0)
        // first on the line in the middle: from there on as always
        run(d, walk(1_000.0 to 300.0, 1_000.0 to 500.0, t0Ms = 100_000))
        assertTrue(d.reached)
        assertEquals(1_500.0, d.progressM, 15.0)
    }

    /** Bug 02: a reached line, the user stands still 60 km away – LEAVING forever must not show progress on the line. */
    @Test fun bug02StandingStillFarFromAReachedLineIsNotOnIt() {
        val d = OffRouteDetector(40.0)
        d.setLine(NavLine(line(0.0 to 0.0, 2_700.0 to 0.0)), null, reached = true)
        (0 until 60).forEach { i -> d.onFix(fix(-60_000.0, 0.0, i * 1000L)) }
        assertTrue("state ${d.state}, shown ${d.shownAlongM}", d.shownAlongM == null)
    }
}

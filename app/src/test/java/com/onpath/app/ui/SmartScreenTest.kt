package com.onpath.app.ui

import com.onpath.app.data.AppSettings
import com.onpath.app.data.BikeScreen
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.TurnDetector
import com.onpath.app.routing.TurnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart screen while cycling (owner 2026-10-30): when the rest view shows instead of the map, and that a hike never gets
 * it (pocket logic unchanged).
 */
class SmartScreenTest {

    // ---- who gets what (Settings → Navigation) ----

    @Test fun hikeIsUnchangedWhateverTheCyclingChoice() {
        for (b in BikeScreen.entries) {
            assertEquals(ScreenPolicy(keepOn = true, pocket = true, rest = false),
                ScreenPolicy.of(AppSettings(keepScreenOn = true, bikeScreen = b), Activity.HIKE))
            assertEquals(ScreenPolicy(keepOn = false, pocket = true, rest = false),
                ScreenPolicy.of(AppSettings(keepScreenOn = false, bikeScreen = b), Activity.HIKE))
        }
    }

    @Test fun everyBikeActivityFollowsTheCyclingChoice() {
        val bikes = Activity.entries - Activity.HIKE
        assertEquals(5, bikes.size)
        for (a in bikes) {
            assertEquals(ScreenPolicy(true, false, true), ScreenPolicy.of(AppSettings(), a)) // Smart is the default
            assertEquals(ScreenPolicy(true, false, false), ScreenPolicy.of(AppSettings(bikeScreen = BikeScreen.ALWAYS), a))
            assertEquals(ScreenPolicy(false, false, false), ScreenPolicy.of(AppSettings(bikeScreen = BikeScreen.PHONE), a))
            // the hike switch does not change the bike
            assertEquals(ScreenPolicy(true, false, true), ScreenPolicy.of(AppSettings(keepScreenOn = false), a))
        }
    }

    @Test fun oldKeepScreenOnChoiceIsKept() {
        assertEquals(BikeScreen.SMART, BikeScreen.of(null, keepScreenOn = true))
        assertEquals(BikeScreen.PHONE, BikeScreen.of(null, keepScreenOn = false)) // the user's "off" stays off
        assertEquals(BikeScreen.ALWAYS, BikeScreen.of("ALWAYS", keepScreenOn = false))
        assertEquals(BikeScreen.SMART, BikeScreen.of("nonsense", keepScreenOn = true))
    }

    // ---- what comes next on the line ----

    private val mPerDeg = 111_195.0
    /** Straight north, a point every 10 m, [lengthM] long; cues at the given metres. */
    private fun line(lengthM: Int, cues: Map<Int, JunctionCue> = emptyMap(), exit: Int = 0, angle: Float = 0f) =
        (0..lengthM step 10).map { m ->
            val c = cues[m]
            GeoPoint(46.5 + m / mPerDeg, 11.0, turn = c, exit = if (c == JunctionCue.ROUNDABOUT) exit else 0,
                turnAngle = if (c != null) angle else 0f)
        }

    @Test fun eventsAreJunctionsRoundaboutsAndTheDestinationButNoSlightBends() {
        val l = line(3000, mapOf(500 to JunctionCue.KEEP_LEFT, 900 to JunctionCue.SLIGHT_RIGHT, 1500 to JunctionCue.RIGHT,
            2000 to JunctionCue.ROUNDABOUT), exit = 2, angle = 80f)
        val e = RestEvents.of(l, emptyList())
        assertEquals(listOf(500.0, 1500.0, 2000.0, 3000.0), e.map { Math.round(it.atM).toDouble() })
        assertEquals(TurnKind.SLIGHT_LEFT, e[0].kind)
        assertEquals(TurnKind.RIGHT, e[1].kind)
        assertEquals(2, e[2].roundabout)
        assertTrue(e[3].isDestination)
    }

    @Test fun drawnTurnsCountAndOnePlaceIsOneEvent() {
        // an L-shaped line: 1 km north, then 1 km east – the turn card's turn at 1000 m; a BRouter "right" 10 m before it
        val north = (0..1000 step 10).map { GeoPoint(46.5 + it / mPerDeg, 11.0, turn = if (it == 990) JunctionCue.RIGHT else null) }
        val east = (10..1000 step 10).map { GeoPoint(46.5 + 1000 / mPerDeg, 11.0 + it / (mPerDeg * Math.cos(Math.toRadians(46.5)))) }
        val l = north + east
        val turns = TurnDetector.turns(l)
        assertEquals(1, turns.size)
        val e = RestEvents.of(l, turns)
        assertEquals(2, e.size) // the turn (merged with the cue) + destination
        assertEquals(TurnKind.RIGHT, e[0].kind)
        assertEquals(990.0, e[0].atM, 15.0) // the earlier of the two places
    }

    @Test fun nextSkipsPassedEvents() {
        val e = RestEvents.of(line(3000, mapOf(500 to JunctionCue.LEFT)), emptyList())
        assertEquals(500.0, RestEvents.next(e, 0.0)!!.distanceM, 1.0)
        assertEquals(498.0, RestEvents.next(e, 2.0)!!.distanceM, 1.0)
        // just passed (within 5 m) still counts as passed only after 5 m – then the destination
        assertTrue(RestEvents.next(e, 506.0)!!.event.isDestination)
        assertEquals(2494.0, RestEvents.next(e, 506.0)!!.distanceM, 1.0)
        assertNull(RestEvents.next(e, 3000.0))
        assertNull(RestEvents.next(e, null))
    }

    // ---- when the rest view shows ----

    private val key = 5000L
    private fun far(m: Double = 2000.0, attention: Boolean = false, touched: Long = 0, k: Long = key) =
        SmartScreen.In(on = true, attention = attention, targetKey = k, targetM = m, touchedMs = touched)

    @Test fun farFromTheNextTurnTheRestViewShows() {
        val g = SmartScreen()
        g.noteSpeed(6.0, 0)
        assertTrue(g.rest(far(2000.0), 1_000))
        assertFalse(g.rest(far(2000.0).copy(on = false), 1_000)) // not allowed (Always on, hike, no tour) → map
    }

    @Test fun mapComesBackFiftySecondsBeforeTheTurnAtTheCurrentSpeed() {
        val g = SmartScreen()
        g.noteSpeed(8.0, 0) // 29 km/h → 400 m
        assertEquals(400.0, g.leadM(0), 0.01)
        assertTrue(g.rest(far(410.0), 0))
        assertFalse(g.rest(far(399.0), 0))
    }

    @Test fun atLeast150MetresBefore() {
        val g = SmartScreen()
        g.noteSpeed(1.0, 0) // walking the bike: 50 m would be too late
        assertEquals(150.0, g.leadM(0), 0.01)
        assertTrue(g.rest(far(160.0), 0))
        assertFalse(g.rest(far(149.0), 0))
    }

    @Test fun speedingUpCountsAtOnceSlowingDownAfterTenSeconds() {
        val g = SmartScreen()
        g.noteSpeed(10.0, 0)
        g.noteSpeed(4.0, 5_000)
        assertEquals(500.0, g.leadM(5_000), 0.01) // the highest of the last 10 s
        g.noteSpeed(4.0, 12_000)
        assertEquals(200.0, g.leadM(12_000), 0.01)
    }

    @Test fun unknownSpeedUsesAboutTwentyKmh() {
        assertEquals(SmartScreen.FALLBACK_SPEED_MPS * SmartScreen.LEAD_S, SmartScreen().leadM(0), 0.01)
    }

    @Test fun slowingDownForTheTurnKeepsTheMap() {
        val g = SmartScreen()
        g.noteSpeed(8.0, 0)
        assertFalse(g.rest(far(380.0), 0)) // map: 400 m lead
        g.noteSpeed(2.0, 20_000) // braking: lead now 150 m, the turn still 300 m away
        assertFalse(g.rest(far(300.0), 20_000))
    }

    @Test fun mapStaysTenSecondsAfterTheTurn() {
        val g = SmartScreen()
        g.noteSpeed(6.0, 0)
        assertFalse(g.rest(far(100.0), 0))
        // passed: the next one is far away
        assertFalse(g.rest(far(2000.0, k = key + 1), 30_000))
        assertFalse(g.rest(far(1950.0, k = key + 1), 39_000))
        assertTrue(g.rest(far(1900.0, k = key + 1), 40_001))
        assertTrue(g.recheckInMs(30_000) in 1..10_001)
    }

    @Test fun twoTurnsCloseTogetherKeepTheMapForBoth() {
        val g = SmartScreen()
        g.noteSpeed(6.0, 0)
        assertFalse(g.rest(far(50.0), 0))
        // passed, the next turn only 200 m on: within the lead → held for that one too
        assertFalse(g.rest(far(200.0, k = key + 1), 5_000))
        assertFalse(g.rest(far(150.0, k = key + 1), 20_000))
    }

    @Test fun aTapShowsTheMapForThirtySeconds() {
        val g = SmartScreen()
        assertTrue(g.rest(far(), 0))
        assertFalse(g.rest(far(touched = 1_000), 1_000))
        assertFalse(g.rest(far(touched = 1_000), 30_999))
        assertTrue(g.rest(far(touched = 1_000), 31_001)) // still far: back to rest
        // a pan / button press during the 30 s extends it
        assertFalse(g.rest(far(touched = 50_000), 50_000))
        assertFalse(g.rest(far(touched = 70_000), 79_000))
        assertTrue(g.rest(far(touched = 70_000), 100_001))
    }

    @Test fun anythingThatNeedsTheUserShowsTheMapAndTenSecondsAfter() {
        val g = SmartScreen()
        assertTrue(g.rest(far(), 0))
        // off route / arrival question / GPS lost / waiting for GPS / a dialog / paused / map moved by hand
        assertFalse(g.rest(far(attention = true), 1_000))
        assertFalse(g.rest(far(attention = true), 100_000))
        assertFalse(g.rest(far(), 105_000))
        assertTrue(g.rest(far(), 110_001))
    }

    private fun needs(paused: Boolean = false, gpsLost: Boolean = false, hadGpsFix: Boolean = true, provisional: Boolean = false,
                      offRoute: Boolean = false, prompt: Boolean = false, notOnRoute: Boolean = false, noFix: Boolean = false,
                      notFollowing: Boolean = false, dialog: Boolean = false) =
        SmartScreen.needsUser(paused, gpsLost, hadGpsFix, provisional, offRoute, prompt, notOnRoute, noFix, notFollowing, dialog)

    /** Indoor start (navigation/Provisional): on a Wi-Fi / fused position the map + amber line stay until the first GPS fix. */
    @Test fun neverRestsBeforeTheFirstGpsFixOfTheTour() {
        assertFalse(needs()) // normal riding: the rest view may show
        assertTrue(needs(hadGpsFix = false)) // no GPS fix yet in this tour, not even a provisional one
        assertTrue(needs(hadGpsFix = false, provisional = true))
        assertTrue(needs(provisional = true)) // a provisional fix shown for any reason
        // and so the gate never rests then, however far the next turn
        val g = SmartScreen()
        assertFalse(g.rest(far(5_000.0, attention = needs(hadGpsFix = false, provisional = true)), 0))
        assertFalse(g.rest(far(5_000.0, attention = needs(hadGpsFix = false, provisional = true)), 600_000))
        // first GPS fix: rest again once the 10 s after it are over
        assertFalse(g.rest(far(5_000.0, attention = needs()), 605_000))
        assertTrue(g.rest(far(5_000.0, attention = needs()), 610_001))
    }

    @Test fun everyOtherReasonNeedsTheUserToo() {
        listOf(needs(paused = true), needs(gpsLost = true), needs(offRoute = true), needs(prompt = true), needs(notOnRoute = true),
            needs(noFix = true), needs(notFollowing = true), needs(dialog = true)).forEach { assertTrue(it) }
    }

    @Test fun ridingAwayFromTheTurnLetsItGo() {
        val g = SmartScreen()
        g.noteSpeed(4.0, 0) // 200 m lead
        assertFalse(g.rest(far(190.0), 0))
        // turned round before it (no off-route yet): 100 m beyond the largest lead → rest again, no "after" hold
        g.noteSpeed(4.0, 30_000)
        assertFalse(g.rest(far(290.0), 30_000))
        g.noteSpeed(4.0, 31_000)
        assertTrue(g.rest(far(301.0), 31_000))
    }

    @Test fun noNextEventRests() {
        val g = SmartScreen()
        assertTrue(g.rest(SmartScreen.In(on = true, attention = false, targetKey = null, targetM = null), 0))
    }

    @Test fun switchedOffForgetsTheHolds() {
        val g = SmartScreen()
        assertFalse(g.rest(far(touched = 1_000), 1_000))
        g.rest(far().copy(on = false), 2_000)
        // switched on again later without a new touch: the old tap still counts only from its own time
        assertTrue(g.rest(far(touched = 1_000), 40_000))
    }

    @Test fun targetKeyIsStablePerPlace() {
        val e = RestEvent(1234.56, TurnKind.LEFT)
        assertEquals(RestTarget(e, 100.0).key, RestTarget(e, 50.0).key)
        assertNotNull(RestTarget(e, 1.0))
    }
}

package com.onpath.app.time

import com.onpath.app.navigation.NavLine
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TripPaceTest {

    // 6 km: 3 km up at 10 % (300 m), then 3 km down at 10 %
    private val pts = (0..120).map { i -> GeoPoint(46.67, 11.10 + i * 0.00065, if (i <= 60) 500.0 + i * 5.0 else 800.0 - (i - 60) * 5.0) }
    private val times = RouteTimes.of(pts, Activity.HIKE)
    private val line = NavLine(pts)

    /** Walks the line with personal factors (relative to the model) on a 1 s clock; returns (trip, seconds walked). */
    private fun walk(trip: TripPace, f: Factors, toFraction: Double = 1.0, startS: Double = 0.0, startAlong: Double = 0.0,
                     onFix: (Double, Double) -> Unit = { _, _ -> }): Double {
        var s = startS; var a = startAlong
        val end = times.lengthM * toFraction
        while (a < end) {
            // seconds for the next 5 m at the walker's speed
            val p = times.between(a, a + 5)
            s += (0 until 3).sumOf { p[it] * f[it] }
            a += 5
            val pt = line.pointAt(a)
            trip.onFix(s, 1_000_000L + (s * 1000).toLong(), pt.lat, pt.lon, a, true)
            onFix(s, a)
        }
        return s
    }

    @Test fun walkingLikeTheModelLearnsNothing() {
        val trip = TripPace(); trip.setLine(times, 0.0)
        walk(trip, Factors.ONE, 0.5)
        val f = trip.factors()
        assertEquals(1.0, f.up, 0.03); assertEquals(1.0, f.down, 0.03)
        assertEquals(times.remainingFrom(times.lengthM / 2), trip.rawToGoS()!!, 60.0)
    }

    @Test fun fastClimberIsLearnedPerGroup() {
        val trip = TripPace(); trip.setLine(times, 0.0)
        val me = Factors(down = 1.4, flat = 1.0, up = 0.75) // strong climber, bad knees
        val s = walk(trip, me, 0.5)
        val f = trip.factors()
        assertTrue("$f", f.up < 0.85)
        // the descent is not walked yet: it gets half of the trip factor, not the climb factor
        assertTrue("$f", f.down > f.up && f.down < 1.0)
        // then the descent teaches its own factor
        walk(trip, me, 1.0, startS = s, startAlong = times.lengthM / 2)
        assertTrue("${trip.factors()}", trip.factors().down > 1.15)
        // shown values exist and are rounded
        val snap = trip.snapshot()
        assertNotNull(snap.learned)
    }

    @Test fun learningBeatsTheStaticModelAtHalfWay() {
        val me = Factors(0.8, 0.8, 0.8)
        val trip = TripPace(); trip.setLine(times, 0.0)
        val s = walk(trip, me, 0.5)
        val truth = (0 until 3).sumOf { me[it] * times.between(times.lengthM / 2, times.lengthM)[it] }
        val learned = abs(trip.rawToGoS()!! / truth - 1)
        val static = abs(times.remainingFrom(times.lengthM / 2) / truth - 1)
        assertTrue("learned $learned static $static after $s s", learned < static)
    }

    @Test fun breaksAreNotMovingTimeAndNotLearned() {
        val trip = TripPace(); trip.setLine(times, 0.0)
        val s = walk(trip, Factors.ONE, 0.3)
        val before = trip.factors()
        val stop = line.pointAt(times.lengthM * 0.3 + 5)
        for (k in 1..240) trip.onFix(s + k * 5.0, 1_000_000L + ((s + k * 5) * 1000).toLong(), stop.lat, stop.lon, times.lengthM * 0.3 + 5, true)
        assertTrue(trip.snapshot().breakS > 19 * 60)
        val end = walk(trip, Factors.ONE, 0.6, startS = s + 1200, startAlong = times.lengthM * 0.3 + 5)
        val after = trip.factors()
        assertEquals(before.up, after.up, 0.05) // the 20 min stop did not make us "slow"
        assertEquals(end, trip.movingS() + trip.snapshot().breakS, 1e-6)
        assertEquals(end - 1200, trip.movingS(), 60.0)
    }

    @Test fun offRouteIsNotLearned() {
        val trip = TripPace(); trip.setLine(times, 0.0)
        var s = walk(trip, Factors.ONE, 0.2)
        // 30 min off the line (a detour), then back where we left it
        val p = line.pointAt(times.lengthM * 0.2)
        for (k in 1..360) { s += 5; trip.onFix(s, (s * 1000).toLong(), p.lat + 0.002 + k * 1e-6, p.lon, null, false) }
        walk(trip, Factors.ONE, 0.4, startS = s, startAlong = times.lengthM * 0.2)
        assertEquals(1.0, trip.factors().up, 0.05)
    }

    @Test fun calmDisplay() {
        val trip = TripPace(); trip.setLine(times, 0.0)
        var lastArr: Long? = null
        var maxJump = 0.0
        // walks fast for a while then very slowly: the raw estimate moves, the shown arrival only in small steps
        val s = walk(trip, Factors(0.6, 0.6, 0.6), 0.3) { _, _ ->
            val a = trip.snapshot().arrivalMs!!
            lastArr?.let { maxJump = maxOf(maxJump, abs(a - it) / 1000.0) }
            lastArr = a
        }
        walk(trip, Factors(1.8, 1.8, 1.8), 0.6, startS = s, startAlong = times.lengthM * 0.3) { _, _ ->
            val a = trip.snapshot().arrivalMs!!
            lastArr?.let { maxJump = maxOf(maxJump, abs(a - it) / 1000.0) }
            lastArr = a
        }
        // 1 min + 2 % of at most ~3 h per update
        assertTrue("$maxJump", maxJump <= 60 + 0.02 * 3 * 3600 + 1)
        // a new line resets the limit once
        trip.setLine(RouteTimes.of(pts.take(30), Activity.HIKE), 0.0)
        trip.tick(100_000.0, 200_000_000L)
        assertNotNull(trip.snapshot().toGoS)
        trip.setLine(null, null)
        assertNull(trip.snapshot().toGoS)
    }

    @Test fun jsonRoundTripAndPriorChange() {
        val trip = TripPace(Factors.ONE * 1.25); trip.setLine(times, 0.0)
        walk(trip, Factors(1.0, 1.0, 1.0), 0.5)
        val copy = TripPace.fromJson(org.json.JSONObject(trip.toJson().toString()))
        copy.setLine(times, times.lengthM / 2)
        assertEquals(trip.factors().up, copy.factors().up, 1e-9)
        assertEquals(trip.movingS(), copy.movingS(), 1e-9)
        // the walker was "Normal" while the setting said Relaxed: learned ~0.8 relative to the prior
        assertTrue("${trip.factors()}", trip.factors().up < 0.9)
        // switching the setting to Normal keeps the absolute learning: factor relative to the new prior ~1
        copy.setPrior(Factors.ONE)
        assertTrue("${copy.factors()}", copy.factors().up > 0.9)
        assertEquals(0.0, TripPace.fromJson(null).movingS(), 0.0)
        assertEquals(0.0, TripPace.fromJson(org.json.JSONObject("{\"prior\":[0,0,0]}")).movingS(), 0.0)
    }

    @Test fun tourTimes() {
        val trip = TripPace(); trip.setLine(times, 0.0)
        val s = walk(trip, Factors(1.1, 1.1, 1.1))
        val t = trip.tourTimes(s)
        assertEquals(s, t.movingS + t.breakS, 1e-6)
        assertTrue(t.plannedS!! < t.neededS!!)
        assertEquals(0.0, TripPace().tourTimes(100.0).breakS, 0.0)
        assertNull(TripPace().tourTimes(100.0).plannedS)
    }

    @Test fun unknownPositionOnANewLineIsNotOnRouteNotTheWholeLine() {
        val trip = TripPace(); trip.setLine(times, null)
        trip.tick(10.0, 1_000_000L)
        assertEquals(EstimateState.NOT_ON_ROUTE, trip.snapshot().state)
        assertNull(trip.snapshot().toGoS)
        val p = line.pointAt(3000.0)
        trip.onFix(11.0, 1_001_000L, p.lat, p.lon, 3000.0, true)
        assertEquals(EstimateState.OK, trip.snapshot().state)
        assertEquals(TimeEstimate.round(times.remainingFrom(3000.0)), trip.snapshot().toGoS!!, 1.0)
    }

    @Test fun walkingAsShownDoesNotCreepTheArrival() {
        // field test: at a steady pace the arrival crept later every 30 s between the 100 m updates
        val trip = TripPace(); trip.setLine(times, 0.0)
        val arrivals = ArrayList<Long>()
        walk(trip, Factors.ONE, 0.6) { _, a -> if (a % 500 < 5) trip.snapshot().arrivalMs?.let { arrivals += it } }
        // never later (no break taken: the 10 % allowance makes it a little earlier, which is right)
        val later = (arrivals.zipWithNext { a, b -> b - a }.max()) / 60_000.0
        assertTrue("arrival moved $later min later at the model's pace", later <= 1.0)
        assertTrue(arrivals.last() <= arrivals.first())
    }

    @Test fun movingWithoutProgressPausesTheEstimate() {
        val trip = TripPace(); trip.setLine(times, 0.0)
        val s = walk(trip, Factors.ONE, 0.2)
        // 3 min walking 150 m beside the line while its progress stays (the matcher lost the way)
        val a = times.lengthM * 0.2
        for (k in 1..180) {
            val p = line.pointAt(a)
            trip.onFix(s + k, 1_000_000L + ((s + k) * 1000).toLong(), p.lat + k * 0.0000075, p.lon, a, true)
        }
        assertEquals(EstimateState.PAUSED, trip.snapshot().state)
        assertEquals(PauseReason.NO_PROGRESS, trip.snapshot().pausedBecause)
        assertNotNull(trip.snapshot().toGoS) // the last value stays (shown greyed)
    }

    /**
     * Field 2026-10-22 15:49: standing at the Taser lift top station (stored points 0.03–0.1 m/s) gave "moving for
     * 2 min but no progress". GPS jitter (and one wild fix) is not movement: the positions around the start and the
     * end of the window are compared, not two single fixes.
     */
    @Test fun gpsJitterWhileStandingIsNotMoving() {
        val trip = TripPace(); trip.setLine(times, 0.0)
        val s = walk(trip, Factors.ONE, 0.2)
        val a = times.lengthM * 0.2
        val p = line.pointAt(a)
        val m = 1.0 / 111_000 // ~1 m in degrees of latitude
        for (k in 1..200) {
            // ±18 m jitter around the spot; every 50th fix (and the last) 70 m off
            val off = if (k % 50 == 0 || k == 200) 70.0 else ((k * 7) % 37 - 18).toDouble()
            trip.onFix(s + k, 1_000_000L + ((s + k) * 1000).toLong(), p.lat + off * m, p.lon + ((k * 13) % 11 - 5) * m, a, true)
            assertTrue("fix $k: ${trip.snapshot().pausedBecause}", trip.snapshot().pausedBecause != PauseReason.NO_PROGRESS)
        }
    }

    @Test fun noGpsPausesButABreakDoesNot() {
        val trip = TripPace(); trip.setLine(times, 0.0)
        val s = walk(trip, Factors.ONE, 0.2)
        trip.tick(s + 130, 1_000_000L + ((s + 130) * 1000).toLong())
        assertEquals(PauseReason.NO_GPS, trip.snapshot().pausedBecause)
        // standing still with fixes (a break) for 10 min: estimate OK, "to go" unchanged
        val trip2 = TripPace(); trip2.setLine(times, 0.0)
        val s2 = walk(trip2, Factors.ONE, 0.2)
        val toGo = trip2.snapshot().toGoS
        val p = line.pointAt(times.lengthM * 0.2)
        for (k in 1..600 step 5) trip2.onFix(s2 + k, 1_000_000L + ((s2 + k) * 1000).toLong(), p.lat, p.lon, times.lengthM * 0.2, true)
        assertEquals(EstimateState.OK, trip2.snapshot().state)
        assertEquals(toGo, trip2.snapshot().toGoS)
    }
}

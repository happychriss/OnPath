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
        val trip = TripPace(); trip.setLine(times)
        walk(trip, Factors.ONE, 0.5)
        val f = trip.factors()
        assertEquals(1.0, f.up, 0.03); assertEquals(1.0, f.down, 0.03)
        assertEquals(times.remainingFrom(times.lengthM / 2), trip.rawToGoS()!!, 60.0)
    }

    @Test fun fastClimberIsLearnedPerGroup() {
        val trip = TripPace(); trip.setLine(times)
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
        val trip = TripPace(); trip.setLine(times)
        val s = walk(trip, me, 0.5)
        val truth = (0 until 3).sumOf { me[it] * times.between(times.lengthM / 2, times.lengthM)[it] }
        val learned = abs(trip.rawToGoS()!! / truth - 1)
        val static = abs(times.remainingFrom(times.lengthM / 2) / truth - 1)
        assertTrue("learned $learned static $static after $s s", learned < static)
    }

    @Test fun breaksAreNotMovingTimeAndNotLearned() {
        val trip = TripPace(); trip.setLine(times)
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
        val trip = TripPace(); trip.setLine(times)
        var s = walk(trip, Factors.ONE, 0.2)
        // 30 min off the line (a detour), then back where we left it
        val p = line.pointAt(times.lengthM * 0.2)
        for (k in 1..360) { s += 5; trip.onFix(s, (s * 1000).toLong(), p.lat + 0.002 + k * 1e-6, p.lon, null, false) }
        walk(trip, Factors.ONE, 0.4, startS = s, startAlong = times.lengthM * 0.2)
        assertEquals(1.0, trip.factors().up, 0.05)
    }

    @Test fun calmDisplay() {
        val trip = TripPace(); trip.setLine(times)
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
        trip.setLine(RouteTimes.of(pts.take(30), Activity.HIKE))
        trip.tick(100_000.0, 200_000_000L)
        assertNotNull(trip.snapshot().toGoS)
        trip.setLine(null)
        assertNull(trip.snapshot().toGoS)
    }

    @Test fun jsonRoundTripAndPriorChange() {
        val trip = TripPace(Factors.ONE * 1.25); trip.setLine(times)
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
        val trip = TripPace(); trip.setLine(times)
        val s = walk(trip, Factors(1.1, 1.1, 1.1))
        val t = trip.tourTimes(s)
        assertEquals(s, t.movingS + t.breakS, 1e-6)
        assertTrue(t.plannedS!! < t.neededS!!)
        assertEquals(0.0, TripPace().tourTimes(100.0).breakS, 0.0)
        assertNull(TripPace().tourTimes(100.0).plannedS)
    }
}

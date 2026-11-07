package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner field ride 2026-11-07 (bike, tour 217, events + GPS points from the Pixel): "Jetzt abbiegen" came too late, a due
 * turn must never be swallowed, and the arrival should say where the goal is. The "now" is timed by when its turn word is
 * *heard* – the fix's age, one fix a second, the speech engine's start and the word before it counted
 * (`docs/design/voice-guidance.md` §9).
 */
class VoiceNowTest {
    private val north = 0.0000089932 // degrees of latitude per metre

    /** A straight line north, a point every 10 m, junctions at the given metres. */
    private fun line(lengthM: Int, junctions: Map<Int, JunctionCue> = emptyMap()): List<GeoPoint> =
        (0..lengthM step 10).map { m -> GeoPoint(46.0 + m * north, 11.0, turn = junctions[m]) }

    private fun at(m: Double, eastM: Double = 0.0) = GeoPoint(46.0 + m * north, 11.0 + eastM / (111_320.0 * Math.cos(Math.toRadians(46.0))))

    /**
     * Ride at [speed] for real: one fix a second, [fixAgeMs] old when it comes; returns, per "now" sentence, the seconds
     * before the turn at which its turn word is heard ([speechMs] start + "Gleich").
     */
    private fun heardBefore(l: List<GeoPoint>, speed: Double, fixAgeMs: Long, speechMs: Long): List<Pair<Double, Double>> {
        val g = VoiceGuide()
        val out = ArrayList<Pair<Double, Double>>()
        var t = 0.0
        val end = (l.size - 1) * 10.0
        while (speed * (t - fixAgeMs / 1000.0) < end) {
            val fix = (speed * (t - fixAgeMs / 1000.0)).coerceAtLeast(0.0)
            val p = g.update(VoiceGuide.Input((t * 1000).toLong(), l, 1, fix, onRoute = true, speedMps = speed, bike = true,
                fixAgeMs = fixAgeMs, speechStartMs = speechMs))
            if (p is Phrase.Junction && p.inM == 0.0) {
                val heardAt = t + speechMs / 1000.0 + VoiceGuide.NOW_WORD_S
                out += p.atM!! to (p.atM!! / speed - heardAt)
            }
            t += 1.0
        }
        return out
    }

    /**
     * Tour 217 (15 km/h): "Gleich …" was triggered 5.1–6.2 s and heard ~4–5 s before the turn – meant 8 s, but measured
     * from a fix ~1 s old with one fix a second and the speech start not counted. Now the turn word is heard ~6 s before
     * at every speed, also with a slow fix and a slow voice (the first sentence after a pause: 0.9 s on the Pixel).
     */
    @Test fun theNowIsHeardAboutSixSecondsBeforeTheTurn_theDelaysCounted() {
        val l = line(3000, mapOf(700 to JunctionCue.LEFT, 1500 to JunctionCue.RIGHT, 2400 to JunctionCue.LEFT))
        for (kmh in listOf(12.0, 15.0, 20.0, 25.0, 30.0)) for ((age, speech) in listOf(0L to 200L, 1000L to 900L, 1500L to 1500L)) {
            val heard = heardBefore(l, kmh / 3.6, age, speech)
            assertEquals("$kmh km/h age $age speech $speech: $heard", 3, heard.size)
            // (slow, the minimum distance rules: 30 m at 12 km/h are 9 s)
            val most = maxOf(8.5, VoiceGuide.BIKE_NOW_MIN_M / (kmh / 3.6) + 1.5)
            for ((m, s) in heard) assertTrue("$kmh km/h age $age speech $speech: the turn at $m heard ${"%.1f".format(s)} s before",
                s in 5.0..most)
        }
    }

    /** Slow (pushing, 7 km/h): at least [VoiceGuide.BIKE_NOW_MIN_M] before – one can still picture "Gleich". */
    @Test fun slowTheNowKeepsItsMinimumDistance() {
        val l = line(1500, mapOf(700 to JunctionCue.LEFT))
        val heard = heardBefore(l, 2.0, 1000, 600)
        assertEquals(1, heard.size)
        assertTrue("$heard", heard[0].second * 2.0 >= VoiceGuide.BIKE_NOW_MIN_M - 0.5)
    }

    /**
     * Tour 217, 12:32:29: "Ziel erreicht." 31 m before the goal and 9 s before the line's last right turn, which was never
     * said. On the bike the arrival waits until within 15 m – the turn before it is said first.
     */
    @Test fun onTheBikeArrivedWaitsUntilThere_theLastTurnIsSaidFirst() {
        val l = line(700, mapOf(690 to JunctionCue.RIGHT))
        val goal = at(700.0, 8.0)
        val stops = listOf(NavStop(l.first(), 0.0), NavStop(goal, 700.0, "Olive"))
        val g = VoiceGuide()
        val base = VoiceGuide.Input(0, l, 1, 600.0, onRoute = true, speedMps = 4.1, bike = true, stops = stops)
        g.update(base)
        fun fix(m: Double, t: Long) = base.copy(nowMs = t, alongM = m, here = at(m), headingDeg = 0.0)
        val said = ArrayList<Pair<Double, Phrase>>()
        var t = 30_000L
        var m = 640.0
        // the arrival gate fires 45 m before the end
        g.update(fix(m, t))?.let { said += m to it }
        assertNull("not yet: 60 m to go", g.arrive(fix(655.0, t + 3_600)))
        assertTrue(g.arrivalPending())
        m = 655.0; t += 3_600
        while (m < 705 && g.arrivalPending()) { m += 4.1; t += 1000; g.update(fix(m, t))?.let { said += m to it } }
        val turn = said.indexOfFirst { (_, p) -> p is Phrase.Junction && p.turn == Turn.RIGHT && p.inM == 0.0 }
        val arrived = said.indexOfFirst { (_, p) -> p == Phrase.Arrived || p is Phrase.ArrivedSide }
        assertTrue("$said", turn >= 0 && arrived > turn)
        assertTrue("arrived within 15 m of the goal or the end: ${said[arrived].first}", 700 - said[arrived].first <= 15.0)
        assertTrue(!g.arrivalPending())
    }

    /** On foot the arrival is said when the gate fires, as before. */
    @Test fun onFootArrivedAtOnce() {
        val l = line(700)
        val g = VoiceGuide()
        val base = VoiceGuide.Input(0, l, 1, 600.0, onRoute = true, speedMps = 1.3, bike = false)
        g.update(base)
        assertEquals(Phrase.Arrived, g.arrive(base.copy(nowMs = 60_000, alongM = 660.0)))
    }

    /** Standing (a red light, at the goal's gate) or 20 s after the gate: said then; a passed goal too. */
    @Test fun onTheBikeArrivedWhenStoppingOrAfterAWhile() {
        val l = line(700)
        val base = VoiceGuide.Input(0, l, 1, 600.0, onRoute = true, speedMps = 4.0, bike = true)
        VoiceGuide().run {
            update(base)
            assertNull(arrive(base.copy(nowMs = 30_000, alongM = 660.0)))
            assertEquals(Phrase.Arrived, update(base.copy(nowMs = 31_000, alongM = 661.0, quiet = true)))
        }
        VoiceGuide().run {
            update(base)
            assertNull(arrive(base.copy(nowMs = 30_000, alongM = 660.0)))
            assertNull(update(base.copy(nowMs = 40_000, alongM = 661.0)))
            assertEquals(Phrase.Arrived, update(base.copy(nowMs = 30_000 + VoiceGuide.ARRIVE_WAIT_MS, alongM = 662.0)))
        }
        // "Ziel erreicht" once: the next fixes say nothing more
        VoiceGuide().run {
            update(base)
            assertEquals(Phrase.Arrived, arrive(base.copy(nowMs = 30_000, alongM = 690.0)))
            assertNull(update(base.copy(nowMs = 31_000, alongM = 694.0)))
            assertNull(arrive(base.copy(nowMs = 32_000, alongM = 695.0)))
        }
    }

    /** Owner 2026-11-07: "Das Ziel ist rechts von dir" – from the heading ridden, only when moving; straight on: plain. */
    @Test fun arrivedSaysWhereTheGoalIs() {
        val l = line(700)
        fun arrivedWith(goal: GeoPoint, heading: Double?): Phrase? {
            val g = VoiceGuide()
            val stops = listOf(NavStop(l.first(), 0.0), NavStop(goal, 700.0))
            val base = VoiceGuide.Input(0, l, 1, 600.0, onRoute = true, speedMps = 4.0, bike = true, stops = stops)
            g.update(base)
            return g.arrive(base.copy(nowMs = 30_000, alongM = 690.0, here = at(690.0), headingDeg = heading))
        }
        assertEquals(Phrase.ArrivedSide(GoalSide.RIGHT), arrivedWith(at(690.0, 12.0), 0.0))
        assertEquals(Phrase.ArrivedSide(GoalSide.HALF_LEFT), arrivedWith(at(700.0, -8.0), 0.0))
        assertEquals(Phrase.Arrived, arrivedWith(at(702.0, 1.0), 0.0))
        assertEquals("standing: no heading, no side", Phrase.Arrived, arrivedWith(at(690.0, 12.0), null))
        assertEquals("riding the other way: behind", Phrase.ArrivedSide(GoalSide.BEHIND), arrivedWith(at(702.0), 180.0))
    }

    /** "Ziel in 50 Metern, schräg rechts." after the last turn, once – only when the goal lies off to a side. */
    @Test fun theGoalAheadOffToASideIsSaidOnce() {
        val l = line(1000, mapOf(300 to JunctionCue.LEFT))
        for ((east, expect) in listOf(40.0 to GoalSide.HALF_RIGHT, 0.0 to null)) {
            val stops = listOf(NavStop(l.first(), 0.0), NavStop(at(1000.0, east), 1000.0))
            val g = VoiceGuide()
            val said = ArrayList<Pair<Int, Phrase>>()
            for (m in 0..990 step 5) {
                g.update(VoiceGuide.Input(m * 200L, l, 1, m.toDouble(), onRoute = true, speedMps = 5.0, bike = true, stops = stops,
                    here = at(m.toDouble()), headingDeg = 0.0))?.let { said += m to it }
            }
            val goal = said.filter { it.second is Phrase.GoalAhead }
            if (expect == null) assertTrue("straight on: no approach sentence – $said", goal.isEmpty())
            else {
                assertEquals("$said", 1, goal.size)
                val p = goal[0].second as Phrase.GoalAhead
                assertEquals(expect, p.side)
                assertTrue("said ~12 s ahead (${goal[0].first} m)", 1000 - goal[0].first in 40..70)
            }
        }
    }
}

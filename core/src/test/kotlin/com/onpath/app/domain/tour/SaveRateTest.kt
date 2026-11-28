package com.onpath.app.domain.tour

import com.onpath.app.navigation.JunctionCues
import com.onpath.app.navigation.OffRouteDetector
import com.onpath.app.navigation.VoiceGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * „Akku sparen“ with 30 s on foot / 10 s by bike (Chriss 2026-11-28 10:55) – but every second where it matters (captain):
 * (a) the junction cue / the first sentence is never later than without saving; (b) leaving the route is confirmed within
 * the detector's own time once a fix shows it off – what the slow rate costs is only the wait for that one fix.
 * Simulated walks along a line with a junction at [J]; the next fix comes after the rate [SaveRate] picks at each fix.
 */
class SaveRateTest {
    private val J = 5_000.0
    private val junctions = doubleArrayOf(J)

    /** The distance before the junction at the first fix that tells it, walking at [v] with saving [on]. */
    private fun toldAt(v: Double, bike: Boolean, on: Boolean, told: Double, startM: Double = 0.0, phaseS: Double = 0.0): Double {
        var along = startM + v * phaseS
        while (true) {
            val d = J - along
            if (d <= told) return d
            val fast = SaveRate.fast(along, 0.0, junctions, v, bike)
            val stepS = BatterySave.rateMs(on, bike, fast) / 1000.0
            along += v * stepS
        }
    }

    @Test fun aTheCueIsNeverLaterThanWithoutSaving() {
        // every speed, every phase of the slow fixes: the fix that tells it is within one second's walk of the told distance
        for ((bike, speeds) in listOf(false to listOf(0.6, 1.0, 1.4, 1.8, 2.5), true to listOf(3.0, 5.0, 7.0, 9.0, 11.0, 14.0))) for (v in speeds) {
            val told = if (bike) SaveRate.firstToldM(v, true) else JunctionCues.hikeLeadM(v)
            for (phase in 0 until 30) {
                val saving = toldAt(v, bike, on = true, told = told, phaseS = phase.toDouble())
                assertTrue("bike=$bike v=$v phase=$phase: told at $saving m, needs ≥ ${told - v} m", saving >= told - v * 1.0 - 1e-9)
            }
        }
    }

    @Test fun aBikeTheNowSentenceAndTheVibrationComeAfterTheEarlyOneSoTheyAreFastToo() {
        for (v in listOf(3.0, 7.0, 14.0)) {
            val now = (v * VoiceGuide.BIKE_NOW_S).coerceIn(VoiceGuide.BIKE_NOW_MIN_M, VoiceGuide.BIKE_NOW_MAX_M)
            assertTrue(SaveRate.firstToldM(v, true) >= now)
            assertTrue(SaveRate.firstToldM(v, true) >= JunctionCues.bikeLeadM(v))
        }
    }

    @Test fun aUnknownSpeedPlansForTheFastest() {
        assertTrue(SaveRate.aheadM(null, bike = true) >= SaveRate.aheadM(14.0, bike = true))
        assertTrue(SaveRate.aheadM(null, bike = false) >= SaveRate.aheadM(3.0, bike = false))
    }

    @Test fun aPastTheJunctionItStaysFastForAWhile() {
        assertTrue(SaveRate.fast(J + 50.0, 0.0, junctions, 1.4, bike = false))
        assertFalse(SaveRate.fast(J + SaveRate.HIKE_BEHIND_M + 1, 0.0, junctions, 1.4, bike = false))
        assertTrue(SaveRate.fast(J + 120.0, 0.0, junctions, 7.0, bike = true))
    }

    @Test fun bOffTheRouteOrNotOnItIsAlwaysFast() {
        assertTrue("a fix 21 m beside the line", SaveRate.fast(1_000.0, SaveRate.OFF_M + 1, junctions, 1.4, bike = false))
        assertTrue("not on the route (not matched, confirmed off)", SaveRate.fast(null, null, junctions, 1.4, bike = false))
        assertFalse("on the line between junctions: slow", SaveRate.fast(1_000.0, 5.0, junctions, 1.4, bike = false))
        // below the warning distance (40 m by default) – so the detector's leaving starts on fast fixes
        assertTrue(SaveRate.OFF_M < 40.0)
    }

    @Test fun bTheWorstCaseForLeavingBetweenJunctions() {
        // walking straight off the line at 1.4 m/s right after a slow fix: the next fix comes 30 s later, 42 m off – from
        // there every second, and the detector confirms after its own CONFIRM_MS. Worst case: one slow interval later
        // than without saving (by bike: 10 s). Said honestly in R-10.
        val v = 1.4
        val firstOffFixM = v * BatterySave.HIKE_MS / 1000.0
        assertEquals(42.0, firstOffFixM, 0.01)
        val worstExtraS = BatterySave.HIKE_MS / 1000.0
        assertEquals(30.0, worstExtraS, 0.0)
        assertEquals(15_000L, OffRouteDetector.CONFIRM_MS)
    }

    @Test fun noRouteTheSlowRateHolds() {
        assertEquals(BatterySave.HIKE_MS, BatterySave.rateMs(on = true, bike = false, fast = false))
        assertEquals(BatterySave.BIKE_MS, BatterySave.rateMs(on = true, bike = true, fast = false))
        assertEquals(BatterySave.FAST_MS, BatterySave.rateMs(on = false, bike = true, fast = false))
    }
}

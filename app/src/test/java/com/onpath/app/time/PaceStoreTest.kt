package com.onpath.app.time

import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceStoreTest {

    private val p = doubleArrayOf(1800.0, 1200.0, 3600.0) // compared seconds down / flat / up
    private fun a(k: Double) = DoubleArray(3) { p[it] * k }

    @Test fun shortToursDoNotTeach() {
        val s = PaceStore.EMPTY.update(Activity.HIKE, Factors(0.8, 0.8, 0.8), doubleArrayOf(600.0, 300.0, 600.0),
            doubleArrayOf(500.0, 300.0, 500.0), null, 1800.0, 0.0)
        assertSame(PaceStore.EMPTY, s)
        assertNull(s.factors(Activity.HIKE))
    }

    @Test fun learnsPerActivityAndGroup() {
        var s = PaceStore.EMPTY.update(Activity.HIKE, Factors(down = 1.2, flat = 1.0, up = 0.85), p, a(0.95), null, 7000.0, 900.0)
        val f = s.factors(Activity.HIKE)!!
        assertEquals(1.2, f.down, 1e-9); assertEquals(0.85, f.up, 1e-9)
        assertNull(s.factors(Activity.MTB))
        assertEquals(1, s.entry(Activity.HIKE)!!.tours)
        // the next tour started from these factors and learned "as expected" (1.0): stays close, weighted by time
        s = s.update(Activity.HIKE, Factors.ONE, p, a(1.0), f, 7000.0, 600.0)
        assertEquals(1.2, s.factors(Activity.HIKE)!!.down, 1e-9)
        assertEquals(2, s.entry(Activity.HIKE)!!.tours)
        // a group not walked on a tour does not change
        s = s.update(Activity.HIKE, Factors(0.5, 0.5, 0.7), doubleArrayOf(0.0, 0.0, 3600.0), doubleArrayOf(0.0, 0.0, 2520.0),
            s.factors(Activity.HIKE), 3600.0, 0.0)
        assertEquals(1.2, s.factors(Activity.HIKE)!!.down, 1e-9)
        assertTrue(s.factors(Activity.HIKE)!!.up < 0.85)
        assertTrue(s.entry(Activity.HIKE)!!.breakRatio in 0.05..0.15)
        assertEquals(listOf(Activity.HIKE), s.learned().map { it.first })
    }

    @Test fun olderToursFade() {
        var s = PaceStore.EMPTY
        repeat(3) { s = s.update(Activity.HIKE, Factors(1.3, 1.3, 1.3), p, a(1.3), s.factors(Activity.HIKE)?.let { Factors.ONE }, 5000.0, 0.0) }
        repeat(10) { s = s.update(Activity.HIKE, Factors.ONE, p, a(0.9), Factors(0.9, 0.9, 0.9) * (1.0 / 1.0), 5000.0, 0.0) }
        // 10 newer tours at ~0.9 outweigh 3 old ones at 1.3
        assertTrue("${s.factors(Activity.HIKE)}", s.factors(Activity.HIKE)!!.up < 1.0)
    }

    @Test fun resetAndJson() {
        val s = PaceStore.EMPTY.update(Activity.EBIKE, Factors(0.9, 0.95, 1.1), p, a(1.0), null, 5000.0, 300.0)
        val back = PaceStore.fromJson(s.toJson())
        assertEquals(s.factors(Activity.EBIKE), back.factors(Activity.EBIKE))
        assertEquals(1, back.entry(Activity.EBIKE)!!.tours)
        assertNull(back.reset(Activity.EBIKE).factors(Activity.EBIKE))
        assertNull(PaceStore.fromJson(null).factors(Activity.HIKE))
        assertNull(PaceStore.fromJson("{broken").factors(Activity.HIKE))
        assertNull(PaceStore.fromJson("{\"HIKE\":{\"f\":[0,1,1],\"n\":3}}").factors(Activity.HIKE))
        // stored values are clamped
        assertEquals(2.0, PaceStore.fromJson("{\"HIKE\":{\"f\":[9,1,1],\"n\":3}}").factors(Activity.HIKE)!!.down, 0.0)
    }

    @Test fun planFactorsUseThePaceAndTheLearnedFactors() {
        val s = com.onpath.app.data.AppSettings(hikePace = Pace.RELAXED, bikePace = Pace.FIT)
        assertEquals(Factors(1.25, 1.25, 1.25), TimeEstimate.planFactors(Activity.HIKE, s, null))
        assertEquals(Factors(0.8, 0.8, 0.8), TimeEstimate.planFactors(Activity.MTB, s, null))
        assertEquals(1.25 * 0.9, TimeEstimate.planFactors(Activity.HIKE, s, Factors(0.9, 1.0, 1.0)).down, 1e-9)
    }
}

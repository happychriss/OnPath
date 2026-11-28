package com.onpath.app.recording

import com.onpath.app.domain.tour.BatterySave
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** „Akku sparen“ (Chriss 2026-11-28, R-10): the moving GPS rate follows the switch and the place at the next tick; resting keeps its own rate. */
class BatterySaveRateTest {
    @After fun off() { BatterySave.on = false; BatterySave.bike = false; BatterySave.fast = false }

    @Test fun switchingWhileMovingChangesTheRateOnce() {
        val w = RestWatch()
        assertEquals(RestWatch.NORMAL_MS, w.intervalMs)
        BatterySave.on = true
        assertEquals(RestWatch.Change.RATE, w.onTick(1_000, still = false))
        assertEquals("on foot: 30 s", BatterySave.HIKE_MS, w.intervalMs)
        assertNull("only once", w.onTick(2_000, still = false))
        BatterySave.bike = true
        assertEquals(RestWatch.Change.RATE, w.onTick(3_000, still = false))
        assertEquals("by bike: 10 s", BatterySave.BIKE_MS, w.intervalMs)
        BatterySave.fast = true
        assertEquals("a junction near: every second", RestWatch.Change.RATE, w.onTick(4_000, still = false))
        assertEquals(BatterySave.FAST_MS, w.intervalMs)
        BatterySave.on = false; BatterySave.fast = false
        assertEquals(RestWatch.NORMAL_MS, w.intervalMs)
    }

    @Test fun gpsCountsAsLostOnlyAfterTwoSlowIntervals() {
        assertEquals(8_000L, RestWatch.lostAfterMs(resting = false))
        BatterySave.on = true
        assertEquals(65_000L, RestWatch.lostAfterMs(resting = false))
    }

    @Test fun restingKeepsItsSlowRate() {
        val w = RestWatch()
        var t = 0L
        while (!w.resting) { w.onTick(t, still = true); t += 1_000 }
        BatterySave.on = true
        assertNull(w.onTick(t, still = true))
        assertEquals(RestWatch.REST_MS, w.intervalMs)
        assertEquals("moving again: the saving rate", RestWatch.Change.MOVING, w.onTick(t + 1_000, still = false))
        assertEquals(BatterySave.HIKE_MS, w.intervalMs)
    }
}

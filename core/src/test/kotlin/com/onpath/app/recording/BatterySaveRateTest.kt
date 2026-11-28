package com.onpath.app.recording

import com.onpath.app.domain.tour.BatterySave
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** „Akku sparen“ (Chriss 2026-11-28, R-10): the moving GPS rate follows the switch at once; resting keeps its slow rate. */
class BatterySaveRateTest {
    @After fun off() { BatterySave.on = false }

    @Test fun switchingWhileMovingChangesTheRateOnce() {
        BatterySave.on = false
        val w = RestWatch()
        assertEquals(RestWatch.NORMAL_MS, w.intervalMs)
        BatterySave.on = true
        assertEquals(RestWatch.Change.RATE, w.onTick(1_000, still = false))
        assertEquals(BatterySave.GPS_MS, w.intervalMs)
        assertNull("only once", w.onTick(2_000, still = false))
        BatterySave.on = false
        assertEquals(RestWatch.Change.RATE, w.onTick(3_000, still = false))
        assertEquals(RestWatch.NORMAL_MS, w.intervalMs)
    }

    @Test fun restingKeepsItsSlowRate() {
        BatterySave.on = false
        val w = RestWatch()
        var t = 0L
        while (!w.resting) { w.onTick(t, still = true); t += 1_000 }
        BatterySave.on = true
        assertNull(w.onTick(t, still = true))
        assertEquals(RestWatch.REST_MS, w.intervalMs)
        assertEquals("moving again: the saving rate", RestWatch.Change.MOVING, w.onTick(t + 1_000, still = false))
        assertEquals(BatterySave.GPS_MS, w.intervalMs)
    }
}

package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One style reload / one recalc per burst of changes, never while the user drags. */
class QuietGateTest {
    @Test fun nothingPendingNothingToDo() {
        assertNull(QuietGate(2_000, 10_000).waitMs(0, busy = false))
    }

    @Test fun aBurstGivesOneAction() {
        val g = QuietGate(2_000, 10_000)
        var actions = 0
        // a region joined every 500 ms for 3 s
        var t = 0L
        while (t <= 3_000) {
            g.changed(t)
            if (g.waitMs(t, false) == 0L) actions++
            t += 500
        }
        assertEquals(0, actions)
        assertEquals(1_500L, g.waitMs(3_500, false)) // 2 s after the last change
        assertEquals(0L, g.waitMs(5_000, false))
        assertNull(g.waitMs(5_001, false)) // done: reset
        assertFalse(g.pending)
    }

    @Test fun aLongBurstStillActsAfterTheMaxWait() {
        val g = QuietGate(2_000, 10_000)
        var t = 0L
        while (t < 10_000) { g.changed(t); assertTrue(g.waitMs(t, false)!! > 0); t += 1_000 }
        assertEquals(0L, g.waitMs(10_000, false))
    }

    @Test fun neverWhileBusyButNotForever() {
        val g = QuietGate(2_000, 10_000, maxBusyMs = 60_000)
        g.changed(0)
        assertEquals(QuietGate.BUSY_RECHECK_MS, g.waitMs(5_000, busy = true)) // dragging the map: wait
        assertEquals(QuietGate.BUSY_RECHECK_MS, g.waitMs(30_000, busy = true))
        assertEquals(0L, g.waitMs(30_100, busy = false)) // finger up: at once (quiet period long over)
        g.changed(100_000)
        assertEquals(0L, g.waitMs(160_000, busy = true)) // stuck "busy" (a minute): acts anyway
    }

    @Test fun resetDropsThePendingChange() {
        val g = QuietGate(2_000, 10_000)
        g.changed(0); g.reset()
        assertNull(g.waitMs(5_000, false))
    }
}

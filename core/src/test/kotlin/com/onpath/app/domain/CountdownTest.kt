package com.onpath.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner's Pixel 2026-11-27: loading a map said „etwa 5 s“, then longer, then 5 s again – now +10 s and only down. */
class CountdownTest {
    @Test fun theEstimateJumpsTheWindowOnlyCountsDown() {
        val c = Countdown()
        assertNull("no estimate yet", c.next(null, 0))
        // the steps' estimates as they came: 5 s (download), 20 s (join starts), 5 s, 15 s, 3 s …
        val est = listOf(5, 5, 20, 18, 5, 15, 12, 3, 2, 1)
        val shown = est.mapIndexed { i, e -> c.next(e, 1_000L + i * 1000L)!! }
        assertEquals("the first estimate + 10 s", 15, shown.first())
        for (i in 1 until shown.size) assertTrue("never up: $shown", shown[i] <= shown[i - 1])
        assertFalse(c.expired)
    }

    @Test fun pausedStandsAndRunningOutSaysLonger() {
        val c = Countdown()
        c.next(5, 0)                                   // 15 s
        assertEquals(15, c.next(null, 60_000, paused = true))   // offline a minute: stands
        assertEquals(10, c.next(30, 65_000))                    // a higher estimate never raises it
        c.next(30, 80_000)
        assertTrue("ran out while it goes on", c.expired)
        assertEquals(0, c.next(30, 81_000))
    }
}

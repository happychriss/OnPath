package com.onpath.app.plansync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review sync M8: a stuck outbox is said after three passes, never "synced just now" for ever. */
class StuckOutboxTest {
    @Test fun threePassesInARowWithItemsLeft() {
        var n = 0
        repeat(2) { n = StuckOutbox.next(n, left = 2) }
        assertFalse(StuckOutbox.stuck(n))
        n = StuckOutbox.next(n, left = 2)
        assertTrue(StuckOutbox.stuck(n))
        n = StuckOutbox.next(n, left = 0)
        assertEquals(0, n)
    }

    /** Pacing (field bug 2026-11-12): 110 plans go up 30 per pass – passes that send something are not "stuck". */
    @Test fun pacedPassesThatSendAreNotStuck() {
        var n = 0
        repeat(5) { n = StuckOutbox.next(n, left = 80, pushed = 30) }
        assertFalse(StuckOutbox.stuck(n))
        assertEquals(0, n)
    }
}

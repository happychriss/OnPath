package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseWatchTest {
    private val north = 0.0009 // ≈ 100 m of latitude

    @Test fun standingStillNeverWarns() {
        val w = PauseWatch()
        repeat(100) { assertFalse(w.onFix(46.6 + (it % 3) * 0.00005, 11.1, 8.0)) }
    }

    @Test fun walkingAwayWarnsOnce() {
        val w = PauseWatch()
        val warnings = (0..10).count { w.onFix(46.6 + it * north / 2, 11.1, 8.0) }
        assertEquals(1, warnings)
        assertTrue(w.warned)
    }

    @Test fun badFixesAreIgnoredAndResetStartsOver() {
        val w = PauseWatch()
        w.onFix(46.6, 11.1, 8.0)
        assertFalse(w.onFix(46.6 + 5 * north, 11.1, 200.0)) // a jump with poor accuracy
        assertTrue(w.onFix(46.6 + 2 * north, 11.1, 10.0))
        w.reset()
        assertFalse(w.onFix(46.6 + 2 * north, 11.1, 10.0)) // new anchor
        assertFalse(w.onFix(46.6 + 2.5 * north, 11.1, 10.0))
    }
}

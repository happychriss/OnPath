package com.onpath.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-01: "Navigation Stopp" (restaurant) = the phone is quiet. Owner 2026-11-03: Plan stays quiet – walking
 * or riding on with the app in Plan never brings the guidance back by itself; only "Navigation fortsetzen" does.
 */
class StopQuietTest {
    @Test fun planIsQuietUntilContinue() {
        val q = StopQuiet()
        assertFalse(q.quiet)
        q.set(stopped = true)
        assertTrue(q.quiet)
        q.set(stopped = false)
        assertFalse(q.quiet)
    }

    // no auto resume by walking off: StopQuiet has no fix input – only Continue ends the quiet (tests above)
}

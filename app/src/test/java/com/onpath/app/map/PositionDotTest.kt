package com.onpath.app.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opening the phone in Navigate: the dot is where I am, it does not glide from the old place (owner). */
class PositionDotTest {
    @Test fun firstPositionJumps() = assertTrue(PositionDot.jumps(0L, 5_000))
    @Test fun afterTheScreenWasLockedItJumps() = assertTrue(PositionDot.jumps(10_000, 70_000))
    @Test fun everySecondItGlidesSmoothly() = assertFalse(PositionDot.jumps(10_000, 11_000))
    @Test fun dotIsLargerThanMapLibresDefault() = assertTrue(PositionDot.MAX_SCALE > 1f && PositionDot.MIN_SCALE > 0.6f)

    /** Owner 2026-11-06: the GPS accuracy circle only in Navigate, never while planning. */
    @Test fun accuracyCircleOnlyInNavigate() {
        assertTrue(PositionDot.accuracyAlpha(navigate = true) > 0f)
        assertTrue(PositionDot.accuracyAlpha(navigate = false) == 0f)
    }
}

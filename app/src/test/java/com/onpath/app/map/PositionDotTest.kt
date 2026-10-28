package com.onpath.app.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opening the phone in Navigate: the dot is where I am, it does not glide from the old place (owner 2026-10-28). */
class PositionDotTest {
    @Test fun firstPositionJumps() = assertTrue(PositionDot.jumps(0L, 5_000))
    @Test fun afterTheScreenWasLockedItJumps() = assertTrue(PositionDot.jumps(10_000, 70_000))
    @Test fun everySecondItGlidesSmoothly() = assertFalse(PositionDot.jumps(10_000, 11_000))
    @Test fun dotIsLargerThanMapLibresDefault() = assertTrue(PositionDot.MAX_SCALE > 1f && PositionDot.MIN_SCALE > 0.6f)
}

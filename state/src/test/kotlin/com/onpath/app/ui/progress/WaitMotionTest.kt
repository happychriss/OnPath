package com.onpath.app.ui.progress

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** „Nur eine Sache bewegt sich“ (owner 2026-11-27): while a wait window is up, no other busy sign moves. */
class WaitMotionTest {
    @Test fun othersStandStillWhileAWindowIsUpAndMoveAgainAfter() {
        assertTrue(WaitMotion.othersMove())
        WaitMotion.opened()
        assertFalse("window up: the others stand still", WaitMotion.othersMove())
        WaitMotion.opened() // a second window for a frame
        WaitMotion.closed()
        assertFalse("one still up", WaitMotion.othersMove())
        WaitMotion.closed()
        assertTrue("closed: they move again", WaitMotion.othersMove())
        WaitMotion.closed() // never below zero
        assertTrue(WaitMotion.othersMove())
    }
}

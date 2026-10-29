package com.onpath.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two MainActivity instances at a cold start: the event log must name each screen and why it went. */
class ScreenLifecycleTest {
    @Test fun secondScreenWhileFirstAliveIsCounted() {
        val (a, before) = ScreenLifecycle.created()
        val (b, othersAtB) = ScreenLifecycle.created()
        assertEquals(before + 1, othersAtB)
        assertEquals(before + 1, ScreenLifecycle.destroyed(a))
        assertEquals(before, ScreenLifecycle.destroyed(b))
        val line = ScreenLifecycle.createdLine(b, 1, 42, root = true, restored = false, intent = "MAIN")
        assertTrue(line, line.contains("1 other screen(s) still alive"))
        assertFalse(ScreenLifecycle.createdLine(a, 0, 42, true, false, "MAIN").contains("alive"))
    }

    @Test fun destroyReasonIsNamed() {
        assertEquals("Screen #1 destroyed (configuration change)", ScreenLifecycle.destroyedLine(1, finishing = false, configChange = true, others = 0))
        assertEquals("Screen #1 destroyed (finished), 1 other screen(s) alive", ScreenLifecycle.destroyedLine(1, true, false, 1))
        assertEquals("Screen #2 destroyed (by the system)", ScreenLifecycle.destroyedLine(2, false, false, 0))
    }

    @Test fun intentTextShowsLaunchDetailsButNoContent() {
        val t = ScreenLifecycle.intentText("android.intent.action.MAIN", null, null,
            setOf("android.intent.category.LAUNCHER"), 0x10200000, fromHistory = false, sender = "com.android.shell")
        assertEquals("MAIN [LAUNCHER] flags 0x10200000 from com.android.shell", t)
        val v = ScreenLifecycle.intentText("android.intent.action.VIEW", "content", "text/xml", null, 0x100000, true, null)
        assertEquals("VIEW content: text/xml flags 0x100000 from recents", v)
    }
}

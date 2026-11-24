package com.onpath.app.ui

import com.onpath.app.ui.live.LiveSign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The share button's look and the pill per sharing state (owner 2026-11-24): off plain, live green, waiting grey; pill only waiting. */
class LiveShareLookTest {
    @Test fun buttonLookPerState() {
        assertEquals(ShareLook.PLAIN, LiveShareLook.button(LiveSign.NONE))
        assertEquals(ShareLook.GREEN, LiveShareLook.button(LiveSign.LIVE))
        assertEquals(ShareLook.GREY, LiveShareLook.button(LiveSign.WAITING))
    }

    @Test fun pillOnlyWhileWaiting() {
        assertFalse(LiveShareLook.pill(LiveSign.NONE))
        assertFalse(LiveShareLook.pill(LiveSign.LIVE))
        assertTrue(LiveShareLook.pill(LiveSign.WAITING))
    }

    @Test fun talkBackNamesTheState() {
        val names = LiveSign.values().map { LiveShareLook.description(it) }
        assertEquals("each state has its own TalkBack name", 3, names.toSet().size)
    }
}

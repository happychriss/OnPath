package com.onpath.app.map

import com.onpath.app.debug.Problems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field test 2026-10-22: 390 + 55 camera notes filled Diagnostics; they are trace events, not problems. */
class MapEventsTest {
    @Test fun cameraNotesAreTraceEventsNotProblems() {
        Problems.clear()
        repeat(390) { MapEvents.followingStopped("map gesture (pan / fling / turn)") }
        repeat(55) { MapEvents.cameraModeChanged(8, 24) }
        assertTrue(Problems.snapshot().isEmpty())
        val ev = Problems.events()
        assertEquals("Camera mode changed by the map to 8 (wanted 24)", ev.first().message)
        assertTrue(ev.any { it.message == "Following stopped: map gesture (pan / fling / turn)" })
    }
}

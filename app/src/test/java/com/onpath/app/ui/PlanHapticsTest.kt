package com.onpath.app.ui

import com.onpath.app.ui.PlanHaptics.Feel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 2026-10-21: 1× point set, 2× point deleted, light tick for a menu, reorder 1×, undo none. */
class PlanHapticsTest {
    @Test fun mapping() {
        assertEquals(Feel.ONE, PlanHaptics.feel(PlanEdit.POINT_SET))
        assertEquals(Feel.TWO, PlanHaptics.feel(PlanEdit.POINT_DELETED))
        assertEquals(Feel.ONE, PlanHaptics.feel(PlanEdit.REORDERED))
        assertEquals(Feel.TICK, PlanHaptics.feel(PlanEdit.MENU_OPENED))
        assertEquals(Feel.NONE, PlanHaptics.feel(PlanEdit.UNDO))
        assertEquals(Feel.NONE, PlanHaptics.feel(PlanEdit.OTHER))
    }

    @Test fun patterns() {
        assertEquals(0, PlanHaptics.pattern(Feel.NONE).size)
        assertEquals(2, PlanHaptics.pattern(Feel.ONE).size)
        assertEquals(4, PlanHaptics.pattern(Feel.TWO).size) // two separate pulses
        // the tick is lighter than one pulse – a menu must never feel like a delete (it was 2× before)
        assertTrue(PlanHaptics.pattern(Feel.TICK)[1] < PlanHaptics.pattern(Feel.ONE)[1])
        assertTrue(PlanHaptics.pattern(Feel.TWO).sum() < 300)
    }

    /** A hold that opens a menu (point, POI, empty plan) only ticks; the edit itself vibrates afterwards. */
    @Test fun holdsOnlyTick() {
        assertEquals(MapGestures.Buzz.TICK, MapGestures.buzz(true, false, 0, false))  // on a point → its menu
        assertEquals(MapGestures.Buzz.TICK, MapGestures.buzz(true, false, -1, true))  // POI menu
        assertEquals(MapGestures.Buzz.TICK, MapGestures.buzz(true, false, -1, false)) // map
        assertEquals(MapGestures.Buzz.NONE, MapGestures.buzz(true, true, -1, false))  // only closes the menu
    }
}

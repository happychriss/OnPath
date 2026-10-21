package com.onpath.app.ui

import com.onpath.app.ui.SheetDrag.Settle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Field test 0.8.9 ("the swipe is jumping"): where the dragged plan sheet settles on release. */
class SheetDragTest {
    private fun settle(shown: Float, v: Float = 0f) = SheetDrag.settle(shown, 500f, v, distancePx = 100f, flingPxS = 1000f)

    @Test fun smallMovementGoesBack() {
        assertEquals(Settle.SAME, settle(450f))
        assertEquals(Settle.SAME, settle(560f))
    }

    @Test fun pulledFarEnoughChangesSize() {
        assertEquals(Settle.SMALLER, settle(380f))
        assertEquals(Settle.BIGGER, settle(620f))
    }

    @Test fun aFlingDecidesEvenWhenShort() {
        assertEquals(Settle.SMALLER, settle(490f, v = 2000f))
        assertEquals(Settle.BIGGER, settle(510f, v = -2000f))
    }

    /** Pulled up a long way, then a flick down at the end: back to the same size, not a jump two ways. */
    @Test fun aFlingAgainstALongPullCancels() {
        assertEquals(Settle.SAME, settle(700f, v = 2000f))
        assertEquals(Settle.SAME, settle(300f, v = -2000f))
    }

    @Test fun followsTheFingerWithRubberBandAtTheEnds() {
        assertEquals(400f, SheetDrag.follow(400f, 100f, 800f), 0f)
        assertEquals(830f, SheetDrag.follow(900f, 100f, 800f), 1e-3f)
        assertEquals(70f, SheetDrag.follow(0f, 100f, 800f), 1e-3f)
    }
}

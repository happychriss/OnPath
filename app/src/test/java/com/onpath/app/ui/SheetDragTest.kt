package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the dragged plan sheet settles (three steps, no jumping). */
class SheetDragTest {
    // small 200, profile 500, points 900
    private val steps = listOf(200f, 500f, 900f)
    private fun target(shown: Float, v: Float = 0f) = SheetDrag.target(shown, steps, v, flingPxS = 1000f)

    @Test fun smallMovementGoesBack() {
        assertEquals(1, target(450f))
        assertEquals(1, target(560f))
    }

    @Test fun settlesAtTheNearestStep() {
        assertEquals(0, target(330f))
        assertEquals(2, target(720f))
        assertEquals(2, target(1000f))  // rubber band above the top step
        assertEquals(0, target(120f))
    }

    /** A long pull goes past a step: from the small sheet straight to the points. */
    @Test fun aLongPullSkipsAStep() = assertEquals(2, target(850f))

    @Test fun aFlingGoesToTheNextStepInItsDirection() {
        assertEquals(2, target(510f, v = -2000f))
        assertEquals(0, target(490f, v = 2000f))
        assertEquals(1, target(300f, v = -2000f))
        assertEquals(1, target(800f, v = 2000f))
    }

    @Test fun aFlingBeyondTheEndsStaysAtTheEnd() {
        assertEquals(2, target(950f, v = -2000f))
        assertEquals(0, target(150f, v = 2000f))
    }

    @Test fun aSlowReleaseIsNoFling() = assertEquals(1, target(520f, v = -600f))

    @Test fun theReleaseLineNamesPositionStepsSpeedAndTarget() = assertEquals(
        "Sheet let go at 1143 px (steps 536 / 938 / 1602 px, -12 px/s) → step 1",
        SheetDrag.describe(1143.4f, listOf(536f, 938.2f, 1602f), -12.3f, 1))

    @Test fun followsTheFingerWithRubberBandAtTheEnds() {
        assertEquals(400f, SheetDrag.follow(400f, 100f, 800f), 0f)
        assertEquals(830f, SheetDrag.follow(900f, 100f, 800f), 1e-3f)
        assertEquals(70f, SheetDrag.follow(0f, 100f, 800f), 1e-3f)
    }
}

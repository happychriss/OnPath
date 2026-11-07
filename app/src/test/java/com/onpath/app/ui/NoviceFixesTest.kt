package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-03, novice fixes from the UX look: (1) a tap on the open map in Plan says "hold to set a point here",
 * (2) every map control names itself on a long press, (3) secondary lines read on the way are readable.
 */
class NoviceFixesTest {
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun aTapOnTheOpenMapShowsTheHoldHint() {
        val none = MapGestures.Action.None
        assertTrue(MapGestures.tapShowsHoldHint(true, MapGestures.Press.TAP, none, poi = false, marker = -1, planEmpty = false))
        assertFalse("hold is the gesture itself", MapGestures.tapShowsHoldHint(true, MapGestures.Press.HOLD, none, false, -1, false))
        assertFalse("not editable (Navigate / a shown track)", MapGestures.tapShowsHoldHint(false, MapGestures.Press.TAP, none, false, -1, false))
        assertFalse("a POI keeps its details", MapGestures.tapShowsHoldHint(true, MapGestures.Press.TAP, none, true, -1, false))
        assertFalse("a route point", MapGestures.tapShowsHoldHint(true, MapGestures.Press.TAP, none, false, 0, false))
        assertFalse("a tap that closes a menu", MapGestures.tapShowsHoldHint(true, MapGestures.Press.TAP, MapGestures.Action.CloseMenu, false, -1, false))
        assertFalse("the empty plan's hint says it already", MapGestures.tapShowsHoldHint(true, MapGestures.Press.TAP, none, false, -1, true))
    }

    @Test fun secondaryLinesOnTheWayAreReadable() {
        assertTrue("slider line on green", contrast(OnPathColors.OnGreenMuted, OnPathColors.Green) >= 4.5)
        // owner 2026-11-06 (flow map N2): the slider's small second line is back – in the readable muted white on green
        assertTrue(src("ui/SlideToUnlock.kt").contains("if (showSub) Text(sub, color = OnPathColors.OnGreenMuted"))
        assertTrue("grey info on the blue plan sheet", contrast(OnPathColors.Grey, OnPathColors.PlanTint) >= 4.5)
        assertTrue("detail lines on the blue plan sheet", contrast(OnPathColors.InkMuted, OnPathColors.PlanTint) >= 4.5)
    }
}

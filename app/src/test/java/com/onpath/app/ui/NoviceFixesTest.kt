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

    @Test fun theHoldHintStaysUntilTheUserActs() {
        val host = src("ui/MapHost.kt")
        assertTrue(host.contains("vm.userMovedMap = true; vm.tapHoldHint = false"))
        assertTrue(host.contains("vm.showTapHoldHint()"))
        val vm = src("ui/AppViewModel.kt")
        assertTrue("a mode switch", vm.substringAfter("private fun enter(m: AppMode) {").substringBefore("\n    }\n").contains("tapHoldHint = false"))
        val screen = src("ui/MapScreen.kt")
        assertTrue("a plan change", screen.contains("LaunchedEffect(vm.waypoints.toList()) { vm.tapHoldHint = false }"))
        assertTrue("a sheet drag", screen.contains("onSheetDrag = { if (it) vm.tapHoldHint = false; vm.mapGesture(it) }"))
        val panel = src("ui/PlanPanel.kt")
        assertTrue(panel.contains("else if (ui.tapHint && ui.tour == null)") && panel.contains("if (ui.tapHint) Box("))
        assertFalse("no time-out", vm.substringAfter("fun showTapHoldHint()").substringBefore("\n    }\n").contains("delay("))
    }

    @Test fun everyMapControlNamesItself() {
        val footer = src("ui/MapFooter.kt")
        assertTrue(footer.contains("MapTip(text, i == tipShown) { control() }"))
        assertTrue(footer.contains("TooltipBox(") && footer.contains("PlainTooltip"))
        for (dir in listOf("values", "values-de"))
            assertTrue(dir, File("../strings/src/commonMain/composeResources/$dir/strings_plan.xml").readText().contains("name=\"map_tip_locate\""))
    }

    @Test fun secondaryLinesOnTheWayAreReadable() {
        assertTrue("slider line on green", contrast(OnPathColors.OnGreenMuted, OnPathColors.Green) >= 4.5)
        assertTrue(src("ui/SlideToUnlock.kt").contains("Text(sub, color = OnPathColors.OnGreenMuted"))
        assertTrue("grey info on the blue plan sheet", contrast(OnPathColors.Grey, OnPathColors.PlanTint) >= 4.5)
        assertTrue("detail lines on the blue plan sheet", contrast(OnPathColors.InkMuted, OnPathColors.PlanTint) >= 4.5)
        val sub = src("ui/PlanPanel.kt").substringAfter("private fun SubLine(").substringBefore("\n}\n")
        assertFalse("the plan's status line is 16 sp", sub.contains("typography.bodyMedium"))
    }
}

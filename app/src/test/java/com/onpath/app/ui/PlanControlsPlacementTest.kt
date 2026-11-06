package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-06 (onpath.private#43): Plan's map controls stand at one fixed place (top anchored a little above the
 * middle) instead of riding on the sheet's edge – they jumped with every sheet step and when Undo appeared. Only a sheet
 * that would reach them pushes them up; without room they step back.
 */
class PlanControlsPlacementTest {
    private val screen = 2400
    private val limit = 300 // band bottom + air
    private val gap = 30

    @Test fun fixedTopIsAThirdDownButNeverAboveTheBand() {
        assertEquals(624, ControlsPlacement.fixedTop(screen, limit))
        assertEquals(900, ControlsPlacement.fixedTop(screen, 900)) // large font: a deep band wins
    }

    @Test fun theSheetSmallOrHalfDoesNotMoveTheColumn() {
        val top = ControlsPlacement.fixedTop(screen, limit)
        // the same top for a small and a half sheet, with 4 or 6 buttons: nothing moves
        for (sheetTop in listOf(1900, 1570)) for (h in listOf(600, 900)) {
            assertEquals("sheet $sheetTop, height $h", top, ControlsPlacement.place(top, h, sheetTop, limit, gap))
        }
    }

    @Test fun aSheetThatWouldReachTheColumnPushesItUp() {
        val top = ControlsPlacement.fixedTop(screen, limit)
        assertEquals(1400 - gap - 900, ControlsPlacement.place(top, 900, 1400, limit, gap))
    }

    @Test fun withoutRoomTheColumnStepsBack() {
        val top = ControlsPlacement.fixedTop(screen, limit)
        assertNull(ControlsPlacement.place(top, 900, 830, limit, gap)) // the sheet pulled up to below the band
    }

    @Test fun planOrderIsTheAlwaysThereButtonsFirst() {
        val src = File("src/main/java/com/onpath/app/ui/MapFooter.kt").readText()
            .substringAfter("fun MapControls(").substringBefore("val items = ")
        val i = { s: String -> src.indexOf(s).also { assertTrue("$s in MapControls", it >= 0) } }
        // compass, then (Plan) locate, then eye, whole route, undo, redo; Navigate keeps locate last
        assertTrue(i("if (compass != null)") < i("if (plan) add(locate)"))
        assertTrue(i("if (plan) add(locate)") < i("if (showEye)"))
        assertTrue(i("if (showEye)") < i("if (onFitRoute != null)"))
        assertTrue(i("if (onFitRoute != null)") < i("if (onUndo != null)"))
        assertTrue(i("if (onUndo != null)") < i("if (onRedo != null)"))
        assertTrue(i("if (onRedo != null)") < i("if (!plan) add(locate)"))
    }

    @Test fun planModeDrawsTheColumnUnderTheBoxesAndTheSheet() {
        val plan = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText().substringAfter("private fun PlanMode(")
        val layer = plan.indexOf("PlanControlsLayer(")
        assertTrue(layer in 0 until plan.indexOf("PlanMessageHost(vm") && layer < plan.indexOf("SheetWithFooter("))
        // the footer on the sheet's edge keeps only scale + ©: the controls are not in it any more in Plan
        val footer = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText().substringAfter("val footer = @Composable")
            .substringBefore("\n    }\n")
        assertTrue(footer, footer.contains("if (mode == AppMode.NAVIGATE) controls(mode)"))
    }
}

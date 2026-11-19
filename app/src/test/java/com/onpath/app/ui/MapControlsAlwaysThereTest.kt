package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-06: in both map columns (Plan, Navigate) compass, locate, eye and whole route are always there – greyed
 * when they cannot act (no route yet), never hidden: nothing jumps, and one sees what one could do. Undo / Redo left the
 * column (owner 2026-11-08): their own pill under it, only while there is something to undo or redo.
 */
class MapControlsAlwaysThereTest {
    private val footer = File("src/main/java/com/onpath/app/ui/MapFooter.kt").readText()
    private val controls = footer.substringAfter("fun MapControls(").substringBefore("\n}\n")

    @Test fun eyeAndWholeRouteAreNeverHidden() {
        assertFalse(controls, controls.contains("if (showEye) add("))
        assertFalse(controls, controls.contains("if (onFitRoute != null) add("))
        assertTrue(controls.contains("EyeControl(onPeek, peeking, size, tint, enabled = showEye)"))
        assertTrue(controls.contains("enabled = onFitRoute != null"))
        // undo / redo are not in the column any more (owner 2026-11-08, father's feedback: ↶ ↷ read as "turn the map")
        assertFalse(controls, controls.contains("plan_undo"))
        assertFalse(controls, controls.contains("plan_redo"))
    }

    /** Owner 2026-11-08 late: undo / redo as two plain round buttons in their own group (no words – too wide); both
     *  there while one can act, the one that cannot act greyed. */
    @Test fun undoGroupIsTwoRoundButtonsAndGreysOne() {
        val pill = footer.substringAfter("fun UndoPill(").substringBefore("\n}\n")
        assertTrue(pill, pill.contains("Res.string.plan_undo") && pill.contains("Res.string.plan_redo"))
        assertTrue(pill, pill.contains("if (onUndo == null && onRedo == null) return"))
        assertTrue(pill, pill.contains("enabled = onUndo != null") && pill.contains("enabled = onRedo != null"))
        assertTrue(pill, !pill.contains("Text("))
    }

    /**
     * Owner 2026-11-18 (answer 4): the full points sheet hides the map's controls, so undo / redo stand at its "Punkte"
     * title – the same pill ([UndoPill] inSheet), the same actions of the view model (no own undo logic), only in the
     * full step and only the one that can act.
     */
    @Test fun undoAndRedoAlsoInTheFullPointsSheet() {
        val ui = "src/main/java/com/onpath/app/ui"
        val section = File("$ui/PointsSection.kt").readText().substringAfter("fun PointsSection(").substringBefore("\n}\n")
        assertTrue(section, section.contains("UndoPill(onUndo, onRedo, inSheet = true)"))
        val panel = File("$ui/PlanPanel.kt").readText()
        assertTrue(panel.contains("onUndo = if (full && ui.canUndo) cb.onUndo else null"))
        assertTrue(panel.contains("onRedo = if (full && ui.canRedo) cb.onRedo else null"))
        val screen = File("$ui/MapScreen.kt").readText()
        assertTrue("the sheet's undo / redo are the map pill's", screen.contains("onUndo = vm.planEdit::undoStep, onRedo = vm.planEdit::redoStep"))
        assertTrue(screen.contains("onUndo = if (MapControlsEnabled.undo(ctl)) vm.planEdit::undoStep else null"))
        assertTrue(screen.contains("onRedo = if (MapControlsEnabled.redo(ctl)) vm.planEdit::redoStep else null"))
        // in the sheet: 48 dp (the title row's height), no shadow; on the map: 56 dp with the float shadow
        val pill = footer.substringAfter("fun UndoPill(").substringBefore("\n}\n")
        assertTrue(pill, pill.contains("if (inSheet) SHEET_UNDO else MAP_FAB"))
        assertEquals(48f, SHEET_UNDO.value, 0f)
    }

    @Test fun aDisabledControlIsGreyNotGone() {
        val t = OnPathColors.ActionEdit
        assertEquals(t, MapControlState.tint(t, true))
        assertEquals(MapControlState.DISABLED_ALPHA, MapControlState.tint(t, false).alpha, 0.001f)
        assertTrue(footer.contains("if (!enabled) disabled()"))
    }
}

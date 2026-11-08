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

    /** Owner 2026-11-08: undo / redo in their own pill with words; both halves there, the one that cannot act greyed. */
    @Test fun undoPillHasWordsAndGreysAHalf() {
        val pill = footer.substringAfter("fun UndoPill(").substringBefore("\n}\n")
        assertTrue(pill, pill.contains("Res.string.plan_undo") && pill.contains("Res.string.plan_redo"))
        assertTrue(pill, pill.contains("if (onUndo == null && onRedo == null) return"))
        val half = footer.substringAfter("private fun UndoHalf(").substringBefore("\n}\n")
        assertTrue(half, half.contains("Text(label") && half.contains("MapControlState.tint(OnPathColors.PlanAccent, onClick != null)"))
    }

    @Test fun aDisabledControlIsGreyNotGone() {
        val t = OnPathColors.ActionEdit
        assertEquals(t, MapControlState.tint(t, true))
        assertEquals(MapControlState.DISABLED_ALPHA, MapControlState.tint(t, false).alpha, 0.001f)
        assertTrue(footer.contains("if (!enabled) disabled()"))
    }
}

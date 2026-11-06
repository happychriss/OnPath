package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-06: in both map columns (Plan, Navigate) compass, locate, eye and whole route are always there – greyed
 * when they cannot act (no route yet), never hidden: nothing jumps, and one sees what one could do. Undo / Redo stay at
 * the very bottom of Plan's column and come only when there is something to undo (they move nothing above them).
 */
class MapControlsAlwaysThereTest {
    private val footer = File("src/main/java/com/onpath/app/ui/MapFooter.kt").readText()
    private val controls = footer.substringAfter("fun MapControls(").substringBefore("\n}\n")

    @Test fun eyeAndWholeRouteAreNeverHidden() {
        assertFalse(controls, controls.contains("if (showEye) add("))
        assertFalse(controls, controls.contains("if (onFitRoute != null) add("))
        assertTrue(controls.contains("EyeControl(onPeek, peeking, size, tint, enabled = showEye)"))
        assertTrue(controls.contains("enabled = onFitRoute != null"))
        // undo / redo: only on demand, after everything else in Plan
        assertTrue(controls.indexOf("plan_undo") > controls.indexOf("plan_show_whole_route"))
        assertTrue(controls.contains("if (onUndo != null) add("))
    }

    @Test fun aDisabledControlIsGreyNotGone() {
        val t = Color(0xFF2E6A9E)
        assertEquals(t, MapControlState.tint(t, true))
        assertEquals(MapControlState.DISABLED_ALPHA, MapControlState.tint(t, false).alpha, 0.001f)
        assertTrue(footer.contains("if (!enabled) disabled()"))
    }
}

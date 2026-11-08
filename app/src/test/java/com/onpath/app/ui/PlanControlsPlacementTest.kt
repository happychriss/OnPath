package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    /** Owner 2026-11-08 late: undo / redo stacked under the column, right-aligned with it, a gap below its bottom. */
    @Test fun theUndoButtonsStandUnderTheColumn() {
        val gap = 30; val width = 1080; val end = 42
        val bottom = 1100
        assertEquals((width - end - 168) to (bottom + gap), ControlsPlacement.pill(bottom, width - end, 168, gap))
        assertEquals(width - end - 189, ControlsPlacement.pillMaxWidth(width, end, 189))
    }

    /** Short room: "↷" loses its word first, then "↶" – always one row of 48 dp. */
    @Test fun theUndoPillDropsWordsWhenNarrow() {
        assertEquals(UndoPillShape.Words.BOTH, UndoPillShape.words(330, 150, 360, 150, 3, 800))
        assertEquals(UndoPillShape.Words.UNDO, UndoPillShape.words(330, 150, 360, 150, 3, 690))
        assertEquals(UndoPillShape.Words.NONE, UndoPillShape.words(330, 150, 360, 150, 3, 400))
    }
}

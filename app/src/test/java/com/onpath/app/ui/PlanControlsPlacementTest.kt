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

    /** Owner 2026-11-08: the undo pill never moves the column; under it when it fits, else beside its last button. */
    @Test fun theUndoPillGoesUnderTheColumnElseBesideIt() {
        val width = 1080; val end = 42; val colW = 147; val colH = 588; val pillW = 600; val pillH = 126
        val top = ControlsPlacement.fixedTop(screen, limit)
        val left = width - end - colW
        // room above the sheet: right-aligned under the column
        assertEquals((width - end - pillW) to (top + colH + gap),
            ControlsPlacement.pill(top, colH, left, pillW, pillH, sheetTopPx = 1900, screenWidthPx = width, gapPx = gap, endPx = end, startPx = end))
        // a half sheet close under the column: beside it, level with its last button
        assertEquals((left - gap - pillW) to (top + colH - pillH),
            ControlsPlacement.pill(top, colH, left, pillW, pillH, sheetTopPx = top + colH + 100, screenWidthPx = width, gapPx = gap, endPx = end, startPx = end))
        // neither fits (a very wide pill at a large font): not placed
        assertNull(ControlsPlacement.pill(top, colH, left, 1000, pillH, sheetTopPx = top + colH + 100, screenWidthPx = width,
            gapPx = gap, endPx = end, startPx = end))
        // the scale bar and © on the sheet's edge are kept free: under the column only with room above them too
        val footer = 168
        assertEquals((width - end - pillW) to (top + colH + gap),
            ControlsPlacement.pill(top, colH, left, pillW, pillH, sheetTopPx = top + colH + gap + pillH + gap + footer + 10,
                screenWidthPx = width, gapPx = gap, endPx = end, startPx = end, footerPx = footer))
        // beside, but the column's bottom is close to the sheet: the pill rises so the scale bar and © stay free
        val sheetTop = top + colH + 40
        assertEquals((left - gap - pillW) to (sheetTop - footer - gap - pillH),
            ControlsPlacement.pill(top, colH, left, pillW, pillH, sheetTopPx = sheetTop, screenWidthPx = width, gapPx = gap,
                endPx = end, startPx = end, footerPx = footer))
        // no room even so (below the top band): not placed
        assertNull(ControlsPlacement.pill(top, colH, left, pillW, pillH, sheetTopPx = sheetTop, screenWidthPx = width, gapPx = gap,
            endPx = end, startPx = end, footerPx = footer, limitPx = sheetTop))
        // the column's own place does not depend on the pill
        assertEquals(top, ControlsPlacement.place(top, colH, 1570, limit, gap))
    }

    /** The pill's halves side by side when they fit the room beside the column, else stacked – never cut. */
    @Test fun theUndoPillStacksWhenNarrow() {
        assertEquals(true, UndoPillShape.sideBySide(330, 360, 3, 800))
        assertEquals(false, UndoPillShape.sideBySide(330, 360, 3, 690))
    }
}

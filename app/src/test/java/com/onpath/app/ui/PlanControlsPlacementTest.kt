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

    /**
     * Owner 2026-11-08 evening (Pixel: the pill sat "mitten im Bild"): the undo pill right above the column,
     * right-aligned with it; its slot is kept whether it shows or not, so nothing moves when it comes or goes.
     */
    @Test fun theUndoPillStandsAboveTheColumnInAKeptSlot() {
        val gap = 30; val slot = 126; val width = 1080; val end = 42
        // the slot fits between the band and 26 %: the column keeps its usual top
        assertEquals(ControlsPlacement.fixedTop(screen, limit), ControlsPlacement.fixedTopWithPill(screen, limit, slot, gap))
        // a deep band (large font, short screen): the column goes down by the slot – the same with and without a pill
        assertEquals(500 + slot + gap, ControlsPlacement.fixedTopWithPill(1500, 500, slot, gap))
        // the pill: right edge = the column's right edge, bottom = a gap above the column's top
        val top = 624
        assertEquals((width - end - 600) to (top - gap - slot), ControlsPlacement.pill(top, width - end, 600, slot, gap))
        // never wider than the room right of the activity button
        assertEquals(width - end - 189, ControlsPlacement.pillMaxWidth(width, end, 189))
    }

    /** Short room: "↷" loses its word first, then "↶" – always one row of 48 dp. */
    @Test fun theUndoPillDropsWordsWhenNarrow() {
        assertEquals(UndoPillShape.Words.BOTH, UndoPillShape.words(330, 150, 360, 150, 3, 800))
        assertEquals(UndoPillShape.Words.UNDO, UndoPillShape.words(330, 150, 360, 150, 3, 690))
        assertEquals(UndoPillShape.Words.NONE, UndoPillShape.words(330, 150, 360, 150, 3, 400))
    }
}

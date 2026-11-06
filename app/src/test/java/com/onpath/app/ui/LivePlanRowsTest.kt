package com.onpath.app.ui

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan on the map in My routes (owner 2026-11-06 23:00: "unter Zuletzt ‚gerade in Bearbeitung'"): first under Zuletzt
 * with the tag, or the tag on the saved route it is – never twice, never on an empty map.
 */
class LivePlanRowsTest {
    private val a = 51.17 to 6.94
    private val b = 51.18 to 6.95
    private val c = 51.19 to 6.96

    private fun tour(id: Long, draft: Boolean, vararg pts: Pair<Double, Double>) = Tour(id, TourKind.PLANNED, "T$id", 0L, null, 0,
        1000.0, 10.0, 10.0, 600.0, "done", pts.map { GpxWaypoint(it.first, it.second, null) }, draft = draft)

    private fun live(linked: Long = -1L, vararg pts: Pair<Double, Double>, unsaved: Boolean = true) =
        LivePlan(name = null, points = pts.size, distanceM = 0.0, ascentM = 0.0, durationS = 0.0, linkedId = linked,
            key = pts.toList(), unsaved = unsaved)

    @Test fun anEmptyMapHasNoEntry() {
        assertEquals(LiveRows.NONE, LivePlanRows.of(null, listOf(tour(1, true, a, b)), emptyList()))
        assertEquals(LiveRows.NONE, LivePlanRows.of(live(), listOf(tour(1, true, a, b)), emptyList()))
    }

    @Test fun aNewPlanIsFirstUnderZuletzt() {
        val r = LivePlanRows.of(live(-1L, a, b), listOf(tour(1, true, a, c)), listOf(tour(2, false, b, c)))
        assertTrue(r.onTop)
        assertEquals(-1L, r.markedSavedId)
        assertTrue(r.hiddenDraftIds.isEmpty())
    }

    @Test fun onePointIsAlreadyInProgress() {
        val r = LivePlanRows.of(live(-1L, a), emptyList(), emptyList())
        assertTrue(r.onTop)
        assertFalse(live(-1L, a).canSave)
        assertTrue(live(-1L, a, b).canSave)
    }

    @Test fun aSavedRouteOnTheMapIsTaggedInPlaceNotTwice() {
        val r = LivePlanRows.of(live(2L, b, c, unsaved = false), emptyList(), listOf(tour(2, false, b, c)))
        assertFalse(r.onTop)
        assertEquals(2L, r.markedSavedId)
    }

    @Test fun aChangedSavedRouteStaysOneRowUnderGespeichert() {
        val r = LivePlanRows.of(live(2L, b, c, a), emptyList(), listOf(tour(2, false, b, c)))
        assertFalse(r.onTop)
        assertEquals(2L, r.markedSavedId)
    }

    @Test fun aPlanLoadedFromZuletztReplacesItsDraftRow() {
        val r = LivePlanRows.of(live(1L, a, b, c), listOf(tour(1, true, a, b), tour(3, true, b, c)), emptyList())
        assertTrue(r.onTop)
        assertEquals(setOf(1L), r.hiddenDraftIds)
    }

    @Test fun theSamePointsKeptUnderZuletztBeforeAreNotShownTwice() {
        // Undo after "Neu planen": the plan is back on the map and its copy is still under Zuletzt
        val r = LivePlanRows.of(live(-1L, a, b), listOf(tour(1, true, a, b), tour(3, true, b, c)), emptyList())
        assertTrue(r.onTop)
        assertEquals(setOf(1L), r.hiddenDraftIds)
    }

    @Test fun aLinkToARouteNoLongerThereIsANewPlan() {
        val r = LivePlanRows.of(live(9L, a, b), emptyList(), listOf(tour(2, false, b, c)))
        assertTrue(r.onTop)
        assertEquals(-1L, r.markedSavedId)
    }
}

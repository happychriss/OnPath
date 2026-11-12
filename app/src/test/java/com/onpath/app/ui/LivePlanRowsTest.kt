package com.onpath.app.ui

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.onpath.app.domain.plan.LivePlan
import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.domain.plan.RecentRow

/**
 * The one row under "Zuletzt" in My routes (owner 2026-11-06 23:00: "unter Zuletzt ‚gerade in Bearbeitung'"; owner
 * 2026-11-12: exactly one plan there – the one worked on last, saved or not): the plan on the map whenever there is one,
 * else the draft or saved route that took the place last – never two rows, never on an empty list.
 */
class LivePlanRowsTest {
    private val a = 51.17 to 6.94
    private val b = 51.18 to 6.95
    private val c = 51.19 to 6.96

    /** [id] doubles as the creation time: a higher id is the newer tour. */
    private fun tour(id: Long, draft: Boolean, vararg pts: Pair<Double, Double>) = Tour(id, TourKind.PLANNED, "T$id", id, null, 0,
        1000.0, 10.0, 10.0, 600.0, "done", pts.map { GpxWaypoint(it.first, it.second, null) }, draft = draft)

    private fun live(linked: Long = -1L, vararg pts: Pair<Double, Double>, unsaved: Boolean = true) =
        LivePlan(name = null, points = pts.size, distanceM = 0.0, ascentM = 0.0, durationS = 0.0, linkedId = linked,
            key = pts.toList(), unsaved = unsaved)

    @Test fun anEmptyMapWithNothingKeptHasNoRow() {
        assertEquals(RecentRow.NONE, LivePlanRows.of(null, emptyList(), listOf(tour(2, false, b, c))))
        assertEquals(RecentRow.NONE, LivePlanRows.of(live(), emptyList(), listOf(tour(2, false, b, c))))
        assertFalse(RecentRow.NONE.shown)
    }

    @Test fun aNewPlanOnTheMapIsTheRow() {
        val r = LivePlanRows.of(live(-1L, a, b), listOf(tour(1, true, a, c)), listOf(tour(2, false, b, c)), recentId = 1L)
        assertEquals(RecentRow(live = true, tourId = -1L, saved = false), r)
        assertTrue(r.shown)
    }

    @Test fun onePointIsAlreadyInProgress() {
        val r = LivePlanRows.of(live(-1L, a), listOf(tour(1, true, a, c)), emptyList(), recentId = 1L)
        assertTrue(r.live)
        assertFalse(live(-1L, a).canSave)
        assertTrue(live(-1L, a, b).canSave)
    }

    /** Owner 2026-11-12: a saved route on the map is the Zuletzt row ("In Bearbeitung" + "Gespeichert") and stays under Gespeichert. */
    @Test fun aSavedRouteOnTheMapIsTheRowWithTheSavedMark() {
        val r = LivePlanRows.of(live(2L, b, c, unsaved = false), emptyList(), listOf(tour(2, false, b, c)))
        assertEquals(RecentRow(live = true, tourId = 2L, saved = true), r)
    }

    @Test fun aChangedSavedRouteOnTheMapHasNoSavedMark() {
        val r = LivePlanRows.of(live(2L, b, c, a), emptyList(), listOf(tour(2, false, b, c)))
        assertEquals(RecentRow(live = true, tourId = 2L, saved = false), r)
    }

    @Test fun aPlanLoadedFromZuletztIsTheRowItsDraftNeverASecondOne() {
        val r = LivePlanRows.of(live(1L, a, b, c), listOf(tour(1, true, a, b)), emptyList(), recentId = 1L)
        assertEquals(RecentRow(live = true, tourId = 1L, saved = false), r)
    }

    @Test fun theSamePointsKeptUnderZuletztBeforeAreTheLivePlan() {
        // Undo after "Neu planen": the plan is back on the map and its copy is still under Zuletzt
        val r = LivePlanRows.of(live(-1L, a, b), listOf(tour(1, true, a, b)), emptyList(), recentId = 1L)
        assertEquals(RecentRow(live = true, tourId = 1L, saved = false), r)
    }

    @Test fun aLinkToARouteNoLongerThereIsANewPlan() {
        val r = LivePlanRows.of(live(9L, a, b), emptyList(), listOf(tour(2, false, b, c)))
        assertEquals(RecentRow(live = true, tourId = -1L, saved = false), r)
    }

    @Test fun anEmptyMapShowsTheDraftThatTookThePlaceLast() {
        val r = LivePlanRows.of(null, listOf(tour(1, true, a, b)), listOf(tour(2, false, b, c)), recentId = 1L)
        assertEquals(RecentRow(live = false, tourId = 1L, saved = false), r)
    }

    @Test fun anEmptyMapShowsTheSavedRouteThatTookThePlaceLastWithTheSavedMark() {
        // (the draft before it is in the bin by then – RoutesState.takePlace)
        val r = LivePlanRows.of(null, emptyList(), listOf(tour(2, false, b, c)), recentId = 2L)
        assertEquals(RecentRow(live = false, tourId = 2L, saved = true), r)
    }

    /**
     * A draft never hides (review 2026-11-12): the app died between a draft's insert and its taking the place, or a
     * draft came back from the bin – a draft outside the bin newer than the pointed one is the row; a saved route
     * holding the place gives way to any draft; an older draft beside the pointed draft does not.
     */
    @Test fun aDraftNewerThanThePointedPlanIsTheRow() {
        val d1 = tour(1, true, a, b); val d2 = tour(2, true, a, c); val x = tour(3, false, b, c)
        assertEquals(RecentRow(live = false, tourId = 2L, saved = false), LivePlanRows.of(null, listOf(d2, d1), listOf(x), recentId = 1L))
        assertEquals("the pointed draft is the newest: it", RecentRow(live = false, tourId = 2L, saved = false),
            LivePlanRows.of(null, listOf(d2, d1), listOf(x), recentId = 2L))
        assertEquals("a saved route holds the place, a draft exists: the draft", RecentRow(live = false, tourId = 2L, saved = false),
            LivePlanRows.of(null, listOf(d2), listOf(x), recentId = 3L))
        assertEquals("no draft: the saved route", RecentRow(live = false, tourId = 3L, saved = true),
            LivePlanRows.of(null, emptyList(), listOf(x), recentId = 3L))
    }

    /** Older installs (no place known yet) and a saved route deleted since: the newest draft is the row. */
    @Test fun withoutAKnownPlaceTheNewestDraftIsTheRow() {
        val drafts = listOf(tour(5, true, a, c), tour(1, true, a, b)) // newest first, like the list
        assertEquals(RecentRow(live = false, tourId = 5L, saved = false), LivePlanRows.of(null, drafts, emptyList()))
        assertEquals("the saved route is gone", RecentRow(live = false, tourId = 5L, saved = false),
            LivePlanRows.of(null, drafts, emptyList(), recentId = 2L))
        assertEquals("nothing kept at all", RecentRow.NONE, LivePlanRows.of(null, emptyList(), emptyList(), recentId = 2L))
    }
}

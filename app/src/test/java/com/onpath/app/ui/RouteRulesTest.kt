package com.onpath.app.ui

import com.onpath.app.data.TourKind
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRulesTest {
    private val a = GeoPoint(46.67, 11.16)
    private val b = GeoPoint(46.70, 11.20)
    private val c = GeoPoint(46.72, 11.22)

    @Test fun emptyPlanNothingSaved() {
        val m = RouteRules.menu(hasRoute = false, pointCount = 0, savedCount = 0, navigating = false, recording = false)
        assertEquals(RouteMenuState(load = false, save = false, startRecording = true), m)
        assertTrue(m.manage && m.settings && m.updates)
    }

    @Test fun routePlannedWithSavedRoutes() {
        val m = RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 2, navigating = false, recording = false)
        assertTrue(m.load); assertTrue(m.save); assertFalse(m.startRecording)
    }

    @Test fun routeStillCalculatingBlocksFreeRecordingButNotSave() {
        val m = RouteRules.menu(hasRoute = false, pointCount = 2, savedCount = 1, navigating = false, recording = false)
        assertFalse(m.startRecording); assertFalse(m.save); assertTrue(m.load)
        // One point is no route yet: free recording is fine.
        assertTrue(RouteRules.menu(false, 1, 0, navigating = false, recording = false).startRecording)
    }

    @Test fun navigatingOrRecordingBlocksLoadAndRecording() {
        for ((nav, rec) in listOf(true to false, false to true, true to true)) {
            val m = RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 5, navigating = nav, recording = rec)
            assertFalse(m.load); assertFalse(m.startRecording); assertTrue(m.save); assertTrue(m.manage); assertTrue(m.settings)
        }
        val free = RouteRules.menu(hasRoute = false, pointCount = 0, savedCount = 5, navigating = true, recording = true)
        assertFalse(free.startRecording); assertFalse(free.load)
    }

    /** Owner 2026-10-30: Save beside ⋯ and on top of ☰ while the plan is new or changed; saved → neither. */
    @Test fun saveAtHandWhileUnsaved() {
        // a new route (never saved) is unsaved
        assertTrue(RouteRules.saveAtHand(2, RouteRules.isUnsaved(listOf(a, b), null)))
        // saved / loaded and untouched: quiet
        assertFalse(RouteRules.saveAtHand(2, RouteRules.isUnsaved(listOf(a, b), listOf(a, b))))
        // changed since the save: at hand again
        assertTrue(RouteRules.saveAtHand(3, RouteRules.isUnsaved(listOf(a, b, c), listOf(a, b))))
        // one point or none: no route to keep
        assertFalse(RouteRules.saveAtHand(1, RouteRules.isUnsaved(listOf(a), null)))
        assertFalse(RouteRules.saveAtHand(0, true))
        // during a tour saving is allowed like ⋯ → Save route: the button is enabled once a route line exists
        val tour = RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 1, navigating = true, recording = true)
        assertTrue(tour.save)
        assertFalse(RouteRules.menu(hasRoute = false, pointCount = 3, savedCount = 1, navigating = false, recording = false).save)
    }

    @Test fun clearRules() {
        assertFalse(RouteRules.canClear(0))
        assertTrue(RouteRules.canClear(1))
        // also during a tour (owner) – it ends the route guidance like deleting all points (PlanGuidanceTest)
        assertTrue(RouteRules.canClear(3))
    }

    @Test fun aPlannedRouteDuringATourAsksToReplaceTheTourRoute() {
        // owner 2026-10-31: during a tour a planned route is loadable – after one question, also over a saved plan
        assertEquals(OpenAsk.REPLACE_TOUR_ROUTE, RouteRules.openAsk(TourKind.PLANNED, 3, touring = true, listOf(a, b), listOf(a, b)))
        assertEquals(OpenAsk.REPLACE_TOUR_ROUTE, RouteRules.openAsk(TourKind.PLANNED, 2, touring = true, listOf(a, b), null))
        assertEquals(OpenAsk.REPLACE_TOUR_ROUTE, RouteRules.openAsk(TourKind.PLANNED, 2, touring = true, emptyList(), null))
        // tracks open beside the plan as before (shown as a track), also during a tour
        assertEquals(OpenAsk.NONE, RouteRules.openAsk(TourKind.RECORDED, 0, touring = true, listOf(a, b), null))
        assertEquals(OpenAsk.NONE, RouteRules.openAsk(TourKind.IMPORTED, 4, touring = true, listOf(a, b), null))
        // a planned route without via points is only shown as a track
        assertEquals(OpenAsk.NONE, RouteRules.openAsk(TourKind.PLANNED, 1, touring = true, listOf(a, b), null))
    }

    @Test fun withoutATourOnlyAnUnsavedPlanAsks() {
        assertEquals(OpenAsk.REPLACE_PLAN, RouteRules.openAsk(TourKind.PLANNED, 3, touring = false, listOf(a, b), null))
        assertEquals(OpenAsk.NONE, RouteRules.openAsk(TourKind.PLANNED, 3, touring = false, listOf(a, b), listOf(a, b)))
        assertEquals(OpenAsk.NONE, RouteRules.openAsk(TourKind.PLANNED, 3, touring = false, emptyList(), null))
        assertEquals(OpenAsk.NONE, RouteRules.openAsk(TourKind.IMPORTED, 3, touring = false, listOf(a, b), null))
    }

    @Test fun unsavedPlan() {
        assertFalse(RouteRules.isUnsaved(emptyList(), null))
        assertFalse(RouteRules.isUnsaved(listOf(a), null)) // one point is no route yet
        assertTrue(RouteRules.isUnsaved(listOf(a, b), null)) // never saved
        assertFalse(RouteRules.isUnsaved(listOf(a, b), listOf(a, b))) // as saved / loaded
        assertTrue(RouteRules.isUnsaved(listOf(a, b, c), listOf(a, b))) // point added
        assertTrue(RouteRules.isUnsaved(listOf(b, a), listOf(a, b))) // reversed
        // Elevation does not make a difference (stored points have none).
        assertFalse(RouteRules.isUnsaved(listOf(a.copy(ele = 500.0), b), listOf(a, b)))
    }

    @Test fun askBeforeLoadingOnlyWhenPlanWouldBeLost() {
        assertTrue(RouteRules.askBeforeLoading(TourKind.PLANNED, 2, listOf(a, b), null))
        assertFalse(RouteRules.askBeforeLoading(TourKind.PLANNED, 2, listOf(a, b), listOf(a, b)))
        assertFalse(RouteRules.askBeforeLoading(TourKind.PLANNED, 2, listOf(a), null))
        // Tracks are shown next to the plan, which stays: no question.
        assertFalse(RouteRules.askBeforeLoading(TourKind.RECORDED, 0, listOf(a, b), null))
        assertFalse(RouteRules.askBeforeLoading(TourKind.IMPORTED, 3, listOf(a, b), null))
    }

    @Test fun saveModeOverwriteOnlyForAnExistingSavedRoute() {
        assertEquals(SaveMode.NAME_DIALOG, RouteRules.saveMode(-1, null, listOf(1L, 2L))) // never saved
        assertEquals(SaveMode.OVERWRITE_OR_NEW, RouteRules.saveMode(2, "Hirzer", listOf(1L, 2L))) // loaded / saved as #2
        assertEquals(SaveMode.NAME_DIALOG, RouteRules.saveMode(3, "Gone", listOf(1L, 2L))) // deleted meanwhile
        assertEquals(SaveMode.NAME_DIALOG, RouteRules.saveMode(2, null, listOf(2L))) // no name known
    }

    @Test fun cleanName() {
        assertEquals("Hirzer", RouteRules.cleanName("  Hirzer ", "Old"))
        assertEquals("Old", RouteRules.cleanName("   ", "Old"))
    }
}

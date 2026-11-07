package com.onpath.app.ui

import com.onpath.app.map.MapController.TrackLook
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan shows the plan's line and numbered points – also during a tour, blue as without one; Navigate shows only the
 * followed line. Owner 2026-11-01: no grey route while planning ("das Planen mit der grauen Route klappt überhaupt nicht
 * – wir lassen die blaue Route"; the blue line had vanished when the plan was hidden behind a pale copy).
 */
class PlanLayersTest {
    // canEditMap = Plan mode and no track shown (AppViewModel.canEditMap)
    @Test fun planShowsLineAndMarkers() = assertEquals(true to true, planLayers(canEditMap = true))
    @Test fun navigateShowsNoPlanLineAndNoMarkers() = assertEquals(false to false, planLayers(canEditMap = false))

    /** Owner 2026-11-01: in Navigate the plan's points show while the tour follows the plan (passed ones with a ✓). */
    @Test fun navigateFollowingThePlanShowsItsPoints() =
        assertEquals(false to true, planLayers(canEditMap = false, navigateWithPlan = true))

    private fun g(i: Int) = com.onpath.app.routing.GeoPoint(51.0 + i * 0.001, 7.0)
    private val ahead = (5..9).map(::g)

    @Test fun planHasNoGreyLines() = assertEquals(emptyList<Any>(), paleLines(plan = true, navOriginal = ahead))

    @Test fun navigateKeepsTheReRoutesGreyOriginal() = assertEquals(listOf(ahead), paleLines(plan = false, navOriginal = ahead))

    @Test fun theFollowedRouteIsNeverGrey() {
        assertEquals(listOf("TRACK", "FOLLOW"), com.onpath.app.map.MapController.TrackLook.entries.map { it.name })
        val vm = java.io.File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val look = vm.substringAfter("fun trackLook()").substringBefore("\n\n")
        assertEquals(true, look.contains("gpxLine("))
        assertEquals(TrackLook.FOLLOW, gpxLine(navigate = false, shown = null, follow = ahead).look)
        assertEquals(TrackLook.TRACK, gpxLine(navigate = false, shown = listOf(ahead), follow = null).look)
    }

    /**
     * Field bug 2026-11-07 (owner's S10, Play 0.17.3, "Wir haben Punkte ohne Route"): during the tour he opened a done
     * tour of 0 m from My routes (no line – it is only shown, not followed) and went back to Navigate. The map line
     * showed that track's (empty) segments instead of the followed line: Navigate drew the plan points ⑦ ⑧ ⑨ around
     * Bonn and no route, while the guidance ran on (587 km to go). Navigate always draws the followed line.
     */
    @Test fun navigateDrawsTheFollowedLineWhileAnEmptyDoneTourIsShown() {
        val line = gpxLine(navigate = true, shown = emptyList(), follow = ahead)
        assertEquals(listOf(ahead), line.lines)
        assertEquals(TrackLook.FOLLOW, line.look)
        assertEquals(false, line.track)
    }

    @Test fun navigateDrawsTheFollowedLineNotAShownTrack() {
        val track = (20..25).map(::g)
        val line = gpxLine(navigate = true, shown = listOf(track), follow = ahead)
        assertEquals(listOf(ahead), line.lines)
        assertEquals(TrackLook.FOLLOW, line.look)
    }

    /** Plan: a shown track (its sheet is open) is drawn in its own colour, as before; with nothing followed Navigate too. */
    @Test fun aShownTrackIsDrawnInPlanAndWhenNothingIsFollowed() {
        val track = (20..25).map(::g)
        assertEquals(GpxLine(listOf(track), TrackLook.TRACK, true), gpxLine(navigate = false, shown = listOf(track), follow = ahead))
        assertEquals(GpxLine(listOf(track), TrackLook.TRACK, true), gpxLine(navigate = true, shown = listOf(track), follow = null))
        assertEquals(GpxLine(emptyList(), TrackLook.FOLLOW, false), gpxLine(navigate = true, shown = null, follow = null))
    }
}

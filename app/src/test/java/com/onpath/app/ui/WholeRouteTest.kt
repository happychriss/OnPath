package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "Show whole route" map button (owner 2026-10-31): when it shows, what it fits, the room it leaves free. */
class WholeRouteTest {
    private val plan = AppMode.PLAN
    private val nav = AppMode.NAVIGATE

    @Test fun planShowsTheShownTrackElseThePlanElseTheFollowedLine() {
        assertEquals(WholeRoute.Source.PLANNED, WholeRoute.source(plan, planned = true, shown = false, followed = false))
        // a shown track hides the plan line: the track is what is on the map
        assertEquals(WholeRoute.Source.SHOWN, WholeRoute.source(plan, planned = true, shown = true, followed = false))
        assertEquals(WholeRoute.Source.SHOWN, WholeRoute.source(plan, planned = false, shown = true, followed = true))
        // a tour runs and the plan was cleared: its followed line is still drawn
        assertEquals(WholeRoute.Source.FOLLOWED, WholeRoute.source(plan, planned = false, shown = false, followed = true))
        assertNull(WholeRoute.source(plan, planned = false, shown = false, followed = false))
    }

    @Test fun navigateShowsOnlyTheFollowedLine() {
        assertEquals(WholeRoute.Source.FOLLOWED, WholeRoute.source(nav, planned = true, shown = false, followed = true))
        // free recording: no line, no button (a plan in the background is not on the map in Navigate)
        assertNull(WholeRoute.source(nav, planned = true, shown = false, followed = false))
        assertNull(WholeRoute.source(nav, planned = false, shown = false, followed = false))
    }

    @Test fun buttonShowsOnlyWithALineAndNotInThePointsStep() {
        assertTrue(WholeRoute.shows(plan, pointsOpen = false, planned = true, shown = false, followed = false))
        assertFalse(WholeRoute.shows(plan, pointsOpen = true, planned = true, shown = false, followed = false))
        assertFalse(WholeRoute.shows(plan, pointsOpen = false, planned = false, shown = false, followed = false))
        assertTrue(WholeRoute.shows(nav, pointsOpen = false, planned = false, shown = false, followed = true))
        assertFalse(WholeRoute.shows(nav, pointsOpen = false, planned = true, shown = false, followed = false))
    }

    @Test fun boundsAreTheLinesThePointsAndThePosition() {
        val line = listOf(GeoPoint(46.60, 11.10), GeoPoint(46.62, 11.12))
        val leadIn = listOf(GeoPoint(46.50, 11.00), GeoPoint(46.60, 11.10))
        // a point the route does not reach lies off the line: it counts too
        val offLine = GeoPoint(46.70, 11.30)
        val here = GeoPoint(46.40, 10.90)
        assertEquals(line + offLine, WholeRoute.bounds(listOf(line), listOf(offLine), null))
        val b = WholeRoute.bounds(listOf(leadIn, line), emptyList(), here)
        assertEquals(leadIn + line + here, b)
        assertEquals(46.40, b.minOf { it.lat }, 1e-9)
        assertEquals(46.62, b.maxOf { it.lat }, 1e-9)
    }

    @Test fun paddingKeepsClearOfTopAndBottomElements() {
        val d = 2.5f
        val status = 60
        // Plan: below the search pill (76 dp) or the strip / box under it, above the sheet
        val p = FitPaddingRule.of(plan, d, status, planTopDp = 0, sheetPx = 800, navTopDp = 0, navBottomDp = 0)
        assertEquals(FitPadding(120, status + 220, 120, 800 + 120), p)
        val withBox = FitPaddingRule.of(plan, d, status, planTopDp = 180, sheetPx = 800, navTopDp = 0, navBottomDp = 0)
        assertEquals(status + ((180 + 12) * d).toInt(), withBox.top)
        // Navigate: below the turn / status column (or the box under it), above the slider, clear of the big buttons
        val n = FitPaddingRule.of(nav, d, status, planTopDp = 0, sheetPx = 800, navTopDp = 200, navBottomDp = 90)
        assertEquals(FitPadding(120, status + 530, 200, 285), n)
    }

    /** Following stops before the camera moves (CLAUDE.md "Map camera"); free look must not take it up again. */
    @Test fun tapStopsFollowingBeforeTheFit() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val body = vm.substringAfter("fun showWholeRoute() {").substringBefore("\n    }\n")
        val stop = body.indexOf("followMe = false")
        assertTrue(stop >= 0 && body.indexOf("freeLookAt = null") > stop)
        assertTrue(body.indexOf("events += UiEvent.Fit(pts, northUp = true)") > stop)
        // Navigate only: where you are counts
        assertTrue(body.contains("takeIf { mode == AppMode.NAVIGATE }"))
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("onFitRoute = if (vm.wholeRouteShown(mode, pointsOpen)) vm::showWholeRoute else null"))
        assertTrue(screen.contains("is UiEvent.Fit -> c.fitTo(e.points, fitPadding(), e.northUp)"))
    }

    /** Field A-019: a search result picked while planning during a tour landed half behind the plan sheet. */
    @Test fun searchResultFliesIntoTheFreeRoom() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val fly = vm.substringAfter("private fun flyToPlace(").substringBefore("\n    }\n")
        assertTrue(fly.indexOf("followMe = false") in 0 until fly.indexOf("events += UiEvent.CenterOn("))
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("is UiEvent.CenterOn -> c.flyTo(e.point, e.zoom, fitPadding())"))
    }
}

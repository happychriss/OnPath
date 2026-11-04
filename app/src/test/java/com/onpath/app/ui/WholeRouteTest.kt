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
        // map button column not measured yet: a MAP_FAB column (56 dp, owner 2026-11-03) beside, no room above it
        val p = FitPaddingRule.of(plan, d, status, planTopDp = 0, sheetPx = 800, navTopDp = 0, navBottomDp = 0)
        assertEquals(FitPadding(60, status + 220, (MAP_FAB.value * d).toInt() + 80, 800 + 120), p)
        val withBox = FitPaddingRule.of(plan, d, status, planTopDp = 180, sheetPx = 800, navTopDp = 0, navBottomDp = 0)
        assertEquals(status + ((180 + 12) * d).toInt(), withBox.top)
        // Navigate: below the turn / status column (or the box under it), above the slider, clear of the big buttons
        val n = FitPaddingRule.of(nav, d, status, planTopDp = 0, sheetPx = 800, navTopDp = 200, navBottomDp = 90)
        assertEquals(FitPadding(120, status + 530, 200, 285), n)
    }

    /**
     * Field A-019 add-on (01b): with the sheet pulled up the destination lay under the map buttons; with the small sheet
     * the fit left a wide margin. Plan's room: the sheet's current height, beside the measured button column or above it.
     */
    @Test fun planRoomIsBesideOrAboveTheMapButtons() {
        val d = 2.25f
        val status = 54
        val fabW = (48 * d).toInt()
        val fabH = (5 * 48 * d).toInt()
        val px = { dp: Int -> (dp * d).toInt() }
        for (sheet in listOf(310, 2000)) { // the small sheet (one row) and the sheet pulled up: always its height now
            val p = FitPaddingRule.of(plan, d, status, planTopDp = 0, sheetPx = sheet, navTopDp = 0, navBottomDp = 0,
                fabWidthPx = fabW, fabHeightPx = fabH)
            // beside: the right edge clears the column (16 dp margin) by a marker's radius
            assertEquals(fabW + px(16) + px(FitPaddingRule.AIR_DP), p.right)
            assertTrue(p.right >= fabW + px(16) + px(15))
            assertEquals(sheet + px(FitPaddingRule.FOOTER_DP), p.bottom)
            // above: the whole width, the bottom edge over the column's top (12 dp above the sheet) by a marker's radius
            val a = p.above!!
            assertEquals(px(FitPaddingRule.PLAN_SIDE_DP), a.right)
            assertEquals(p.left, a.left)
            assertEquals(p.top, a.top)
            assertEquals(sheet + px(12) + fabH + px(FitPaddingRule.AIR_DP), a.bottom)
            assertTrue(a.above == null)
        }
        // no excessive margin: the sides are a marker's radius + 8 dp, not the old 48 dp
        val p = FitPaddingRule.of(plan, d, status, planTopDp = 0, sheetPx = 310, navTopDp = 0, navBottomDp = 0, fabWidthPx = fabW, fabHeightPx = fabH)
        assertEquals(px(24), p.left)
        assertTrue(p.above!!.right < px(FitPaddingRule.SIDE_DP))
        // Navigate keeps its one room
        assertNull(FitPaddingRule.of(nav, d, status, 0, 310, 200, 90, fabW, fabH).above)
    }

    /** The room that shows the route bigger wins; a tie or an unknown zoom keeps the room beside the buttons. */
    @Test fun pickTakesTheRoomWithTheBiggerRoute() {
        val above = FitPadding(10, 20, 10, 900)
        val pad = FitPadding(10, 20, 200, 400, above)
        // a wide route (01: east–west): above the buttons, over the whole width, is bigger
        assertEquals(above, FitPaddingRule.pick(pad) { if (it == above) 13.6 else 13.3 })
        // a tall route: beside the buttons (down to the sheet) is bigger
        assertEquals(pad, FitPaddingRule.pick(pad) { if (it == above) 12.8 else 13.1 })
        assertEquals(pad, FitPaddingRule.pick(pad) { 13.0 })
        assertEquals(pad, FitPaddingRule.pick(pad) { if (it == above) null else 13.0 })
        val single = FitPadding(1, 2, 3, 4)
        assertEquals(single, FitPaddingRule.pick(single) { error("not asked") })
        // the map asks the camera for both rooms
        val ctl = File("src/main/java/com/onpath/app/map/MapController.kt").readText()
        val fit = ctl.substringAfter("fun fitTo(").substringBefore("\n    }\n")
        assertTrue(fit.contains("FitPaddingRule.pick(room)"))
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("fabWidthPx = planFab[0], fabHeightPx = planFab[1]"))
        assertTrue(screen.contains("sheetPx = panelHeightPx"))
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

    /** Field A-019: the first destination / a plan link left the route half off the map. */
    @Test fun firstRouteIsFitted() {
        assertTrue(WholeRoute.firstRoute(1, 2)) // first destination held
        assertTrue(WholeRoute.firstRoute(0, 2)) // "Go here", a route opened into an empty plan
        assertTrue(WholeRoute.firstRoute(0, 5))
        assertTrue(!WholeRoute.firstRoute(2, 3)) // later edits never move the map
        assertTrue(!WholeRoute.firstRoute(3, 2))
        assertTrue(!WholeRoute.firstRoute(0, 1))
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        // the same fit as "Show whole route", following stopped first
        val fit = vm.substringAfter("private fun fitFirstRoute(").substringBefore("\n    }\n")
        val stop = fit.indexOf("followMe = false")
        assertTrue(stop >= 0 && fit.indexOf("events += UiEvent.Fit(WholeRoute.bounds(listOf(line), pts, null), northUp = true)") > stop)
        assertTrue(fit.contains("lastMapTouchMs > asked")) // the user took the map meanwhile: their view stays
        assertTrue(vm.contains("if (WholeRoute.firstRoute(before.size, waypoints.size)) fitNextRoute = true"))
        assertTrue(vm.contains("if (fitNextRoute) { fitNextRoute = false; fitFirstRoute(it.points, pts) }"))
        // a plan link: following stops, the start camera keeps away, the route is fitted once computed
        val link = vm.substringAfter("private fun openSharedPlan(").substringBefore("\n    }\n")
        assertTrue(link.contains("holdCameraForSharedPlace()"))
        assertTrue(link.indexOf("followMe = false") in 0 until link.indexOf("events += UiEvent.Fit(load.points, northUp = true)"))
        assertTrue(link.contains("fitNextRoute = true"))
    }

    /** Field A-019 add-on (09d): after "Add as new destination" from the candidate's menu the map stayed on the place. */
    @Test fun placeAddedFromTheCardOrTheCandidateIsFitted() {
        val a = GeoPoint(46.69, 11.10); val b = GeoPoint(46.70, 11.12); val c = GeoPoint(46.70, 11.14)
        assertTrue(WholeRoute.fitAfterPlace(listOf(a, b), listOf(a, b, c))) // new destination
        assertTrue(WholeRoute.fitAfterPlace(listOf(a, b), listOf(a, c, b))) // stop 2
        assertTrue(WholeRoute.fitAfterPlace(listOf(a), listOf(a, c))) // set as destination
        assertTrue(WholeRoute.fitAfterPlace(listOf(a, c, b), listOf(a, b, c))) // a planned place made the destination
        assertFalse(WholeRoute.fitAfterPlace(listOf(a, b), listOf(a, b))) // nothing changed (e.g. already a stop)
        assertFalse(WholeRoute.fitAfterPlace(emptyList(), listOf(c))) // a start alone: no route to fit
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        // the place card's actions (SMART = "Add as stop N" / "Add as new destination", START, DESTINATION) all go through it
        assertTrue(vm.contains("fun addPlace(place: com.onpath.app.search.Place, role: PlaceRole) = fittingPlaceAdd {"))
        // the candidate's hold menu ("Add as new destination") – a POI of the map held there does not move the map
        val poi = vm.substringAfter("fun addPoiPoint(").substringBefore("\n    }\n")
        assertTrue(poi.indexOf("val candidate = isCandidate(poi)") in 0 until poi.indexOf("closeMapOverlays()"))
        assertTrue(poi.contains("if (candidate) fittingPlaceAdd { addPoint(p, lineSnapM, onPoi = true) } else addPoint(p, lineSnapM, onPoi = true)"))
        val add = vm.substringAfter("private inline fun fittingPlaceAdd(").substringBefore("\n    }\n")
        assertTrue(add.contains("if (WholeRoute.fitAfterPlace(before, waypoints.toList())) fitNextRoute = true"))
        // the same fit as the first route: following stopped, the user's touch wins
        assertTrue(vm.contains("if (fitNextRoute) { fitNextRoute = false; fitFirstRoute(it.points, pts) }"))
    }

    /** Field A-019: a search result picked while planning during a tour landed half behind the plan sheet. */
    @Test fun searchResultFliesIntoTheFreeRoom() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val search = File("src/main/java/com/onpath/app/ui/SearchFlow.kt").readText() // search: out of AppViewModel
        assertTrue(search.substringAfter("fun flyToPlace(").substringBefore("\n    }\n").contains("host.flyTo("))
        val fly = vm.substringAfter("override fun flyTo(").substringBefore("\n")
        assertTrue(fly, fly.indexOf("followMe = false") in 0 until fly.indexOf("events += UiEvent.CenterOn("))
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("is UiEvent.CenterOn -> c.flyTo(e.point, e.zoom, fitPadding())"))
    }
}

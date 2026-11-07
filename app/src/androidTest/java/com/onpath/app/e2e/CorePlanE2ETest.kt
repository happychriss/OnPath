package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.plan_start_recording_for
import com.onpath.strings.plan_menu_record_without_plan
import com.onpath.strings.plan_menu
import com.onpath.strings.plan_menu_my_routes
import com.onpath.strings.routes_tag_live
import com.onpath.strings.plan_sheet_bigger
import com.onpath.strings.plan_sheet_smaller
import com.onpath.app.ui.PlanSize
import com.onpath.strings.routes_overview_plan
import com.onpath.strings.routes_overview_show
import com.onpath.strings.routes_overview_loading
import com.onpath.strings.common_cancel
import org.maplibre.android.geometry.LatLng

/**
 * CORE FEATURES C1 / C2 end to end (docs/core-features.md): the real app, the real MapLibre map, a finger held on the
 * screen (UiAutomator). Runs on an emulator beside every release (.github/workflows/e2e.yml) – better than finding out
 * during a walk.
 *
 * Map data: a tiny generated package (empty base tile, flat terrain, empty overlays, a dummy routing file → straight
 * lines). Enough for the gestures, which do not depend on what the map shows.
 */
@RunWith(AndroidJUnit4::class)
class CorePlanE2ETest : E2EBase() {

    @Before fun setUp() = launch()

    // ------------------------------------------------------------------------------------------------ tests

    /** C2 + C1: hold the empty map → Start here; hold elsewhere → point added; hold that point → menu → Delete. */
    @Test fun holdAddsPointsAndHoldOnAPointOpensItsMenuToDelete() {
        startPlanWithTwoPoints()
        holdOnWaypoint(1)
        clickText("Delete")
        waitUntil("point deleted") { waypointCount() == 1 }
    }

    /** C1 after the map style reloads (a map update installs a new generation → the style is loaded again). */
    @Test fun holdOnAPointWorksAfterTheMapReloaded() {
        startPlanWithTwoPoints()
        scenario.onActivity { vm(it).reloadMapStyleForTest() }
        waitForMap(reloaded = true)
        holdOnWaypoint(1)
        assertNotNull("point menu after a style reload", device.wait(Until.findObject(By.text("Delete")), 5_000))
        clickText("Delete")
        waitUntil("point deleted after reload") { waypointCount() == 1 }
    }

    /** After a style reload the route points must not be drawn above the "I am here" dot. */
    @Test fun positionDotStaysAboveRoutePointsAfterAReload() {
        startPlanWithTwoPoints()
        assertPositionDotOnTop("plan")
        scenario.onActivity { vm(it).reloadMapStyleForTest() }
        waitForMap(reloaded = true)
        assertPositionDotOnTop("after a style reload")
        // the app's own layers exist and sit below it
        var ids = emptyList<String>()
        onMap { m -> ids = m.style?.layers?.map { it.id } ?: emptyList() }
        for (own in listOf(com.onpath.app.map.MapController.LAYER_WPT, "planned-route", "recorded-track", "highlight-dot"))
            assertTrue("$own exists", own in ids)
        // layers the app adds later (search mark, highlight) go below it too
        scenario.onActivity { a -> vm(a).highlight = vm(a).waypoints.first() }
        Thread.sleep(1_000)
        assertPositionDotOnTop("with a highlight")
    }

    /** C1 on a turned map (compass, hand rotation). */
    @Test fun holdOnAPointWorksOnATurnedMap() {
        startPlanWithTwoPoints()
        // turn around the middle of the route, so both points stay above the plan sheet
        var mid: LatLng? = null
        scenario.onActivity { a -> vm(a).waypoints.let { w -> mid = LatLng((w[0].lat + w[1].lat) / 2, (w[0].lon + w[1].lon) / 2) } }
        onMap { map ->
            map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                org.maplibre.android.camera.CameraPosition.Builder().target(mid).bearing(137.0).build()))
        }
        Thread.sleep(500)
        holdOnWaypoint(0)
        assertNotNull("point menu on a turned map", device.wait(Until.findObject(By.text("Delete")), 5_000))
    }

    /**
     * Owner 2026-11-06 23:00 ("unter Zuletzt ‚gerade in Bearbeitung', dann weiß ich, dass immer gespeichert wird"): the plan
     * on the map is the first row of My routes → Planned with the tag "In Bearbeitung"; a tap on it brings back the very
     * same plan on the map – nothing reloaded, nothing lost.
     */
    @Test fun myRoutesShowsThePlanInProgressAndATapGoesBackToIt() {
        startPlanWithTwoPoints()
        var before = emptyList<com.onpath.app.routing.GeoPoint>()
        scenario.onActivity { a -> before = vm(a).waypoints.toList() }
        findFresh(By.desc(Txt.s(Res.string.plan_menu)), 8_000)!!.click()
        findFresh(By.text(Txt.s(Res.string.plan_menu_my_routes)), 5_000)!!.click()
        val tag = findFresh(By.text(Txt.s(Res.string.routes_tag_live)), 8_000)
        assertNotNull("My routes shows the plan \"In Bearbeitung\"", tag)
        assertTrue("only once", device.findObjects(By.text(Txt.s(Res.string.routes_tag_live))).size == 1)
        tag!!.click()
        waitUntil("My routes closed", 5_000) { var open = true; scenario.onActivity { a -> open = vm(a).routesOpen }; !open }
        var after = emptyList<com.onpath.app.routing.GeoPoint>()
        scenario.onActivity { a -> after = vm(a).waypoints.toList() }
        org.junit.Assert.assertEquals("the same plan on the map", before, after)
    }

    /**
     * My routes (owner 2026-11-07): every row has its small route preview; a row tap opens the large overview first –
     * "Abbrechen" changes nothing, "Planen" puts the route on the map in Plan.
     */
    @Test fun aRowTapOpensTheOverviewAndPlanenLoadsIt() {
        startPlanWithTwoPoints()
        var before = emptyList<com.onpath.app.routing.GeoPoint>()
        var centre = LatLng(0.0, 0.0)
        scenario.onActivity { a -> before = vm(a).waypoints.toList(); centre = LatLng(before[0].lat, before[0].lon) }
        val db = com.onpath.app.data.TourDb.get(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        val pts = listOf(com.onpath.app.gpx.GpxWaypoint(centre.latitude + 0.003, centre.longitude, "E2E A"),
            com.onpath.app.gpx.GpxWaypoint(centre.latitude + 0.005, centre.longitude + 0.003, "E2E B"))
        val name = "E2E overview route"
        val id = db.insertTour(com.onpath.app.data.TourKind.PLANNED, name, com.onpath.app.routing.Activity.HIKE.name, 2, 420.0, 0.0, 0.0,
            300.0, listOf(pts.map { com.onpath.app.gpx.TrackPoint(it.lat, it.lon) }), pts)
        try {
            scenario.onActivity { a -> vm(a).routesTab = com.onpath.app.ui.RoutesTab.PLANNED; vm(a).openRoutes() }
            val row = findFresh(By.text(name), 8_000)
            assertNotNull("the saved route in My routes", row)
            assertTrue("rows show their route preview",
                device.wait(Until.hasObject(By.descContains(Txt.s(Res.string.routes_overview_show))), 5_000))
            shot("1-routes-with-previews")
            row!!.click()
            val plan = findFresh(By.text(Txt.s(Res.string.routes_overview_plan)), 8_000)
            // the map picture comes a moment later: the shot shows it (the line's width is checked by eye – 2026-11-07)
            device.wait(Until.gone(By.text(Txt.s(Res.string.routes_overview_loading))), 15_000)
            shot("2-overview")
            assertNotNull("the overview with \"Planen\" – ${state()}", plan)
            findFresh(By.text(Txt.s(Res.string.common_cancel)), 3_000)!!.click()
            waitUntil("overview closed, still in My routes", 5_000) {
                device.findObject(By.text(Txt.s(Res.string.routes_overview_plan))) == null &&
                    run { var open = false; scenario.onActivity { a -> open = vm(a).routesOpen }; open }
            }
            var still = emptyList<com.onpath.app.routing.GeoPoint>()
            scenario.onActivity { a -> still = vm(a).waypoints.toList() }
            org.junit.Assert.assertEquals("Abbrechen changed nothing", before, still)
            findFresh(By.text(name), 5_000)!!.click()
            findFresh(By.text(Txt.s(Res.string.routes_overview_plan)), 5_000)!!.click()
            waitUntil("My routes closed", 8_000) { var open = true; scenario.onActivity { a -> open = vm(a).routesOpen }; !open }
            val loaded = pts.map { com.onpath.app.routing.GeoPoint(it.lat, it.lon) }
            waitUntil("the route is the plan", 10_000) {
                var w = emptyList<com.onpath.app.routing.GeoPoint>(); scenario.onActivity { a -> w = vm(a).waypoints.toList() }; w == loaded
            }
        } finally {
            db.deleteTour(id)
        }
    }

    /**
     * Flow map H1 (2026-11-06, confirmed on the emulator): with no plan point there was no way to start a tour – no
     * green button on any sheet step, none in ☰. Now the sheet's green button says "Los geht's · ohne Route" and starts a
     * plain recording (PlanStart.RECORD_ONLY).
     */
    @Test fun anEmptyPlanStartsARecordingWithoutARoute() {
        scenario.onActivity { a -> if (vm(a).waypoints.isNotEmpty()) vm(a).clearPlan() }
        waitUntil("empty plan", 5_000) { waypointCount() == 0 }
        // owner 2026-11-06: no start button in the sheet without a plan – the plain recording is ☰ → "Nur aufzeichnen"
        org.junit.Assert.assertNull("no green start button on an empty plan",
            device.findObject(By.desc(Txt.s(Res.string.plan_start_recording_for))))
        val menu = findFresh(By.desc(Txt.s(Res.string.plan_menu)), 8_000)
        assertNotNull("the ☰ menu button", menu)
        menu!!.click()
        val item = findFresh(By.text(Txt.s(Res.string.plan_menu_record_without_plan)), 5_000)
        assertNotNull("☰ → \"Nur aufzeichnen\"", item)
        item!!.click()
        // the phone check before the first tour may open (vibration / voice): "Later" goes on to the tour, as a user does
        repeat(3) {
            Thread.sleep(800)
            var open = false
            scenario.onActivity { a -> open = vm(a).setupGuide != null }
            if (open) device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Later|Continue|Später|Weiter"))), 5_000)?.click()
        }
        waitUntil("a tour records", 20_000) { var r = false; scenario.onActivity { a -> r = vm(a).recState.value != null }; r }
        try { scenario.onActivity { a -> vm(a).endTourAndDelete() } } catch (_: Exception) {} // expected: app already closed
        waitUntil("the tour ended", 20_000) { var r = true; scenario.onActivity { a -> r = vm(a).recState.value != null }; !r }
    }

    /**
     * Owner 2026-11-06 23:42: the plan sheet keeps its drag handle and a tap on it steps the sheet – bigger one step,
     * from the largest step back one; TalkBack hears what the tap does.
     */
    @Test fun aTapOnTheHandleStepsTheSheet() {
        startPlanWithTwoPoints()
        scenario.onActivity { vm(it).planSize = PlanSize.COMPACT }
        Thread.sleep(800)
        val bigger = findFresh(By.desc(Txt.s(Res.string.plan_sheet_bigger)), 10_000)
        assertNotNull("the handle says what a tap does", bigger)
        bigger!!.click()
        waitUntil("a tap on the handle: one step bigger", 5_000) { planSize() == PlanSize.FULL }
        val smaller = findFresh(By.desc(Txt.s(Res.string.plan_sheet_smaller)), 5_000)
        assertNotNull("on the largest step it says smaller", smaller)
        smaller!!.click()
        waitUntil("a tap on the largest step: one step back", 5_000) { planSize() == PlanSize.COMPACT }
    }

    private fun planSize(): PlanSize { var s = PlanSize.MINI; scenario.onActivity { s = vm(it).planSize }; return s }
}

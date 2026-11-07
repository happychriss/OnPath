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
import com.onpath.strings.plan_bar_go
import com.onpath.strings.plan_navigate_anyway
import com.onpath.strings.plan_reach_level
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

    /**
     * Field 2026-11-07 (owner, S10: "a destination outside my map region … loads the new map but then forgets the
     * destination"): the found place's pin is drawn again after the map style reloads (new map regions joined), and
     * when Cancel at the very-long-plan question takes its add back, the place comes back as its pin.
     */
    @Test fun aFoundPlaceSurvivesAMapReloadAndALongPlanCancel() {
        startPlanWithTwoPoints()
        scenario.onActivity { vm(it).selectActivity(com.onpath.app.routing.Activity.HIKE) }
        var centre: LatLng? = null
        onMap { m -> centre = m.cameraPosition.target }
        val near = com.onpath.app.search.Place("e2e:near", "Near place", "place", centre!!.latitude, centre!!.longitude, sub = "village")
        scenario.onActivity { vm(it).searchMark = near }
        waitUntil("pin drawn") { pinsDrawn() >= 1 }
        scenario.onActivity { vm(it).reloadMapStyleForTest() }
        waitForMap(reloaded = true)
        waitUntil("pin drawn again after a style reload") { pinsDrawn() >= 1 }
        var mark: com.onpath.app.search.Place? = null
        scenario.onActivity { mark = vm(it).searchMark }
        assertTrue("the pin stays after a style reload", mark == near)

        // a place far beyond a day on foot (~130 km north): adding it asks first; Cancel takes the add back
        val far = com.onpath.app.search.Place("e2e:far", "Far place", "place", MERAN_LAT + 1.2, MERAN_LON, sub = "town")
        scenario.onActivity { vm(it).searchMark = far; vm(it).addPoiPoint(far.toPoi()) }
        waitUntil("the very-long-plan question") { var a: Double? = null; scenario.onActivity { a = vm(it).longRouteAsk }; a != null }
        scenario.onActivity { mark = vm(it).searchMark }
        assertTrue("the pin went into the plan", mark == null)
        scenario.onActivity { vm(it).answerLongRoute(false) }
        waitUntil("the add taken back") { waypointCount() == 2 }
        scenario.onActivity { mark = vm(it).searchMark }
        assertTrue("the far place is a pin again, not forgotten (mark $mark)", mark == far)
    }

    /**
     * The found place's pin on the map: features of its layer drawn anywhere on the screen, else in its source (a GeoJSON
     * point can come once per tile it touches – so "at least one").
     */
    private fun pinsDrawn(): Int {
        var n = -1
        val (w, h) = mapSize()
        onMap { m ->
            n = try {
                val drawn = m.queryRenderedFeatures(android.graphics.RectF(0f, 0f, w, h), com.onpath.app.map.MapController.LAYER_MARK).size
                drawn.takeIf { it > 0 } ?: (m.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(
                    com.onpath.app.map.MapController.SRC_MARK)?.querySourceFeatures(null)?.size ?: 0)
            } catch (e: Exception) {
                android.util.Log.i("OnPathE2E", "pin not readable yet: $e"); 0 // expected: the style is being replaced – asked again
            }
        }
        return n
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
     * E2E 2026-11-07 (CoreFieldE2ETest.aSavedRouteTapped… red on GitHub now and then, "E2E saved route" not shown while the
     * list held it): My routes opens with the list read before, the fresh read puts a new route on top – it must show
     * there, not above the screen (a LazyColumn keeps its first visible row by key when rows come before it; only a list
     * longer than the screen can scroll, so more rows than fit: the class run had ten plans, a single run two).
     */
    @Test fun aRouteAddedWhileMyRoutesIsOpenShowsAtTheTop() {
        val db = com.onpath.app.data.TourDb.get(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        val now = System.currentTimeMillis()
        fun insert(name: String, dLat: Double, createdMs: Long): Long {
            val pts = listOf(com.onpath.app.gpx.GpxWaypoint(MERAN_LAT + dLat, MERAN_LON, null),
                com.onpath.app.gpx.GpxWaypoint(MERAN_LAT + dLat + 0.002, MERAN_LON + 0.003, null))
            return db.insertTour(com.onpath.app.data.TourKind.PLANNED, name, com.onpath.app.routing.Activity.HIKE.name, 2, 420.0,
                0.0, 0.0, 300.0, listOf(pts.map { com.onpath.app.gpx.TrackPoint(it.lat, it.lon) }), pts, createdMs = createdMs)
        }
        // more rows than the screen holds, the "older route" the newest of them
        val fillers = (1..10).map { insert("E2E filler $it", 0.001 * it, now - 60_000L * (11 - it)) }
        val older = insert("E2E older route", 0.003, now)
        var newer = -1L
        try {
            scenario.onActivity { a -> vm(a).routesTab = com.onpath.app.ui.RoutesTab.PLANNED; vm(a).openRoutes() }
            assertNotNull("the older route in My routes", findFresh(By.text("E2E older route"), 8_000))
            newer = insert("E2E newer route", 0.006, now + 1_000)
            scenario.onActivity { a -> vm(a).refreshTours() }
            waitUntil("the list holds the newer route", 10_000) {
                var listed = false; scenario.onActivity { a -> listed = vm(a).tours.any { it.id == newer } }; listed
            }
            val row = findFresh(By.text("E2E newer route"), 5_000)
            if (row == null) shot("newer-route-missing")
            assertNotNull("the newer route shows on top of My routes – ${state()}", row)
        } finally {
            scenario.onActivity { a -> vm(a).routesOpen = false }
            (fillers + older).forEach { db.deleteTour(it) }
            if (newer >= 0) db.deleteTour(newer)
        }
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
     * Owner 2026-11-07: "Los geht's" in My routes – a row tap opens the overview (Abbrechen · Los geht's · Planen); Los
     * geht's loads the route as the plan (the unsaved plan on the map goes under Zuletzt, no question) and starts the tour
     * on it by the plan sheet's own start path: recording, Navigate, the loaded route followed.
     */
    @Test fun losGehtsInMyRoutesStartsTheTourOnThatRoute() {
        startPlanWithTwoPoints()
        var centre = LatLng(0.0, 0.0)
        scenario.onActivity { a -> val w = vm(a).waypoints; centre = LatLng(w[0].lat, w[0].lon) }
        val db = com.onpath.app.data.TourDb.get(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        val pts = listOf(com.onpath.app.gpx.GpxWaypoint(centre.latitude + 0.002, centre.longitude - 0.001, "E2E go A"),
            com.onpath.app.gpx.GpxWaypoint(centre.latitude + 0.004, centre.longitude + 0.002, "E2E go B"))
        val name = "E2E Los geht's route"
        val id = db.insertTour(com.onpath.app.data.TourKind.PLANNED, name, com.onpath.app.routing.Activity.HIKE.name, 2, 380.0, 0.0, 0.0,
            300.0, listOf(pts.map { com.onpath.app.gpx.TrackPoint(it.lat, it.lon) }), pts)
        try {
            scenario.onActivity { a -> vm(a).routesTab = com.onpath.app.ui.RoutesTab.PLANNED; vm(a).openRoutes() }
            findFresh(By.text(name), 8_000)!!.click()
            val go = findFresh(By.text(Txt.s(Res.string.plan_bar_go)), 8_000)
            assertNotNull("the overview offers \"Los geht's\" – ${state()}", go)
            assertNotNull("… beside \"Planen\"", device.findObject(By.text(Txt.s(Res.string.routes_overview_plan))))
            shot("1-overview-three-actions")
            go!!.click()
            // the normal start: the phone check may ask (Later goes on), the tiny map's straight lines ask "Navigate anyway?"
            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline) {
                var rec = false; var guide = false
                scenario.onActivity { a -> rec = vm(a).recState.value != null; guide = vm(a).setupGuide != null }
                if (rec) break
                if (guide) device.findObject(By.text(java.util.regex.Pattern.compile("Later|Continue|Später|Weiter")))?.click()
                device.findObject(By.text(Txt.s(Res.string.plan_navigate_anyway)))?.click()
                Thread.sleep(500)
            }
            waitUntil("a tour records – ${state()}", 5_000) { var r = false; scenario.onActivity { a -> r = vm(a).recState.value != null }; r }
            val loaded = pts.map { com.onpath.app.routing.GeoPoint(it.lat, it.lon) }
            waitUntil("Navigate follows the route from My routes", 20_000) {
                var ok = false
                scenario.onActivity { a ->
                    val v = vm(a)
                    ok = v.mode == com.onpath.app.ui.AppMode.NAVIGATE && v.waypoints.toList() == loaded && !v.routesOpen &&
                        v.followLine != null && v.recState.value?.nav != null
                }
                ok
            }
            shot("2-navigate-on-the-route")
            val trace = com.onpath.app.debug.Problems.events().map { it.message }
            for (step in listOf("Los geht's on \"$name\" in My routes", "Plan kept under Zuletzt",
                    "Los geht's from My routes: the line of \"$name\" is there")) {
                assertTrue("\"$step\" traced: ${trace.takeLast(30)}", trace.any { it.contains(step) })
            }
        } finally {
            try { scenario.onActivity { a -> vm(a).endTourAndDelete() } } catch (_: Exception) {} // expected: no tour / app closed
            waitUntil("no tour", 20_000) { var r = true; scenario.onActivity { a -> r = vm(a).recState.value != null }; !r }
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

    /**
     * Owner 2026-11-07 (Pixel, "Meran → Mutkopf" at Hike T1): the route stops 552 m short of ② – the path up is above
     * T1 – and the sheet only said "kein Weg für Wandern". Now the red line says what reaches it, "② erst ab T2
     * erreichbar", and a tap on it shows that section on the map (drawn amber, like a steep section). Real routing data.
     */
    @Test fun aStopOnlyT2ReachesSaysSoAndATapShowsTheSection() {
        org.junit.Assume.assumeTrue("real routing data needed (E10_N45.rd5 in /data/local/tmp)", realRouting)
        val meran = com.onpath.app.routing.GeoPoint(46.67135, 11.16464)
        val mutkopf = com.onpath.app.routing.GeoPoint(46.71077, 11.13787)
        scenario.onActivity { vm(it).selectActivity(com.onpath.app.routing.Activity.HIKE); vm(it).selectSac(1) }
        scenario.onActivity { vm(it).addPoint(meran); vm(it).addPoint(mutkopf) }
        waitUntil("the plan is routed", 120_000) { var ok = false; scenario.onActivity { ok = !vm(it).routing && vm(it).route != null }; ok }
        val line = Txt.s(Res.string.plan_reach_level, "②", 2)
        var said: String? = null
        waitUntil("the red line says T2 reaches ②", 10_000) { scenario.onActivity { said = vm(it).planGapText() }; said == line }
        scenario.onActivity { vm(it).planSize = PlanSize.MINI }
        val row = findFresh(By.text(line), 10_000)
        assertNotNull("\"$line\" in the plan sheet", row)
        shot("reach-line")
        row!!.click()
        // the section: drawn (amber, like a steep section) and the map went there – from the route's end up to ②
        waitUntil("the section is drawn", 5_000) { var s: List<com.onpath.app.routing.GeoPoint>? = null; scenario.onActivity { s = vm(it).spotLine }; (s?.size ?: 0) >= 2 }
        var piece: List<com.onpath.app.routing.GeoPoint> = emptyList()
        scenario.onActivity { piece = vm(it).spotLine.orEmpty() }
        assertTrue("the section ends at ②", com.onpath.app.data.Geo.distance(piece.last(), mutkopf) < 150)
        Thread.sleep(2_500) // the camera flies there
        var centre: LatLng? = null
        onMap { m -> centre = m.cameraPosition.target }
        val c = com.onpath.app.routing.GeoPoint(centre!!.latitude, centre!!.longitude)
        assertTrue("the map shows the section (centre ${c.lat}, ${c.lon})", piece.any { com.onpath.app.data.Geo.distance(it, c) < 1_500 })
        shot("reach-section")
    }

    /**
     * Owner 2026-11-07 ("saved routes load without routing again" – the S10 took 67 s for a 723 km plan, and every load
     * routed it again): a route is planned, saved and cleared; the legs routed in this process are forgotten as after an
     * app restart; loaded from My routes, its line is there at once from the phone's leg store – no router run, the same
     * line. Real routing data.
     */
    @Test fun aSavedRouteLoadsFromMyRoutesWithoutRoutingAgain() {
        org.junit.Assume.assumeTrue("real routing data needed (E10_N45.rd5 in /data/local/tmp)", realRouting)
        val pts = listOf(com.onpath.app.routing.GeoPoint(46.67135, 11.16464), com.onpath.app.routing.GeoPoint(46.67500, 11.15000),
            com.onpath.app.routing.GeoPoint(46.68000, 11.14000))
        scenario.onActivity { vm(it).selectActivity(com.onpath.app.routing.Activity.BIKE_TOURING) }
        scenario.onActivity { a -> pts.forEach { vm(a).addPoint(it) } }
        waitUntil("the plan is routed – ${state()}", 120_000) {
            var ok = false
            scenario.onActivity { val v = vm(it); ok = !v.routing && v.waypoints.size == 3 && v.route?.legs?.none { l -> l.straight } == true }
            ok
        }
        val name = "E2E stored line ${System.currentTimeMillis() % 100_000}"
        var line = emptyList<com.onpath.app.routing.GeoPoint>()
        scenario.onActivity { line = vm(it).route!!.points; vm(it).saveRoute(name) }
        waitUntil("the plan is saved", 10_000) { var s = true; scenario.onActivity { s = vm(it).planUnsaved }; !s }
        Thread.sleep(1_500) // the legs under the points moved onto the line are written (one small file each)
        scenario.onActivity { vm(it).clearPlan(); vm(it).forgetLegsInMemoryForTest() }
        waitUntil("the map is empty", 10_000) { waypointCount() == 0 }
        val db = com.onpath.app.data.TourDb.get(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            scenario.onActivity { a -> vm(a).routesTab = com.onpath.app.ui.RoutesTab.PLANNED; vm(a).openRoutes() }
            findFresh(By.text(name), 8_000)!!.click()
            val plan = findFresh(By.text(Txt.s(Res.string.routes_overview_plan)), 8_000)
            assertNotNull("the overview with \"Planen\" – ${state()}", plan)
            val t0 = android.os.SystemClock.elapsedRealtime()
            plan!!.click()
            var loaded = emptyList<com.onpath.app.routing.GeoPoint>()
            waitUntil("the line is there at once – ${state()}", 5_000) {
                var ok = false
                scenario.onActivity { val v = vm(it); ok = v.waypoints.size == 3 && !v.routing && v.route != null; if (ok) loaded = v.route!!.points }
                ok
            }
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            shot("stored-line-loaded")
            org.junit.Assert.assertEquals("the same line as saved", line, loaded)
            val trace = com.onpath.app.debug.Problems.events().map { it.message }.reversed() // oldest first
            val load = trace.indexOfLast { it.startsWith("Loading \"$name\"") }
            assertTrue("the load traced: ${trace.takeLast(20)}", load >= 0)
            assertTrue("every leg from the store: ${trace[load]}", trace[load].contains("all 2 legs stored – no routing"))
            val run = trace.drop(load).firstOrNull { it.startsWith("Route #") && it.contains("ready") }
            assertNotNull("the plan run after the load: ${trace.takeLast(20)}", run)
            // the load read both legs from the store into the cache (above); the run takes them from there – no router try
            assertTrue("no router run, both legs from the cache: $run", run!!.contains("tries: 2 legs from the cache") && !run.contains(" pts "))
            android.util.Log.i("OnPath", "E2E stored line: loaded in $ms ms")
        } finally {
            scenario.onActivity { vm(it).tours.filter { t -> t.name == name }.forEach { t -> db.deleteTour(t.id) } }
        }
    }
}

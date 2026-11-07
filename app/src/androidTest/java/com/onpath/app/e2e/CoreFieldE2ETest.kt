package com.onpath.app.e2e

import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.common_cancel
import com.onpath.strings.routes_overview_follow
import com.onpath.strings.common_resume
import com.onpath.strings.nav_action_pause
import com.onpath.strings.nav_end_short
import com.onpath.strings.nav_end_tour_title
import com.onpath.strings.nav_arrived_title
import com.onpath.strings.nav_end_tour
import com.onpath.strings.nav_keep_recording
import com.onpath.strings.plan_guide_find_title
import com.onpath.strings.nav_hold_hint
import com.onpath.strings.nav_pause_band_line
import com.onpath.strings.nav_leave_go
import com.onpath.strings.nav_leave_stay
import com.onpath.strings.nav_settings_cd
import com.onpath.strings.settings_group_offroute
import com.onpath.strings.nav_leave_title
import com.onpath.strings.plan_go_here
import com.onpath.strings.plan_locate_far_cd
import com.onpath.strings.plan_new_during_tour
import com.onpath.strings.plan_undo
import com.onpath.strings.search_card_set_start
import com.onpath.strings.nav_profile_hide
import com.onpath.strings.nav_profile_show
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.domain.tour.UnlockWindow
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.AppMode
import com.onpath.app.ui.RoutesTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/**
 * CORE FEATURE C1 under field conditions (the plain emulator test alone is not enough): a moving GPS position in Meran, the map following it in Plan, "Go here" from my location, real
 * routing data when available (points snapped to ways), Navigate → back to Plan with the plan being followed.
 */
@RunWith(AndroidJUnit4::class)
class CoreFieldE2ETest : E2EBase() {
    @Volatile private var walking = true
    @Volatile private var lat = 46.6714
    @Volatile private var lon = 11.1700

    private fun sh(cmd: String) = device.executeShellCommand(cmd)

    @Before fun setUp() {
        sh("appops set com.android.shell android:mock_location allow")
        // Both providers the app listens to: on a real phone the network provider otherwise reports where the phone
        // really is, the map follows that and the plan's points end up off screen.
        for (p in PROVIDERS) {
            sh("cmd location providers add-test-provider $p")
            sh("cmd location providers set-test-provider-enabled $p true")
        }
        fix()
        // a walker: a fix every 0.5 s, ~1.4 m/s north-east
        thread(isDaemon = true) {
            while (walking) { lat += 0.0000045; lon += 0.0000065; fix(); Thread.sleep(500) }
        }
        launch()
        waitUntil("GPS fix in the app", 20_000) { var ok = false; scenario.onActivity { ok = vm(it).tracker.location.value != null }; ok }
    }

    @After fun stopGps() {
        walking = false
        try { scenario.onActivity { if (vm(it).recState.value != null) vm(it).stopRecording() } } catch (_: Exception) {} // expected: app already closed
        for (p in PROVIDERS) sh("cmd location providers remove-test-provider $p")
    }

    private fun fix() { for (p in PROVIDERS) sh("cmd location providers set-test-provider-location $p --location $lat,$lon --accuracy 4") }

    private companion object { val PROVIDERS = listOf("gps", "network") }

    private fun follow() { scenario.onActivity { vm(it).followMe = true }; Thread.sleep(1_500) }

    /** "Go here" from my location, a stop added, then both deleted by holding them – while the map follows me. */
    @Test fun goHereFromMyLocationThenDeleteWhileFollowing() {
        follow()
        val (w, h) = mapSize()
        hold(w * 0.5f, h * 0.25f)
        clickText(Txt.s(Res.string.plan_go_here))
        waitUntil("route from my location", 20_000) { waypointCount() == 2 }
        hold(w * 0.30f, h * 0.30f)
        waitUntil("a stop added", 20_000) { waypointCount() == 3 }
        routeSettled() // route + snapping
        follow() // the map keeps following while the user edits (camera moves under the finger)
        holdOnWaypoint(1)
        clickText("Delete")
        waitUntil("stop deleted", 15_000) { waypointCount() == 2 }
        Thread.sleep(1_500)
        holdOnWaypoint(1) // the destination
        clickText("Delete")
        waitUntil("destination deleted", 15_000) { waypointCount() == 1 }
    }

    /**
     * A tour from the plan: Navigate shows its points (not editable), back to Plan (the plan is being followed) – its
     * points must still be editable.
     */
    @Test fun navigateThenBackToPlanStillDeletes() {
        startPlanWithTwoPoints()
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { var m: AppMode? = null; scenario.onActivity { m = vm(it).mode }; m == AppMode.NAVIGATE }
        Thread.sleep(2_000)
        assertPositionDotOnTop("navigate")
        // Navigate shows the plan's numbered points while the tour follows the plan (owner 2026-11-01: "sonst weiß man
        // gar nicht, wo man einen Punkt gemacht hat") – not editable there (MapHost edits only when canEditMap)
        waitUntil("plan markers in Navigate (the tour follows the plan)", 10_000) { drawnMarkers() > 0 }
        var editable = true; scenario.onActivity { editable = vm(it).canEditMap }
        assertFalse("plan points not editable in Navigate", editable)
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { var m: AppMode? = null; scenario.onActivity { m = vm(it).mode }; m == AppMode.PLAN }
        Thread.sleep(2_000)
        assertPositionDotOnTop("plan during a tour")
        waitUntil("plan markers back in Plan", 10_000) { drawnMarkers() > 0 }
        holdOnWaypoint(1)
        assertNotNull(device.wait(Until.findObject(By.text("Delete")), 3_000))
        clickText("Delete")
        waitUntil("point deleted during a tour", 15_000) { waypointCount() == 1 }
        var rec = false; scenario.onActivity { rec = vm(it).recState.value != null }
        assertTrue("recording goes on in Plan (core feature C4)", rec)
    }

    private fun mode(): AppMode? { var m: AppMode? = null; scenario.onActivity { m = vm(it).mode }; return m }
    /** The app draws a followed line and the service navigates one. */
    private fun guided(): Boolean {
        var g = false
        scenario.onActivity { g = vm(it).followLine != null && vm(it).recState.value?.nav != null }
        return g
    }
    private fun recording(): Boolean { var r = false; scenario.onActivity { r = vm(it).recState.value != null }; return r }

    /**
     * The plan's route is computed – a state, not a fixed 3 s: the first real route on a cold GitHub emulator took 18 s
     * (run 42371488087), the tour then started before it ("nothing followed") and never followed it. Never fails: a plan
     * that gets no route goes on after [maxMs], as the fixed sleep did.
     */
    private fun routeSettled(maxMs: Long = 30_000) {
        Thread.sleep(300) // the edit's routing starts
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            var done = false
            scenario.onActivity {
                val v = vm(it)
                val r = v.route
                done = !v.routing && (v.waypoints.size < 2 || (r != null && r.legs.size >= v.waypoints.size - 1))
            }
            if (done) break
            Thread.sleep(100)
        }
        Thread.sleep(700) // snapping + camera, as before
    }

    /**
     * Owner rule: what Plan shows = what Navigate follows (never an old route followed unseen). Navigate → Plan → delete
     * all points (✕ in the list) →
     * Navigate: no route line, no guidance, the recording goes on. Undo → the points and the guidance are back.
     */
    @Test fun deletingAllPointsDuringATourEndsTheGuidanceUndoBringsItBack() {
        startPlanWithTwoPoints()
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { guided() }
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
        scenario.onActivity { vm(it).deletePointFromList(1) }
        waitUntil("one point left", 10_000) { waypointCount() == 1 }
        Thread.sleep(1_500)
        scenario.onActivity { vm(it).deletePointFromList(0) }
        waitUntil("plan empty", 10_000) { waypointCount() == 0 }
        scenario.onActivity { vm(it).continueNavigation() }
        waitUntil("Navigate again", 10_000) { mode() == AppMode.NAVIGATE }
        waitUntil("no route followed any more", 15_000) { !guided() }
        assertTrue("the recording goes on", recording())
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
        scenario.onActivity { vm(it).undoStep() }
        waitUntil("Undo: one point back", 10_000) { waypointCount() == 1 }
        scenario.onActivity { vm(it).undoStep() }
        waitUntil("Undo: two points back", 10_000) { waypointCount() == 2 }
        waitUntil("the route is followed again", 30_000) { guided() }
        assertTrue(recording())
        // every step is in the event trace (Undo / Clear / tour start must not have to be guessed)
        val trace = com.onpath.app.debug.Problems.events().map { it.message }
        for (step in listOf("Tour start", "All points deleted during the tour", "Undo: plan 0 → 1 points",
                "Undo: plan 1 → 2 points", "Plan points back during the tour")) {
            assertTrue("\"$step\" traced: ${trace.take(30)}", trace.any { it.startsWith(step) })
        }
    }

    /**
     * Owner 2026-11-06 22:05 (core feature C4; was the question "Plan laden? Du bist gerade unterwegs."): My routes → a tap
     * on a planned route during a tour follows it at once – no question, Navigate, the same tour records on, its activity
     * stays; Navigate says "Neuer Plan – deine Aufnahme läuft weiter" and its "Rückgängig" brings the plan from before back,
     * which the tour follows again.
     */
    @Test fun aSavedRouteTappedDuringATourIsFollowedAtOnceAndUndoBringsThePlanBack() {
        startPlanWithTwoPoints()
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { guided() }
        var tourId = -1L
        var before: List<GeoPoint> = emptyList()
        var activityBefore: Activity? = null
        scenario.onActivity {
            tourId = vm(it).recState.value!!.tourId; before = vm(it).waypoints.toList(); activityBefore = vm(it).activity
        }
        // a saved route nearby, for another activity
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val pts = listOf(GpxWaypoint(lat + 0.002, lon - 0.001, "E2E start"), GpxWaypoint(lat + 0.004, lon + 0.002, "E2E goal"))
        val name = "E2E saved route"
        val savedId = db.insertTour(TourKind.PLANNED, name, Activity.BIKE_TOURING.name, 2, 350.0, 0.0, 0.0, 90.0,
            listOf(pts.map { TrackPoint(it.lat, it.lon) }), pts)
        try {
            scenario.onActivity { vm(it).switchToPlan() }
            waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
            scenario.onActivity { vm(it).routesTab = RoutesTab.PLANNED; vm(it).openRoutes() }
            // the list is read on the IO threads (slow on GitHub's emulator); an older read must never hide the route
            // (RoutesState.show – E2E 2026-11-07: the list read at the tour start came back last)
            waitUntil("My routes lists \"$name\"", 20_000) {
                var listed = false; scenario.onActivity { listed = vm(it).tours.any { t -> t.id == savedId } }; listed
            }
            clickText(name)
            // a row opens its overview first (owner 2026-11-07); during the tour its primary is "Folgen"
            clickText(Txt.s(Res.string.routes_overview_follow))
            waitUntil("Navigate at once, no question", 10_000) { mode() == AppMode.NAVIGATE }
            val loaded = pts.map { GeoPoint(it.lat, it.lon) }
            waitUntil("the saved route is the plan", 10_000) { var w: List<GeoPoint> = emptyList(); scenario.onActivity { w = vm(it).waypoints.toList() }; w == loaded }
            val line = Txt.s(Res.string.plan_new_during_tour)
            assertNotNull("\"$line\" on Navigate", device.wait(Until.findObject(By.text(line)), 5_000))
            scenario.onActivity {
                assertFalse("My routes closed", vm(it).routesOpen)
                assertEquals("the activity stays", activityBefore, vm(it).activity)
                assertEquals("the same tour records on", tourId, vm(it).recState.value?.tourId)
            }
            waitUntil("the loaded route is followed", 20_000) { guided() }
            clickText(Txt.s(Res.string.plan_undo))
            waitUntil("Rückgängig: the plan from before is back", 10_000) {
                var w: List<GeoPoint> = emptyList(); scenario.onActivity { w = vm(it).waypoints.toList() }; w == before
            }
            waitUntil("the line is gone", 5_000) { device.findObject(By.text(line)) == null }
            waitUntil("the plan from before is followed again", 20_000) { guided() }
            assertEquals("still Navigate", AppMode.NAVIGATE, mode())
            assertEquals("still recording, not ended", "active", db.getTour(tourId)?.state)
            val trace = com.onpath.app.debug.Problems.events().map { it.message }
            for (step in listOf("Plan \"$name\" tapped during the tour: followed at once", "Rückgängig on \"Neuer Plan\"")) {
                assertTrue("\"$step\" traced: ${trace.take(30)}", trace.any { it.startsWith(step) })
            }
        } finally {
            scenario.onActivity { vm(it).routesOpen = false }
            db.deleteTour(savedId)
        }
    }

    /** The planned routes in My routes now (id set). */
    private fun plannedIds(): Set<Long> {
        var ids: Set<Long> = emptySet()
        scenario.onActivity { ids = vm(it).tours.filter { t -> t.kind == TourKind.PLANNED }.map { t -> t.id }.toSet() }
        return ids
    }

    /** Starts a tour on a new (never saved) two-point plan; returns the planned route saved at "Los geht's". */
    private fun startOnANewPlan(): Long {
        startPlanWithTwoPoints()
        routeSettled()
        val before = plannedIds()
        scenario.onActivity { assertTrue("a new plan is not saved yet", vm(it).planUnsaved) }
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("Los geht's saved the new plan in My routes → Planned", 15_000) { (plannedIds() - before).size == 1 }
        scenario.onActivity { assertFalse("the plan counts as saved now", vm(it).planUnsaved) }
        return (plannedIds() - before).single()
    }

    /**
     * Owner 2026-11-06 (the tour and the plan are two things, like Komoot) + 2026-11-07 ("Tour beenden" → back to the
     * start as after "✕ Neu planen"): "Los geht's" saves a new plan; "Tour speichern" asks nothing about the plan, saves
     * the tour, and the map is empty with the guide – the plan still in My routes, one Undo brings it back.
     */
    @Test fun endTourSavesTheTourKeepsThePlanAndClearsTheMap() {
        val planId = startOnANewPlan()
        val before = waypointList()
        var tourId = -1L
        scenario.onActivity { tourId = vm(it).recState.value!!.tourId; vm(it).endTour() }
        waitUntil("the tour has ended", 20_000) { !recording() }
        waitUntil("back at the start: the map is empty", 10_000) { waypointCount() == 0 }
        scenario.onActivity { assertTrue("no Save dialog after the tour", vm(it).saveAsk == null) }
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals("the tour is saved", "done", db.getTour(tourId)?.state)
        assertTrue("the plan is still in My routes", planId in plannedIds())
        assertEquals(AppMode.PLAN, mode())
        scenario.onActivity { vm(it).undoStep() }
        waitUntil("Undo brings the plan back", 10_000) { waypointList() == before }
        db.deleteTour(planId)
    }

    /**
     * Owner 2026-11-07: a saved plan changed and started again with "Los geht's" updates THAT route (like ☰ Plan
     * speichern) – no "Name (2)" copy; the version before is kept under Zuletzt.
     */
    @Test fun losGehtsWithAChangedSavedPlanUpdatesItAndKeepsTheOldUnderZuletzt() {
        val planId = startOnANewPlan()
        val old = waypointList()
        scenario.onActivity { vm(it).endTour() }
        waitUntil("the tour has ended", 20_000) { !recording() }
        waitUntil("back at the start (owner 2026-11-07)", 10_000) { waypointCount() == 0 }
        scenario.onActivity { vm(it).undoStep() } // the saved plan back on the map, as it was
        waitUntil("Undo: the saved plan is back", 10_000) { waypointList() == old }
        scenario.onActivity { assertFalse("back as saved", vm(it).planUnsaved) }
        // change the saved plan: one more point beyond the goal
        val goal = old.last()
        scenario.onActivity { vm(it).addPoint(GeoPoint(goal.lat + 0.004, goal.lon + 0.004)) }
        waitUntil("the plan has three points", 10_000) { waypointList().size == 3 }
        routeSettled()
        val changed = waypointList()
        scenario.onActivity { assertTrue("the changed saved plan counts as unsaved", vm(it).planUnsaved) }
        val idsBefore = plannedIds()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        fun key(points: List<GeoPoint>) = points.map { it.lat to it.lon }
        waitUntil("the saved route holds the changed plan", 15_000) {
            db.getTour(planId)?.waypoints?.map { it.lat to it.lon } == key(changed)
        }
        // under Zuletzt – or, when another saved route already has exactly these points (an earlier test's), there: the
        // draft rules never duplicate a saved route, and the version is not lost either way
        waitUntil("the version before is still in My routes (Zuletzt)", 15_000) {
            var others: List<List<Pair<Double, Double>>> = emptyList()
            scenario.onActivity {
                others = vm(it).tours.filter { t -> t.kind == TourKind.PLANNED && t.id != planId }
                    .map { t -> t.waypoints.map { w -> w.lat to w.lon } }
            }
            key(old) in others
        }
        var saved: List<Long> = emptyList()
        scenario.onActivity { saved = vm(it).tours.filter { t -> t.kind == TourKind.PLANNED && !t.draft }.map { t -> t.id } }
        assertEquals("no new saved route – no \"Name (2)\"", idsBefore.filter { it in saved }.toSet(), saved.toSet())
        scenario.onActivity { assertFalse("the plan counts as saved now", vm(it).planUnsaved) }
        scenario.onActivity { vm(it).endTour() }
        waitUntil("the tour has ended", 20_000) { !recording() }
        scenario.onActivity { vm(it).tours.filter { t -> t.draft && t.waypoints.map { w -> w.lat to w.lon } == key(old) }.forEach { t -> db.deleteTour(t.id) } }
        db.deleteTour(planId)
    }

    /**
     * The same for "Löschen" (flow map H2): only the tour goes to the bin; the plan stays in My routes, the map is back
     * at the start (owner 2026-11-07), Undo brings it back.
     */
    @Test fun endTourDeleteBinsTheTourAndKeepsThePlan() {
        val planId = startOnANewPlan()
        val before = waypointList()
        var tourId = -1L
        scenario.onActivity { tourId = vm(it).recState.value!!.tourId; vm(it).endTourAndDelete() }
        waitUntil("the tour has ended", 20_000) { !recording() }
        Thread.sleep(1_500)
        scenario.onActivity { assertTrue("no Save dialog after Löschen", vm(it).saveAsk == null) }
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("the tour is in the bin", (db.getTour(tourId)?.deletedMs ?: 0L) > 0L)
        waitUntil("back at the start: the map is empty", 10_000) { waypointCount() == 0 }
        assertTrue("the plan is still in My routes", planId in plannedIds())
        scenario.onActivity { vm(it).undoStep() }
        waitUntil("Undo brings the plan back", 10_000) { waypointList() == before }
        db.deleteTour(planId)
    }

    /**
     * Owner 2026-11-07: at the goal "Du bist angekommen." offers "Weiter aufzeichnen" | "Tour beenden"; "Tour beenden"
     * saves the tour (in My routes → Gemacht), the plan saved at "Los geht's" stays in My routes → Planned, and the map
     * is back at the start – empty, with the empty sheet's guide.
     */
    @Test fun arrivalEndTourBringsTheStartScreen() {
        walking = false
        Thread.sleep(800)
        val start = GeoPoint(lat, lon)
        val goal = GeoPoint(lat + 0.0035, lon + 0.0020)    // ~430 m away
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = plannedIds()
        scenario.onActivity { vm(it).addPoint(start); vm(it).addPoint(goal) }
        waitUntil("two points", 10_000) { waypointCount() == 2 }
        waitUntil("the route", 30_000) { var ok = false; scenario.onActivity { ok = (vm(it).route?.points?.size ?: 0) >= 2 }; ok }
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { guided() }
        waitUntil("Los geht's saved the plan", 15_000) { (plannedIds() - before).size == 1 }
        val planId = (plannedIds() - before).single()
        var tourId = -1L
        scenario.onActivity { tourId = vm(it).recState.value!!.tourId }
        val line = com.onpath.app.recording.RecordingService.state.value!!.nav!!.line
        try {
            // ride to the goal (the demo position feeds the service like GPS – walking pace, × 20)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                com.onpath.app.recording.DemoLocation.startRide(com.onpath.app.recording.DemoPath.constant(line, 1.4)!!, "E2E arrival", 20)
            }
            waitUntil("\"Du bist angekommen.\"", 120_000) { var a = false; scenario.onActivity { a = vm(it).arrivalAsk }; a }
            assertNotNull("the arrival box", findFresh(By.text(Txt.s(Res.string.nav_arrived_title)), 10_000))
            assertNotNull("Weiter aufzeichnen", findFresh(By.text(Txt.s(Res.string.nav_keep_recording)), 5_000))
            assertNotNull("Tour beenden", findFresh(By.text(Txt.s(Res.string.nav_end_tour)), 5_000))
            shot("arrival-box")
            Thread.sleep(com.onpath.app.domain.tour.EndSheetGuard.GUARD_MS) // the box ignores a press in its first half second
            clickText(Txt.s(Res.string.nav_end_tour))
            waitUntil("the tour has ended", 30_000) { !recording() }
            waitUntil("the tour is saved", 15_000) { db.getTour(tourId)?.state == "done" }
            waitUntil("in My routes → Gemacht", 15_000) {
                var ok = false; scenario.onActivity { ok = vm(it).tours.any { t -> t.id == tourId && t.kind == TourKind.RECORDED } }; ok
            }
            assertTrue("the plan in My routes → Planned", planId in plannedIds())
            waitUntil("the map is empty", 10_000) { waypointCount() == 0 }
            assertEquals("back in Plan", AppMode.PLAN, mode())
            scenario.onActivity { vm(it).closeSavedTour() } // the "Tour gespeichert" box
            assertNotNull("the empty sheet's guide", findFresh(By.text(Txt.s(Res.string.plan_guide_find_title)), 10_000))
            shot("start-after-arrival")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { com.onpath.app.recording.DemoLocation.stop("E2E arrival done") }
            db.deleteTour(planId)
        }
    }

    /**
     * Field bug (owner 2026-11-06, onpath.private#39): during a tour the START of a start → goal plan was deleted (hold →
     * Delete) – the plan had one point left, the sheet said "Neuer Plan", but the tour still followed the OLD line with
     * the deleted start. Owner: one point left during a tour = "Hierhin": the plan becomes position → that point, the
     * sheet shows that route and the tour follows exactly it. Undo brings the plan and its guidance back.
     */
    @Test fun deletingTheStartDuringATourRoutesFromHereToTheOnePointLeft() {
        startPlanWithTwoPoints()
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { guided() }
        var plan: List<GeoPoint> = emptyList()
        var oldLine: List<GeoPoint>? = null
        scenario.onActivity { plan = vm(it).waypoints.toList(); oldLine = vm(it).followLine }
        val goal = plan[1]
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
        // as in the field: hold on point 1 → Delete
        scenario.onActivity { vm(it).openPointMenu(0, via = "e2e"); vm(it).deletePointFromMenu() }
        waitUntil("the plan is position → the point left", 20_000) {
            val w = waypointList(); w.size == 2 && w[1] == goal && w[0] != plan[0]
        }
        waitUntil("the sheet has a route for it", 30_000) { var ok = false; scenario.onActivity { ok = (vm(it).route?.points?.size ?: 0) >= 2 }; ok }
        waitUntil("the tour follows position → the point left, not the old line", 30_000) {
            var ok = false
            scenario.onActivity {
                val l = vm(it).followLine
                val here = vm(it).tracker.location.value?.let { p -> GeoPoint(p.latitude, p.longitude) }
                ok = l != null && l != oldLine && here != null &&
                    com.onpath.app.data.Geo.distance(l.first(), here) < 150.0 &&
                    com.onpath.app.data.Geo.distance(l.last(), goal) < 150.0
            }
            ok
        }
        assertTrue("the recording goes on", recording())
        scenario.onActivity { vm(it).undoStep() }
        waitUntil("Undo: the two points from before", 10_000) { waypointList() == plan }
        waitUntil("Undo: the old guidance again", 30_000) { var ok = false; scenario.onActivity { ok = vm(it).followLine == oldLine }; ok }
        val trace = com.onpath.app.debug.Problems.events().map { it.message }
        assertTrue("traced: ${trace.take(30)}", trace.any { it.startsWith("One point left during the tour") })
    }

    /**
     * Field bug 2026-11-07, tour 207 (owner, Pixel): at home in Hilden with the plan "Meran → Mutkopf" ~700 km away, Los
     * geht's, then Hike T1 → T5 in the activity sheet: every row was routed Hilden → Meran (15 s timeout each, ~90 s of
     * spinners) and T5 never came. Now nothing is routed before a choice; the T5 chip routes Hike T5 alone from the
     * plan's start, and – the route never reached – the whole plan is planned again as T5 and followed: the line reaches
     * point 2, which T1 cannot (its line ends ~550 m short – the path up is above T1). Unit tests: ActivityCheckTest
     * (farFromARouteNeverReached…), ActivityCheckRulesTest.onlyTheChosenRowIsRouted….
     */
    @Test fun farFromThePlanTheT5ChipPlansItAgainAndReachesThePointT1CouldNot() {
        org.junit.Assume.assumeTrue("needs the real routing data (E10_N45.rd5)", realRouting)
        val meran = GeoPoint(46.67135, 11.16464)
        val mutkopf = GeoPoint(46.71077, 11.13787)
        fun dist(a: GeoPoint, b: GeoPoint) = com.onpath.app.data.Geo.distance(a, b)
        // at home, 700 km away
        lat = 51.16926; lon = 6.92544
        waitUntil("the position at home", 20_000) {
            var ok = false
            scenario.onActivity { ok = vm(it).tracker.location.value?.let { p -> p.latitude > 51.0 } == true }
            ok
        }
        scenario.onActivity { vm(it).selectActivity(Activity.HIKE); vm(it).selectSac(1) }
        scenario.onActivity { vm(it).addPoint(meran); vm(it).addPoint(mutkopf) }
        waitUntil("two points", 10_000) { waypointCount() == 2 }
        waitUntil("the T1 plan routed", 60_000) { var ok = false; scenario.onActivity { ok = !vm(it).routing && vm(it).route != null }; ok }
        var t1End: GeoPoint? = null
        scenario.onActivity { t1End = vm(it).route?.points?.lastOrNull() }
        assertTrue("T1 cannot reach point 2 (ends ${dist(t1End!!, mutkopf).toInt()} m from it)", dist(t1End!!, mutkopf) > 200.0)
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the plan is followed", 20_000) { guided() }
        var reached = true
        scenario.onActivity { reached = vm(it).recState.value?.nav?.session?.reached ?: true }
        assertFalse("700 km away the route was never reached", reached)

        scenario.onActivity { vm(it).openActivityCheck() }
        var rows: List<com.onpath.app.ui.ActivityRowState> = emptyList()
        scenario.onActivity { rows = vm(it).activityCheck?.rows?.map { r -> r.state }.orEmpty() }
        assertTrue("nothing routed before a choice: $rows", rows.isNotEmpty() && rows.all { it == com.onpath.app.ui.ActivityRowState.Idle })
        val t0 = System.currentTimeMillis()
        scenario.onActivity { vm(it).chooseHikeLevel(5) }
        waitUntil("the tour follows Hike T5 to point 2", 60_000) {
            var ok = false
            scenario.onActivity {
                val s = vm(it).recState.value?.nav?.session
                val l = vm(it).followLine
                ok = s != null && s.activity == Activity.HIKE && s.sac == 5 && l != null && dist(l.last(), mutkopf) < 60.0
            }
            ok
        }
        val took = System.currentTimeMillis() - t0
        scenario.onActivity {
            assertEquals("the plan is Hike T5 now", 5, vm(it).sac)
            assertTrue("the plan line reaches point 2", vm(it).route?.points?.lastOrNull()?.let { p -> dist(p, mutkopf) < 60.0 } == true)
            assertTrue("the sheet closed", vm(it).activityCheck == null)
        }
        assertTrue("recording goes on", recording())
        assertTrue("one routing of the plan, not six 15 s timeouts ($took ms)", took < 40_000)
        val trace = com.onpath.app.debug.Problems.events().map { it.message }
        assertTrue("routed from the route's start: ${trace.takeLast(30)}",
            trace.any { it.startsWith("Activity check: routing the rest for HIKE T5 from the route's start") })
        assertFalse("never routed from the position", trace.any { it.startsWith("Activity check: routing the rest") && it.endsWith("from the position") })
    }

    /**
     * Field 2026-11-07 10:45 (Pixel, tour "Gewerbepark Süd → Unterstand"): a held place during a tour offered "Als Start
     * setzen" instead of joining the route the tour follows. Now: the POI menu says what really happens (the same
     * insertion as the add), a place beside the route is stop 2 between the points, and the tour follows the new route.
     */
    @Test fun aPlaceAddedDuringATourIsAStopBetweenNotANewStart() {
        startPlanWithTwoPoints()
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { guided() }
        var plan: List<GeoPoint> = emptyList()
        var oldLine: List<GeoPoint>? = null
        scenario.onActivity { plan = vm(it).waypoints.toList(); oldLine = vm(it).followLine }
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
        // a place a little beside the middle of the route (a POI menu adds it this way)
        val mid = GeoPoint((plan[0].lat + plan[1].lat) / 2 + 0.0003, (plan[0].lon + plan[1].lon) / 2)
        val poi = com.onpath.app.poi.Poi("e2e-shelter", "shelter", "Unterstand E2E", mid.lat, mid.lon)
        var label = ""
        var menu: com.onpath.app.ui.PlaceAddMenu.Menu? = null
        scenario.onActivity { label = vm(it).poiAddLabel(poi); menu = vm(it).placeMenu(poi) }
        assertFalse("never a new start during a tour: \"$label\"", label == Txt.s(Res.string.search_card_set_start))
        // beside the middle: a clear stop – the menu offers "Als Zwischenstopp" (owner 2026-11-07), no start
        assertEquals("the menu: $menu", listOf(com.onpath.app.ui.PlaceAddMenu.Item.ADD_STOP), menu?.main)
        scenario.onActivity { vm(it).addPoiAsStop(poi) }
        waitUntil("3 points, the place between start and goal", 10_000) {
            val w = waypointList()
            w.size == 3 && w[0] == plan[0] && w[2] == plan[1] &&
                com.onpath.app.data.Geo.distance(w[1], mid) < 5.0
        }
        waitUntil("the tour follows the new route", 30_000) {
            var ok = false
            scenario.onActivity { val l = vm(it).followLine; ok = l != null && l != oldLine }
            ok
        }
        assertTrue("the recording goes on", recording())
    }

    /**
     * Owner 2026-11-07 11:20: during a tour, a place past the goal → its menu says "Als neues Ziel" first; tapped, the plan
     * ends there (the old goal is dropped), one Undo brings the old goal back. The recording goes on throughout.
     */
    @Test fun aPlaceMadeTheDestinationDuringATourEndsThePlanThereUndoBringsTheGoalBack() {
        startPlanWithTwoPoints()
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { guided() }
        var plan: List<GeoPoint> = emptyList()
        scenario.onActivity { plan = vm(it).waypoints.toList() }
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
        // half the route again beyond the goal, in its direction
        val past = GeoPoint(plan[1].lat + (plan[1].lat - plan[0].lat) / 2, plan[1].lon + (plan[1].lon - plan[0].lon) / 2)
        val poi = com.onpath.app.poi.Poi("e2e-church", "place_of_worship", "Kirche E2E", past.lat, past.lon)
        var menu: com.onpath.app.ui.PlaceAddMenu.Menu? = null
        scenario.onActivity { menu = vm(it).placeMenu(poi) }
        assertEquals("the menu: $menu", com.onpath.app.ui.PlaceAddMenu.Item.NEW_DESTINATION, menu?.main?.firstOrNull())
        assertTrue("the stop stays reachable: $menu",
            com.onpath.app.ui.PlaceAddMenu.Item.ADD_STOP in menu!!.main + menu!!.more)
        scenario.onActivity { vm(it).poiAsNewDestination(poi) }
        waitUntil("the plan ends at the place, the old goal dropped", 10_000) {
            val w = waypointList()
            w.size == 2 && w[0] == plan[0] && com.onpath.app.data.Geo.distance(w[1], past) < 5.0
        }
        assertTrue("the recording goes on", recording())
        scenario.onActivity { vm(it).undoStep() }
        waitUntil("Undo: the old goal is back", 10_000) { waypointList() == plan }
        assertTrue("the recording goes on after Undo", recording())
    }

    /** A done tour (a recorded line) near the walker, for the follow-a-track cases below. */
    private fun insertDoneTour(name: String): Long {
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val line = (0..20).map { i -> TrackPoint(lat + 0.0003 * i, lon + 0.0002 * i) }
        return db.insertTour(TourKind.RECORDED, name, Activity.BIKE_TOURING.name, 2, 600.0, 0.0, 0.0, 300.0, listOf(line), emptyList())
    }

    private fun tourWith(id: Long) = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext).getTour(id)!!

    /**
     * A tour following a plan changed on the way (owner 2026-11-06: "Los geht's" saved the two-point plan – a third point
     * set during the tour makes it unsaved again – it goes under Zuletzt when it leaves the map). Returns the planned route saved at the start.
     */
    private fun tourWithAnUnsavedPlan(): Long {
        val planId = startOnANewPlan()
        waitUntil("the plan is followed", 20_000) { guided() }
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
        val (w, h) = mapSize()
        hold(w * 0.50f, h * 0.62f)
        waitUntil("a third point set on the way", 10_000) { waypointCount() == 3 }
        routeSettled()
        var unsaved = false
        scenario.onActivity { unsaved = vm(it).planUnsaved }
        assertTrue("the plan changed on the way is not saved", unsaved)
        return planId
    }

    private fun followName(): String? { var n: String? = null; scenario.onActivity { n = vm(it).followName }; return n }
    private fun drafts(): List<com.onpath.app.data.Tour> =
        TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext).listTours().filter { it.draft }

    /**
     * The draft of exactly these plan points (null = none yet). Counting all drafts is not enough: since the end of a
     * tour clears the plan the "Neu planen" way (owner 2026-11-07), a test that edits the plan on the way and ends its
     * tour in @After leaves its own plan under Zuletzt (GitHub run 2026-11-07: "kept under Zuletzt" saw two).
     */
    private fun draftOf(pts: List<GeoPoint>): com.onpath.app.data.Tour? = drafts().firstOrNull { d ->
        d.waypoints.size == pts.size &&
            d.waypoints.zip(pts).all { (a, b) -> kotlin.math.abs(a.lat - b.lat) < 1e-6 && kotlin.math.abs(a.lon - b.lon) < 1e-6 }
    }

    /**
     * Flow map M1 + owner 2026-11-06 ("Zuletzt"): a done tour tapped during a tour is followed – no question; the plan built on the
     * way leaves the map, kept under My routes → Zuletzt, and the tour follows the track alone. Undo brings the plan back.
     */
    @Test fun followingATrackDuringATourKeepsTheUnsavedPlanUnderZuletzt() {
        val planId = tourWithAnUnsavedPlan()
        val name = "E2E done tour ${System.currentTimeMillis() % 100_000}"
        val id = insertDoneTour(name)
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            scenario.onActivity { vm(it).requestOpen(tourWith(id)) }
            waitUntil("the plan has left the map", 10_000) { waypointCount() == 0 }
            waitUntil("the tour follows the track", 10_000) { followName() == name }
            assertTrue("the recording goes on", recording())
            waitUntil("the plan is under Zuletzt", 10_000) { drafts().any { it.waypoints.size == 3 } }
            scenario.onActivity { vm(it).undoStep() }
            waitUntil("Undo brings the plan back", 10_000) { waypointCount() == 3 }
        } finally {
            db.deleteTour(id); db.deleteTour(planId); drafts().forEach { db.deleteTour(it.id) }
        }
    }

    /**
     * Owner 2026-11-06 ("Zuletzt" in My routes): a new plan cleared → kept under Zuletzt, no question; Speichern there makes
     * it a saved plan; Löschen moves another one to the recycle bin; "Los geht's" saves the plan it starts with.
     */
    @Test fun aClearedPlanIsUnderZuletztSavedOrDeletedThere() {
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            // each plan gets an own third point: a plan with the points of a saved route (another test saved these two
            // at "Los geht's", and the first plan below is saved too) is no draft – RoutesState.keepsDraft
            val (w, h) = mapSize()
            startPlanWithTwoPoints()
            hold(w * 0.50f, h * 0.30f)
            waitUntil("a third point", 8_000) { waypointCount() == 3 }
            routeSettled()
            val first = waypointList()
            scenario.onActivity { vm(it).requestClear() }
            waitUntil("cleared at once, no question", 5_000) { waypointCount() == 0 }
            waitUntil("kept under Zuletzt", 10_000) { draftOf(first) != null }
            val draft = draftOf(first)!!
            scenario.onActivity { vm(it).saveDraft(draft) }
            waitUntil("saved: no longer a draft", 10_000) { db.getTour(draft.id)?.draft == false }
            scenario.onActivity { assertEquals(draft.id, vm(it).justSavedId) }
            // a second one, deleted from Zuletzt → recycle bin
            startPlanWithTwoPoints()
            hold(w * 0.45f, h * 0.65f)
            waitUntil("another third point", 8_000) { waypointCount() == 3 }
            routeSettled()
            val secondPts = waypointList()
            scenario.onActivity { vm(it).requestClear() }
            waitUntil("the second one under Zuletzt", 10_000) { draftOf(secondPts) != null }
            val second = draftOf(secondPts)!!
            scenario.onActivity { vm(it).deleteDraft(second) }
            waitUntil("in the recycle bin", 10_000) { (db.getTour(second.id)?.deletedMs ?: 0L) > 0L }
            db.deleteTour(draft.id); db.deleteTour(second.id)
        } finally {
            drafts().forEach { db.deleteTour(it.id) }
        }
    }

    /**
     * Owner 2026-11-06: ✕ Neu planen during a tour asks nothing ("Plan leeren? Deine Tour läuft weiter" gone) – the plan
     * changed on the way goes under Zuletzt, the recording goes on, Undo brings the plan back.
     */
    @Test fun clearingThePlanDuringATourAsksNothing() {
        val planId = tourWithAnUnsavedPlan()
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val changed = waypointList()
            scenario.onActivity { vm(it).requestClear() }
            waitUntil("cleared at once, no question", 5_000) { waypointCount() == 0 }
            assertTrue("the recording goes on", recording())
            waitUntil("the changed plan under Zuletzt", 10_000) { draftOf(changed) != null }
            scenario.onActivity { vm(it).undoStep() }
            waitUntil("Undo: the plan is back", 10_000) { waypointCount() == 3 }
        } finally {
            drafts().forEach { db.deleteTour(it.id) }
            db.deleteTour(planId)
        }
    }

    /** M1: a saved plan asks nothing – it stays in My routes, leaves the map, the tour follows the track. */
    @Test fun followingATrackDuringATourWithASavedPlanGoesAtOnce() {
        val planId = tourWithAnUnsavedPlan()
        val planName = "E2E saved plan ${System.currentTimeMillis() % 100_000}"
        scenario.onActivity { vm(it).requestSave() }
        waitUntil("Save's dialog", 5_000) { var a = false; scenario.onActivity { a = vm(it).saveAsk != null }; a }
        scenario.onActivity { vm(it).confirmSave(planName) }
        waitUntil("the plan is saved", 10_000) { var s = true; scenario.onActivity { s = vm(it).planUnsaved }; !s }
        val name = "E2E done tour ${System.currentTimeMillis() % 100_000}"
        val id = insertDoneTour(name)
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            scenario.onActivity { vm(it).requestOpen(tourWith(id)) }
            waitUntil("the tour follows the track", 10_000) { followName() == name }
            waitUntil("the plan has left the map", 10_000) { waypointCount() == 0 }
        } finally {
            db.deleteTour(id)
            db.deleteTour(planId) // saved at the start, overwritten (renamed) by the Save above
            scenario.onActivity { vm(it).tours.filter { t -> t.name == planName }.forEach { t -> db.deleteTour(t.id) } }
        }
    }


    /**
     * Owner 2026-11-07: one map orientation for the session ("bleibt immer in der eingestellten Richtung"). North up:
     * whole route, Navigate, back in Plan and locate all end north up, also after a hand rotation. Walking direction:
     * the map is never snapped north by a fit or by leaving Navigate.
     */
    @Test fun oneOrientationNorthStaysNorthAndTravelKeepsTheBearing() {
        var before: com.onpath.app.data.AppSettings? = null
        scenario.onActivity { before = vm(it).settings; vm(it).updateSettings(vm(it).settings.copy(navHeadingUp = false)) }
        fun bearing(): Double { var b = -1.0; onMap { m -> b = m.cameraPosition.bearing }; return b }
        fun turnByHand() = onMap { m ->
            m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                org.maplibre.android.camera.CameraPosition.Builder(m.cameraPosition).bearing(137.0).build()))
        }
        fun near(want: Double, got: Double) = kotlin.math.abs(((got - want + 540) % 360) - 180) < 2.0
        try {
            startPlanWithTwoPoints()
            routeSettled()
            // NORTH: a hand rotation, then "whole route" → north up
            turnByHand(); Thread.sleep(500)
            scenario.onActivity { vm(it).showWholeRoute() }
            Thread.sleep(1_500)
            assertTrue("north: whole route turns north (${bearing()})", near(0.0, bearing()))
            scenario.onActivity { vm(it).startNavigation(force = true) }
            waitUntil("Navigate", 20_000) { mode() == AppMode.NAVIGATE }
            Thread.sleep(2_000)
            assertTrue("north: Navigate north up (${bearing()})", near(0.0, bearing()))
            scenario.onActivity { vm(it).switchToPlan() }
            waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
            Thread.sleep(2_000)
            assertTrue("north: back in Plan north up (${bearing()})", near(0.0, bearing()))
            scenario.onActivity { vm(it).locate() }
            Thread.sleep(1_500)
            assertTrue("north: locate in Plan north up (${bearing()})", near(0.0, bearing()))
            // TRAVEL: Navigate, looked around by hand, back in Plan – the bearing stays; a fit keeps it too
            scenario.onActivity { vm(it).updateSettings(vm(it).settings.copy(navHeadingUp = true)) }
            scenario.onActivity { vm(it).continueNavigation(force = true) }
            waitUntil("Navigate again", 20_000) { mode() == AppMode.NAVIGATE }
            Thread.sleep(2_000)
            turnByHand(); Thread.sleep(800)
            scenario.onActivity { vm(it).switchToPlan() }
            waitUntil("back in Plan again", 10_000) { mode() == AppMode.PLAN }
            Thread.sleep(2_000)
            assertTrue("travel: leaving Navigate keeps the bearing (${bearing()})", near(137.0, bearing()))
            scenario.onActivity { vm(it).showWholeRoute() }
            Thread.sleep(1_500)
            assertTrue("travel: whole route keeps the bearing (${bearing()})", near(137.0, bearing()))
        } finally {
            scenario.onActivity { if (vm(it).recState.value != null) vm(it).stopRecording(); before?.let { s -> vm(it).updateSettings(s) } }
        }
    }

    /**
     * Owner 2026-11-06 ("Ich bin in Hilden, Plan in Köln – im Planmodus steht mein Standort, wo ist mein Plan?"): with
     * a plan, Navigate → Plan shows the whole plan, not the own position (PlanCamera).
     */
    @Test fun backInPlanTheMapShowsThePlanNotThePosition() = planInFrontAfterNavigate(walkingDirectionUp = false)

    /**
     * Owner 2026-11-07 ("Plan in Meran, ich in Hilden – Navigation, zurück in Plan, ich sehe meinen Standort"): the same
     * with the walking direction up in Navigate (the compass cycle) – Plan keeps following the position there until the
     * plan's fit; the fit must win, the map must not end on the position.
     */
    @Test fun backInPlanWithWalkingDirectionUpTheMapShowsThePlan() = planInFrontAfterNavigate(walkingDirectionUp = true)

    /**
     * Plan far away, me off the map (owner 2026-11-07, variant 3): the locate button says the distance ("Du bist … km
     * entfernt") and a tap frames the plan and my position together.
     */
    @Test fun locateOffTheMapShowsTheDistanceAndATapShowsPlanAndMe() {
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val far = listOf(GpxWaypoint(lat + 0.22, lon - 0.01, "E2E far start"), GpxWaypoint(lat + 0.24, lon + 0.02, "E2E far goal"))
        val name = "E2E far locate ${System.currentTimeMillis() % 100_000}"
        val id = db.insertTour(TourKind.PLANNED, name, Activity.HIKE.name, 2, 3_000.0, 0.0, 0.0, 3600.0,
            listOf(far.map { TrackPoint(it.lat, it.lon) }), far)
        try {
            scenario.onActivity { vm(it).refreshTours(); vm(it).openTour(db.getTour(id)!!) }
            waitUntil("the far plan is on the map", 10_000) { waypointCount() == 2 }
            Thread.sleep(3_000) // the plan is framed; my position is ~25 km south, off the map
            val prefix = Txt.s(Res.string.plan_locate_far_cd, "").substringBefore(" ").ifBlank { "Du" }
            val label = findFresh(By.desc(java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(prefix) + ".*km.*", java.util.regex.Pattern.DOTALL)), 8_000)
            assertNotNull("the locate button tells the distance while I am off the map", label)
            label!!.click()
            Thread.sleep(2_500) // fit animation
            var bounds: org.maplibre.android.geometry.LatLngBounds? = null
            onMap { m -> bounds = m.projection.visibleRegion.latLngBounds }
            val b = bounds!!
            assertTrue("my position is in view after the tap", b.contains(org.maplibre.android.geometry.LatLng(lat, lon)))
            assertTrue("the plan is in view too", b.contains(org.maplibre.android.geometry.LatLng(lat + 0.23, lon + 0.005)))
        } finally {
            db.deleteTour(id)
        }
    }

    private fun planInFrontAfterNavigate(walkingDirectionUp: Boolean) {
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        // a saved plan about 25 km north of the walker
        val far = listOf(GpxWaypoint(lat + 0.22, lon - 0.01, "E2E far start"), GpxWaypoint(lat + 0.24, lon + 0.02, "E2E far goal"))
        val name = "E2E far plan ${System.currentTimeMillis() % 100_000}"
        val id = db.insertTour(TourKind.PLANNED, name, Activity.HIKE.name, 2, 3_000.0, 0.0, 0.0, 3600.0,
            listOf(far.map { TrackPoint(it.lat, it.lon) }), far)
        try {
            var t: com.onpath.app.data.Tour? = null
            scenario.onActivity { vm(it).refreshTours(); t = db.getTour(id); vm(it).openTour(t!!) }
            waitUntil("the far plan is on the map", 10_000) { waypointCount() == 2 }
            // Navigate follows the own position
            scenario.onActivity { vm(it).startNavigation(force = true) }
            waitUntil("Navigate", 20_000) { mode() == AppMode.NAVIGATE }
            if (walkingDirectionUp) {
                scenario.onActivity { if (!vm(it).headingUp) vm(it).onCompass(false) }
                waitUntil("walking direction up", 5_000) { var h = false; scenario.onActivity { a -> h = vm(a).headingUp }; h }
            }
            Thread.sleep(2_500)
            scenario.onActivity { vm(it).switchToPlan() }
            waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
            Thread.sleep(3_000) // settle + fit animation
            Thread.sleep(3_000) // a following camera would have pulled the map back to the position by now
            var at: org.maplibre.android.geometry.LatLng? = null
            onMap { m -> at = m.cameraPosition.target }
            val c = at!!
            val toPlan = com.onpath.app.data.Geo.distance(c.latitude, c.longitude, lat + 0.23, lon + 0.005)
            val toMe = com.onpath.app.data.Geo.distance(c.latitude, c.longitude, lat, lon)
            assertTrue("the map shows the plan (${toPlan.toInt()} m from it), not the position (${toMe.toInt()} m)", toPlan < toMe && toPlan < 5_000)
        } finally {
            scenario.onActivity { if (vm(it).recState.value != null) vm(it).stopRecording() }
            if (walkingDirectionUp) scenario.onActivity { if (vm(it).headingUp) vm(it).onCompass(true) }
            db.deleteTour(id)
        }
    }

    /**
     * Flow map N4 (owner 2026-11-06): Android's back in Navigate while a tour records asks "App verlassen? Deine Tour wird
     * weiter aufgezeichnet." – Bleiben keeps Navigate; Verlassen sends the app to the background and the tour records on.
     */
    @Test fun backInNavigateDuringATourAsksAndLeavingKeepsTheTour() {
        startPlanWithTwoPoints()
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        Thread.sleep(1_000)
        var tourId = -1L
        scenario.onActivity { tourId = vm(it).recState.value!!.tourId }
        val title = Txt.s(Res.string.nav_leave_title)
        device.pressBack()
        assertNotNull("the question after back", findFresh(By.text(title), 5_000))
        clickText(Txt.s(Res.string.nav_leave_stay))
        // UiAutomator's node cache keeps the old tree on Android 14 – look afresh (E2EBase.goneFresh)
        assertTrue("the question is gone after Bleiben", goneFresh(By.text(title), 5_000))
        assertEquals("still in Navigate", AppMode.NAVIGATE, mode())
        assertEquals("the app is still in front", androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        device.pressBack()
        assertNotNull("the question again", findFresh(By.text(title), 5_000))
        clickText(Txt.s(Res.string.nav_leave_go))
        waitUntil("the app went to the background", 10_000) { scenario.state != androidx.lifecycle.Lifecycle.State.RESUMED }
        Thread.sleep(1_500)
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals("the tour records on", "active", db.getTour(tourId)?.state)
        assertTrue("the recording service still runs", com.onpath.app.recording.RecordingService.state.value != null)
        val trace = com.onpath.app.debug.Problems.events().map { it.message }
        assertTrue(trace.any { it.startsWith("Back in Navigate: left the app") })
    }

    /**
     * Owner 2026-11-06: the tour's ONE bottom bar – with the lock off (with the lock on the same, held): "Pause |
     * Planen"; ending only out of a pause ("Weiter · Beenden · ✎"); "Beenden" asks first (the end sheet).
     */
    /**
     * Owner 2026-11-07: Navigate's ⚙ opens the settings in the new look "on the way" – the Navigation page first – and
     * Android's back returns to Navigate with the tour still recording (no "App verlassen?", no switch to Plan).
     */
    @Test fun navigateGearOpensSettingsOnTheWayAndBackReturns() {
        var before: com.onpath.app.data.AppSettings? = null
        scenario.onActivity { before = vm(it).settings; vm(it).updateSettings(vm(it).settings.copy(navHoldMs = 0)) }
        try {
            startPlanWithTwoPoints()
            routeSettled()
            scenario.onActivity { vm(it).startNavigation(force = true) }
            waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
            val gear = findFresh(By.desc(Txt.s(Res.string.nav_settings_cd)), 10_000)
            assertNotNull("⚙ in Navigate's map controls", gear)
            gear!!.click()
            assertNotNull("the Navigation page's first group", findFresh(By.text(Txt.s(Res.string.settings_group_offroute).uppercase()), 10_000))
            var onTheWay = false
            scenario.onActivity { onTheWay = vm(it).showSettings && vm(it).settingsOnTheWay }
            assertTrue("settings on the way", onTheWay)
            assertTrue("the tour records on", recording())
            device.pressBack()
            waitUntil("settings closed", 5_000) { var s = true; scenario.onActivity { s = vm(it).showSettings }; !s }
            assertTrue("back in Navigate", mode() == AppMode.NAVIGATE)
            assertTrue("no 'App verlassen?' on that back", goneFresh(By.text(Txt.s(Res.string.nav_leave_title)), 2_000))
            assertTrue("still recording", recording())
        } finally {
            scenario.onActivity { vm(it).updateSettings(before!!) }
        }
    }

    @Test fun lockOffPauseThenEndOpensTheEndSheet() {
        var before: com.onpath.app.data.AppSettings? = null
        scenario.onActivity { before = vm(it).settings; vm(it).updateSettings(vm(it).settings.copy(navHoldMs = 0)) }
        try {
            startPlanWithTwoPoints()
            routeSettled()
            scenario.onActivity { vm(it).startNavigation(force = true) }
            waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
            assertNotNull("Pause at the bottom, no slider", findFresh(By.text(Txt.s(Res.string.nav_action_pause)), 10_000))
            assertTrue("no Beenden before a pause", goneFresh(By.text(Txt.s(Res.string.nav_end_short)), 2_000))
            clickText(Txt.s(Res.string.nav_action_pause))
            waitUntil("paused", 10_000) { com.onpath.app.recording.RecordingService.state.value?.paused == true }
            assertNotNull("Weiter at the bottom", findFresh(By.text(Txt.s(Res.string.common_resume)), 5_000))
            // the pause band (owner 2026-11-07, variant C): what stands still, over the buttons
            assertNotNull("the pause band says what stands still", findFresh(By.text(Txt.s(Res.string.nav_pause_band_line)), 5_000))
            clickText(Txt.s(Res.string.nav_end_short))
            assertNotNull("the end sheet", findFresh(By.text(Txt.s(Res.string.nav_end_tour_title)), 10_000))
            assertTrue("asking does not end the tour", recording())
        } finally {
            scenario.onActivity { vm(it).updateSettings(before!!) }
        }
    }

    /**
     * Owner 2026-11-06 22:20 (variant A): locked, the same bar's buttons fire only when held ~1 s – a short tap does
     * nothing but show "Gedrückt halten"; held Pause pauses, held Beenden opens the end sheet (asks first).
     */
    @Test fun lockOnHoldPauseThenHoldEnd() {
        var before: com.onpath.app.data.AppSettings? = null
        // the longest hold (2 s). The short taps that "fired the hold" on GitHub (2026-11-07, runs 42361613981,
        // 42362929806, 42363890364: "Pause on the locked bar (short tap 2)") were an app bug, not the tap: CI runs with
        // animations off and the band was a tween, full on its first frame – "Held button fired" 50 ms after a 21 ms tap.
        // The band now fills by the frame clock (HoldRule.fill, HoldFillTest); this test runs with animations off on CI.
        val holdMs = com.onpath.app.data.HoldSetting.MAX_MS
        UnlockWindow.openMsForTests = 1_500 // the 20 s unlock window, short – no test waits 20 s
        scenario.onActivity { before = vm(it).settings; vm(it).updateSettings(vm(it).settings.copy(navHoldMs = holdMs)) }
        try {
            startPlanWithTwoPoints()
            routeSettled()
            scenario.onActivity { vm(it).startNavigation(force = true) }
            waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
            val pause = findFresh(By.desc(Txt.s(Res.string.nav_action_pause)), 10_000)
            assertNotNull("locked: Pause at the bottom, no slider", pause)
            // owner 2026-11-07 09:30: nothing over the light buttons at rest – the hint only after a short tap
            assertTrue("no hint at rest", goneFresh(By.text(Txt.s(Res.string.nav_hold_hint)), 2_000))
            // a short tap: the band starts and falls back. Right after the switch to Navigate (a janky first frame on the
            // emulator, GitHub run 2026-11-06 22:46) a tap can land before the bar takes touches – tap again then; a short
            // tap never acts, so a second one is harmless
            var hintSeen: androidx.test.uiautomator.UiObject2? = null
            for (attempt in 1..3) {
                // the bar can be redrawn between the find and the click: find it again each time, never `!!`
                val button = findFresh(By.desc(Txt.s(Res.string.nav_action_pause)), 5_000)
                assertNotNull("Pause on the locked bar (short tap $attempt)", button)
                quickTap(button) // a real short tap – UiAutomator's click() became a hold on a loaded CI emulator
                hintSeen = findFresh(By.text(Txt.s(Res.string.nav_hold_hint)), 3_000)
                if (hintSeen != null) break
            }
            assertNotNull("a short tap shows the hint", hintSeen)
            Thread.sleep(1_500)
            assertTrue("a short tap does not pause", com.onpath.app.recording.RecordingService.state.value?.paused != true)
            holdButton(Txt.s(Res.string.nav_action_pause), ms = holdMs + 1_000)
            waitUntil("paused", 10_000) { com.onpath.app.recording.RecordingService.state.value?.paused == true }
            assertNotNull("the pause band over the bar", findFresh(By.text(Txt.s(Res.string.nav_pause_band_line)), 5_000))
            // the unlock window (owner 2026-11-07, 20 s; here 1.5 s) is over: the bar is locked again. Checked by its look,
            // not by another tap: a held button carries its word as a description, an open one as plain text – Beenden
            // (Weiter is held in the window too, so it tells nothing here)
            val end = Txt.s(Res.string.nav_end_short)
            waitUntil("locked again after the window: Beenden is a held button", UnlockWindow.openMs + 10_000) {
                findFresh(By.desc(end), 500) != null && findFresh(By.text(end), 500) == null
            }
            assertTrue("still paused", com.onpath.app.recording.RecordingService.state.value?.paused == true)
            holdButton(Txt.s(Res.string.nav_end_short), ms = holdMs + 1_000)
            assertNotNull("the end sheet", findFresh(By.text(Txt.s(Res.string.nav_end_tour_title)), 10_000))
            assertTrue("asking does not end the tour", recording())
        } finally {
            UnlockWindow.openMsForTests = null
            scenario.onActivity { vm(it).updateSettings(before!!) }
        }
    }

    /**
     * Owner 2026-11-07 09:05 ("nur einmal entriegeln"): one held Pause unlocks the bar – Beenden then acts on a plain
     * tap (no second hold) and opens the end sheet, which still asks.
     */
    @Test fun lockOnOneHoldUnlocksTheBarForAPlainTapOnBeenden() {
        var before: com.onpath.app.data.AppSettings? = null
        val holdMs = com.onpath.app.data.HoldSetting.MAX_MS // see lockOnHoldPauseThenHoldEnd (a short tap on Weiter resumed: HoldFillTest)
        // the window long: this test checks that ONE hold opens the bar for a plain tap, not the window's length
        // (UnlockWindowTest) – a loaded GitHub emulator took 13 s from the fired hold to the next step (run 42363890364)
        UnlockWindow.openMsForTests = 60_000
        scenario.onActivity { before = vm(it).settings; vm(it).updateSettings(vm(it).settings.copy(navHoldMs = holdMs)) }
        try {
            startPlanWithTwoPoints()
            routeSettled()
            scenario.onActivity { vm(it).startNavigation(force = true) }
            waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
            assertNotNull("locked: Pause at the bottom", findFresh(By.desc(Txt.s(Res.string.nav_action_pause)), 10_000))
            holdButton(Txt.s(Res.string.nav_action_pause), ms = holdMs + 1_000)
            waitUntil("paused", 10_000) { com.onpath.app.recording.RecordingService.state.value?.paused == true }
            // the bar is open now – but "Weiter" still wants a hold (owner 10:15): a short tap shows the hint, no resume
            var hint: androidx.test.uiautomator.UiObject2? = null
            for (attempt in 1..3) {
                val resume = findFresh(By.desc(Txt.s(Res.string.common_resume)), 5_000)
                assertNotNull("Weiter is still a held button in the window (short tap $attempt)", resume)
                quickTap(resume) // a real short tap – UiAutomator's click() became a hold on a loaded CI emulator
                hint = findFresh(By.text(Txt.s(Res.string.nav_hold_hint)), 3_000)
                if (hint != null) break
            }
            assertNotNull("a short tap on Weiter shows the hint", hint)
            Thread.sleep(1_000)
            assertTrue("a short tap on Weiter never resumes", com.onpath.app.recording.RecordingService.state.value?.paused == true)
            // Beenden is a plain button with its text in the window – a tap, no hold
            clickText(Txt.s(Res.string.nav_end_short))
            assertNotNull("the end sheet after a plain tap", findFresh(By.text(Txt.s(Res.string.nav_end_tour_title)), 10_000))
            assertTrue("asking does not end the tour", recording())
        } finally {
            UnlockWindow.openMsForTests = null
            scenario.onActivity { vm(it).updateSettings(before!!) }
        }
    }

    /**
     * Field bug (owner, Pixel 2026-11-06 21:18, "es gibt keinen Punkt 1 mehr"): during a tour the one-point rule (#39)
     * rebuilt position → point after every delete – deleting point 2 of "position → point" over and over never emptied
     * the plan, and a goal 11 m away gave "Punkt 1 → 2: kein Weg gefunden". Now: deleting the goal of the rule's own
     * "position → point" empties the plan (guidance ends, the recording goes on).
     */
    @Test fun deletingTheGoalAgainAfterOnePointLeftEmptiesThePlan() {
        // the field: "Start here" where I stand, a goal far off; on the way point 2 is deleted in the Points list
        val start = GeoPoint(lat, lon)
        val goal = GeoPoint(lat + 0.0050, lon + 0.0030)    // ~640 m away
        scenario.onActivity { vm(it).addPoint(start); vm(it).addPoint(goal) }
        waitUntil("two points", 10_000) { waypointCount() == 2 }
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { guided() }
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
        val plan = waypointList()
        scenario.onActivity { vm(it).deletePointFromList(1) }
        // the point left is where I stand: no "position → point" (that looped: delete point 2, it came back, again …)
        waitUntil("the plan is empty", 15_000) { waypointCount() == 0 }
        waitUntil("no route followed any more", 15_000) { !guided() }
        assertTrue("the recording goes on", recording())
        scenario.onActivity { vm(it).undoStep() }
        waitUntil("Undo: both points back", 10_000) { waypointList() == plan }
    }

    /** The same field bug, other side: the one point left is right at the position (11 m) – no "position → point". */
    @Test fun onePointLeftRightHereEmptiesThePlan() {
        val start = GeoPoint(lat + 0.0030, lon - 0.0020)   // ~380 m away
        val near = GeoPoint(lat + 0.0001, lon)             // ~11 m away
        scenario.onActivity { vm(it).addPoint(start); vm(it).addPoint(near) }
        waitUntil("two points", 10_000) { waypointCount() == 2 }
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { guided() }
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
        val plan = waypointList()
        scenario.onActivity { vm(it).deletePointFromList(0) }
        waitUntil("the plan is empty (the point left is where I stand)", 15_000) { waypointCount() == 0 }
        waitUntil("no route followed any more", 15_000) { !guided() }
        assertTrue("the recording goes on", recording())
        scenario.onActivity { vm(it).undoStep() }
        waitUntil("Undo: both points back", 10_000) { waypointList() == plan }
    }

    /**
     * Found by PlanFlowPropertyTest (audit 2026-11-07): Undo during a tour back to one point right where I stand left the
     * old 2-point line followed. Undo never empties the plan – the point stays – but the guidance ends (the recording
     * goes on); Redo brings the goal back and the tour follows it again.
     */
    @Test fun undoDuringATourBackToOnePointHereEndsTheGuidance() {
        val near = GeoPoint(lat + 0.0001, lon)             // ~11 m away
        val goal = GeoPoint(lat + 0.0050, lon + 0.0030)    // ~640 m away
        scenario.onActivity { vm(it).addPoint(near); vm(it).addPoint(goal) }
        waitUntil("two points", 10_000) { waypointCount() == 2 }
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { guided() }
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
        val plan = waypointList()
        scenario.onActivity { vm(it).undoStep() }
        waitUntil("Undo: the one point stays (Undo never empties)", 10_000) { waypointCount() == 1 }
        waitUntil("no route followed any more (not the old line)", 15_000) { !guided() }
        assertTrue("the recording goes on", recording())
        scenario.onActivity { vm(it).redoStep() }
        waitUntil("Redo: both points back", 10_000) { waypointList() == plan }
        waitUntil("the tour follows the plan again", 20_000) { guided() }
    }

    /**
     * Owner 2026-11-06 23:42: Navigate's top card keeps its chevron but opens / closes like the plan sheet – a tap
     * anywhere on the card (not the chevron) toggles it, pushing it up closes it, pulling it down opens it.
     */
    @Test fun theNavigateCardOpensByATapAndClosesByADragUp() {
        startPlanWithTwoPoints()
        routeSettled()
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        val chevron = findFresh(By.desc(Txt.s(Res.string.nav_profile_show)), 10_000)
        assertNotNull("the card with its chevron (closed)", chevron)
        val b = chevron!!.visibleBounds
        // a point on the card's head, well left of the chevron (the time / distance text – no button of its own)
        val x = (b.left - b.width() * 3).coerceAtLeast(b.width())
        val y = b.centerY()
        device.click(x, y)
        assertNotNull("a tap on the card opens it", findFresh(By.desc(Txt.s(Res.string.nav_profile_hide)), 5_000))
        device.swipe(x, y, x, y - b.height() * 4, 25)
        assertNotNull("pushing it up closes it", findFresh(By.desc(Txt.s(Res.string.nav_profile_show)), 5_000))
        device.swipe(x, y, x, y + b.height() * 4, 25)
        assertNotNull("pulling it down opens it", findFresh(By.desc(Txt.s(Res.string.nav_profile_hide)), 5_000))
        assertEquals("a drag on the card is no map gesture: still Navigate", AppMode.NAVIGATE, mode())
    }

    private fun waypointList(): List<GeoPoint> { var l: List<GeoPoint> = emptyList(); scenario.onActivity { l = vm(it).waypoints.toList() }; return l }
}

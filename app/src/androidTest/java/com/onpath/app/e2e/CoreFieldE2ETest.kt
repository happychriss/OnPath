package com.onpath.app.e2e

import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.common_cancel
import com.onpath.strings.routes_overview_follow
import com.onpath.strings.common_resume
import com.onpath.strings.nav_action_pause
import com.onpath.strings.nav_end_short
import com.onpath.strings.nav_end_tour_title
import com.onpath.strings.nav_hold_hint
import com.onpath.strings.nav_leave_go
import com.onpath.strings.nav_leave_stay
import com.onpath.strings.nav_settings_cd
import com.onpath.strings.settings_group_offroute
import com.onpath.strings.nav_leave_title
import com.onpath.strings.plan_go_here
import com.onpath.strings.plan_new_during_tour
import com.onpath.strings.plan_undo
import com.onpath.strings.nav_profile_hide
import com.onpath.strings.nav_profile_show
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
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
        Thread.sleep(if (realRouting) 3_000 else 1_000) // route + snapping
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
        Thread.sleep(if (realRouting) 3_000 else 1_000)
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
     * Owner rule: what Plan shows = what Navigate follows (never an old route followed unseen). Navigate → Plan → delete
     * all points (✕ in the list) →
     * Navigate: no route line, no guidance, the recording goes on. Undo → the points and the guidance are back.
     */
    @Test fun deletingAllPointsDuringATourEndsTheGuidanceUndoBringsItBack() {
        startPlanWithTwoPoints()
        Thread.sleep(if (realRouting) 3_000 else 1_000)
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
        Thread.sleep(if (realRouting) 3_000 else 1_000)
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
        Thread.sleep(if (realRouting) 3_000 else 1_000)
        val before = plannedIds()
        scenario.onActivity { assertTrue("a new plan is not saved yet", vm(it).planUnsaved) }
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("Los geht's saved the new plan in My routes → Planned", 15_000) { (plannedIds() - before).size == 1 }
        scenario.onActivity { assertFalse("the plan counts as saved now", vm(it).planUnsaved) }
        return (plannedIds() - before).single()
    }

    /**
     * Owner 2026-11-06 (the tour and the plan are two things, like Komoot – was: a field bug where the end sheet's "Plan
     * auch speichern" lost the plan, then a Save dialog after the tour that was easy to miss): "Los geht's" saves a new
     * plan; "Tour speichern" asks nothing about the plan and leaves it on the map – still in My routes.
     */
    @Test fun endTourKeepsThePlanSavedAtTheStart() {
        val planId = startOnANewPlan()
        val before = waypointList()
        scenario.onActivity { vm(it).endTour() }
        waitUntil("the tour has ended", 20_000) { !recording() }
        Thread.sleep(1_500) // the service's "finished" has run
        scenario.onActivity { assertTrue("no Save dialog after the tour", vm(it).saveAsk == null) }
        assertEquals("the plan stays on the map", before, waypointList())
        assertTrue("the plan is still in My routes", planId in plannedIds())
        TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext).deleteTour(planId)
    }

    /** The same for "Löschen" (flow map H2): only the tour goes to the bin, the plan stays – on the map and in My routes. */
    @Test fun endTourDeleteBinsOnlyTheTour() {
        val planId = startOnANewPlan()
        val before = waypointList()
        var tourId = -1L
        scenario.onActivity { tourId = vm(it).recState.value!!.tourId; vm(it).endTourAndDelete() }
        waitUntil("the tour has ended", 20_000) { !recording() }
        Thread.sleep(1_500)
        scenario.onActivity { assertTrue("no Save dialog after Löschen", vm(it).saveAsk == null) }
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("the tour is in the bin", (db.getTour(tourId)?.deletedMs ?: 0L) > 0L)
        assertEquals("the plan stays on the map", before, waypointList())
        assertTrue("the plan is still in My routes", planId in plannedIds())
        db.deleteTour(planId)
    }

    /**
     * Field bug (owner 2026-11-06, onpath.private#39): during a tour the START of a start → goal plan was deleted (hold →
     * Delete) – the plan had one point left, the sheet said "Neuer Plan", but the tour still followed the OLD line with
     * the deleted start. Owner: one point left during a tour = "Hierhin": the plan becomes position → that point, the
     * sheet shows that route and the tour follows exactly it. Undo brings the plan and its guidance back.
     */
    @Test fun deletingTheStartDuringATourRoutesFromHereToTheOnePointLeft() {
        startPlanWithTwoPoints()
        Thread.sleep(if (realRouting) 3_000 else 1_000)
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
        Thread.sleep(if (realRouting) 3_000 else 1_000)
        var unsaved = false
        scenario.onActivity { unsaved = vm(it).planUnsaved }
        assertTrue("the plan changed on the way is not saved", unsaved)
        return planId
    }

    private fun followName(): String? { var n: String? = null; scenario.onActivity { n = vm(it).followName }; return n }
    private fun drafts(): List<com.onpath.app.data.Tour> =
        TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext).listTours().filter { it.draft }

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
            startPlanWithTwoPoints()
            Thread.sleep(if (realRouting) 3_000 else 1_000)
            scenario.onActivity { vm(it).requestClear() }
            waitUntil("cleared at once, no question", 5_000) { waypointCount() == 0 }
            waitUntil("kept under Zuletzt", 10_000) { drafts().size == 1 }
            val draft = drafts().single()
            scenario.onActivity { vm(it).saveDraft(draft) }
            waitUntil("saved: no longer a draft", 10_000) { db.getTour(draft.id)?.draft == false }
            scenario.onActivity { assertEquals(draft.id, vm(it).justSavedId) }
            // a second one, deleted from Zuletzt → recycle bin
            startPlanWithTwoPoints()
            Thread.sleep(if (realRouting) 3_000 else 1_000)
            scenario.onActivity { vm(it).requestClear() }
            waitUntil("the second one under Zuletzt", 10_000) { drafts().size == 1 }
            val second = drafts().single()
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
            scenario.onActivity { vm(it).requestClear() }
            waitUntil("cleared at once, no question", 5_000) { waypointCount() == 0 }
            assertTrue("the recording goes on", recording())
            waitUntil("the changed plan under Zuletzt", 10_000) { drafts().size == 1 }
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
     * Owner 2026-11-06 ("Ich bin in Hilden, Plan in Köln – im Planmodus steht mein Standort, wo ist mein Plan?"): with
     * a plan, Navigate → Plan shows the whole plan, not the own position (PlanCamera).
     */
    @Test fun backInPlanTheMapShowsThePlanNotThePosition() {
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
            Thread.sleep(2_500)
            scenario.onActivity { vm(it).switchToPlan() }
            waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
            Thread.sleep(3_000) // settle + fit animation
            var at: org.maplibre.android.geometry.LatLng? = null
            onMap { m -> at = m.cameraPosition.target }
            val c = at!!
            val toPlan = com.onpath.app.data.Geo.distance(c.latitude, c.longitude, lat + 0.23, lon + 0.005)
            val toMe = com.onpath.app.data.Geo.distance(c.latitude, c.longitude, lat, lon)
            assertTrue("the map shows the plan (${toPlan.toInt()} m from it), not the position (${toMe.toInt()} m)", toPlan < toMe && toPlan < 5_000)
        } finally {
            scenario.onActivity { if (vm(it).recState.value != null) vm(it).stopRecording() }
            db.deleteTour(id)
        }
    }

    /**
     * Flow map N4 (owner 2026-11-06): Android's back in Navigate while a tour records asks "App verlassen? Deine Tour wird
     * weiter aufgezeichnet." – Bleiben keeps Navigate; Verlassen sends the app to the background and the tour records on.
     */
    @Test fun backInNavigateDuringATourAsksAndLeavingKeepsTheTour() {
        startPlanWithTwoPoints()
        Thread.sleep(if (realRouting) 3_000 else 1_000)
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
            Thread.sleep(if (realRouting) 3_000 else 1_000)
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
            Thread.sleep(if (realRouting) 3_000 else 1_000)
            scenario.onActivity { vm(it).startNavigation(force = true) }
            waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
            assertNotNull("Pause at the bottom, no slider", findFresh(By.text(Txt.s(Res.string.nav_action_pause)), 10_000))
            assertTrue("no Beenden before a pause", goneFresh(By.text(Txt.s(Res.string.nav_end_short)), 2_000))
            clickText(Txt.s(Res.string.nav_action_pause))
            waitUntil("paused", 10_000) { com.onpath.app.recording.RecordingService.state.value?.paused == true }
            assertNotNull("Weiter at the bottom", findFresh(By.text(Txt.s(Res.string.common_resume)), 5_000))
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
        scenario.onActivity { before = vm(it).settings; vm(it).updateSettings(vm(it).settings.copy(navHoldMs = com.onpath.app.data.HoldSetting.DEFAULT_MS)) }
        try {
            startPlanWithTwoPoints()
            Thread.sleep(if (realRouting) 3_000 else 1_000)
            scenario.onActivity { vm(it).startNavigation(force = true) }
            waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
            val pause = findFresh(By.desc(Txt.s(Res.string.nav_action_pause)), 10_000)
            assertNotNull("locked: Pause at the bottom, no slider", pause)
            // a short tap: the band starts and falls back. Right after the switch to Navigate (a janky first frame on the
            // emulator, GitHub run 2026-11-06 22:46) a tap can land before the bar takes touches – tap again then; a short
            // tap never acts, so a second one is harmless
            var hintSeen: androidx.test.uiautomator.UiObject2? = null
            for (attempt in 1..3) {
                findFresh(By.desc(Txt.s(Res.string.nav_action_pause)), 5_000)!!.click()
                hintSeen = findFresh(By.text(Txt.s(Res.string.nav_hold_hint)), 3_000)
                if (hintSeen != null) break
            }
            assertNotNull("a short tap shows the hint", hintSeen)
            Thread.sleep(1_500)
            assertTrue("a short tap does not pause", com.onpath.app.recording.RecordingService.state.value?.paused != true)
            holdButton(Txt.s(Res.string.nav_action_pause))
            waitUntil("paused", 10_000) { com.onpath.app.recording.RecordingService.state.value?.paused == true }
            holdButton(Txt.s(Res.string.nav_end_short))
            assertNotNull("the end sheet", findFresh(By.text(Txt.s(Res.string.nav_end_tour_title)), 10_000))
            assertTrue("asking does not end the tour", recording())
        } finally {
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
        Thread.sleep(if (realRouting) 3_000 else 1_000)
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
        Thread.sleep(if (realRouting) 3_000 else 1_000)
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
     * Owner 2026-11-06 23:42: Navigate's top card keeps its chevron but opens / closes like the plan sheet – a tap
     * anywhere on the card (not the chevron) toggles it, pushing it up closes it, pulling it down opens it.
     */
    @Test fun theNavigateCardOpensByATapAndClosesByADragUp() {
        startPlanWithTwoPoints()
        Thread.sleep(if (realRouting) 3_000 else 1_000)
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

package com.onpath.app.e2e

import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.common_cancel
import com.onpath.strings.plan_go_here
import com.onpath.strings.routes_load_ask_text
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
import com.onpath.app.ui.ReplaceAsk
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
     * Owner 2026-11-03 (core feature C4): My routes → a tap on a planned route during a tour asks "Load route …? You are
     * on the way." – nothing loads before the answer; Cancel keeps the plan, the followed route, the activity and the
     * tour (Continue: ReleaseRundgang flow 6).
     */
    @Test fun aSavedRouteTappedDuringATourAsksAndCancelKeepsTheTourRoute() {
        startPlanWithTwoPoints()
        Thread.sleep(if (realRouting) 3_000 else 1_000)
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { guided() }
        var tourId = -1L
        var before: List<GeoPoint> = emptyList()
        var followedBefore: String? = null
        var lineBefore: List<GeoPoint>? = null
        var activityBefore: Activity? = null
        scenario.onActivity {
            tourId = vm(it).recState.value!!.tourId; before = vm(it).waypoints.toList(); followedBefore = vm(it).followName
            lineBefore = vm(it).followLine; activityBefore = vm(it).activity
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
            val box = Txt.s(Res.string.routes_load_ask_text)
            assertNotNull("the question", device.wait(Until.findObject(By.text(box)), 5_000))
            Thread.sleep(1_500)
            scenario.onActivity {
                assertEquals("the plan stays", before, vm(it).waypoints.toList())
                assertEquals("the followed route stays", followedBefore, vm(it).followName)
                assertEquals("the followed line stays", lineBefore, vm(it).followLine)
                assertEquals("the activity stays", activityBefore, vm(it).activity)
                assertEquals("the same tour records on", tourId, vm(it).recState.value?.tourId)
                assertTrue("My routes stays open", vm(it).routesOpen)
            }
            assertTrue(guided())
            clickText(Txt.s(Res.string.common_cancel))
            waitUntil("the question is gone after Cancel", 5_000) { device.findObject(By.text(box)) == null }
            assertEquals("still recording, not ended", "active", db.getTour(tourId)?.state)
            val trace = com.onpath.app.debug.Problems.events().map { it.message }
            val step = "Load \"$name\" during the tour: cancel"
            assertTrue("\"$step\" traced: ${trace.take(30)}", trace.any { it.startsWith(step) })
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
     * set during the tour makes it unsaved again: "Nicht gespeichert ›"). Returns the planned route saved at the start.
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
    private fun askOpen(): Boolean { var a = false; scenario.onActivity { a = vm(it).replaceUnsavedAsk }; return a }

    /**
     * Flow map M1 (2026-11-06): "Folgen" on a done tour during a tour kept the plan built on the way – two routes, and an
     * edit to the plan switched the guidance silently back. Now: an unsaved plan asks "Plan speichern?" first; Verwerfen →
     * the plan leaves the map (Undo brings it back) and the tour follows the track.
     */
    @Test fun followingATrackDuringATourAsksForTheUnsavedPlanThenTheTrackAlone() {
        val planId = tourWithAnUnsavedPlan()
        val name = "E2E done tour ${System.currentTimeMillis() % 100_000}"
        val id = insertDoneTour(name)
        try {
            scenario.onActivity { vm(it).requestOpen(tourWith(id)); vm(it).answerTrackFollow(true) }
            waitUntil("'Plan speichern?' before the track takes the plan's place", 5_000) { askOpen() }
            assertEquals("nothing changed while it asks", 3, waypointCount())
            scenario.onActivity { vm(it).answerReplaceUnsaved(ReplaceAsk.Answer.DISCARD) }
            waitUntil("the plan has left the map", 10_000) { waypointCount() == 0 }
            waitUntil("the tour follows the track", 10_000) { followName() == name }
            assertTrue("the recording goes on", recording())
            scenario.onActivity { vm(it).undoStep() }
            waitUntil("Undo brings the plan back", 10_000) { waypointCount() == 3 }
        } finally {
            TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext).deleteTour(id)
            TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext).deleteTour(planId)
        }
    }

    /** M1: Abbrechen in "Plan speichern?" changes nothing – the plan stays and is still followed, no track. */
    @Test fun followingATrackDuringATourCancelKeepsThePlan() {
        val planId = tourWithAnUnsavedPlan()
        val before = followName()
        val name = "E2E done tour ${System.currentTimeMillis() % 100_000}"
        val id = insertDoneTour(name)
        try {
            scenario.onActivity { vm(it).requestOpen(tourWith(id)); vm(it).answerTrackFollow(true) }
            waitUntil("asked", 5_000) { askOpen() }
            scenario.onActivity { vm(it).answerReplaceUnsaved(ReplaceAsk.Answer.CANCEL) }
            Thread.sleep(1_500)
            assertEquals("the plan stays", 3, waypointCount())
            assertEquals("the plan is still followed", before, followName())
            assertTrue(guided())
        } finally {
            TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext).deleteTour(id)
            TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext).deleteTour(planId)
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
            scenario.onActivity { vm(it).requestOpen(tourWith(id)); vm(it).answerTrackFollow(true) }
            waitUntil("the tour follows the track", 10_000) { followName() == name }
            assertFalse("no question for a saved plan", askOpen())
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

    private fun waypointList(): List<GeoPoint> { var l: List<GeoPoint> = emptyList(); scenario.onActivity { l = vm(it).waypoints.toList() }; return l }
}

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

    /**
     * Field bug (owner 2026-11-06, "a plan made on the phone was gone after the tour"): Tour beenden with "Plan auch
     * speichern" ON opens Save's dialog; the end of the tour cleared the plan (and closed the dialog) before Save was
     * tapped – nothing reached My routes. Now: the plan stays while the dialog is open, Save stores it, then it leaves.
     */
    @Test fun endTourWithSavePlanOnPutsThePlanInMyRoutes() {
        startPlanWithTwoPoints()
        Thread.sleep(if (realRouting) 3_000 else 1_000)
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        var before: List<GeoPoint> = emptyList()
        scenario.onActivity { before = vm(it).waypoints.toList(); vm(it).saveRouteAtEnd = true; vm(it).endTour() }
        waitUntil("the tour has ended", 20_000) { !recording() }
        Thread.sleep(1_500) // the service's "finished" has run
        var askOpen = false
        scenario.onActivity { askOpen = vm(it).saveAsk != null }
        assertTrue("Save's dialog is still open after the tour ended", askOpen)
        assertEquals("the plan stays while Save's dialog is open", before, waypointList())
        val name = "E2E end save ${System.currentTimeMillis() % 100_000}"
        scenario.onActivity { vm(it).confirmSave(name) }
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        waitUntil("the plan is in My routes", 15_000) { var ok = false; scenario.onActivity { ok = vm(it).tours.any { t -> t.name == name && t.kind == TourKind.PLANNED } }; ok }
        waitUntil("then the plan leaves the map", 10_000) { waypointCount() == 0 }
        scenario.onActivity { vm(it).tours.filter { t -> t.name == name }.forEach { t -> db.deleteTour(t.id) } }
    }

    /**
     * Flow map H2 (2026-11-06): Löschen in the end sheet with "Plan auch speichern" ON threw the unsaved plan away without
     * a word. Now Löschen bins only the tour; the plan goes through Save's dialog into My routes.
     */
    @Test fun endTourDeleteWithSavePlanOnKeepsThePlan() {
        startPlanWithTwoPoints()
        Thread.sleep(if (realRouting) 3_000 else 1_000)
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        var before: List<GeoPoint> = emptyList()
        var tourId = -1L
        scenario.onActivity { before = vm(it).waypoints.toList(); tourId = vm(it).recState.value!!.tourId; vm(it).saveRouteAtEnd = true; vm(it).endTourAndDelete() }
        waitUntil("the tour has ended", 20_000) { !recording() }
        Thread.sleep(1_500)
        var askOpen = false
        scenario.onActivity { askOpen = vm(it).saveAsk != null }
        assertTrue("Save's dialog for the plan is open", askOpen)
        assertEquals("the plan stays while Save's dialog is open", before, waypointList())
        val name = "E2E delete keeps plan ${System.currentTimeMillis() % 100_000}"
        scenario.onActivity { vm(it).confirmSave(name) }
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        waitUntil("the plan is in My routes", 15_000) { var ok = false; scenario.onActivity { ok = vm(it).tours.any { t -> t.name == name && t.kind == TourKind.PLANNED } }; ok }
        assertTrue("the tour is in the bin", (db.getTour(tourId)?.deletedMs ?: 0L) > 0L)
        scenario.onActivity { vm(it).tours.filter { t -> t.name == name }.forEach { t -> db.deleteTour(t.id) } }
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

    /** A tour following a plan built on the way (two points, never saved). */
    private fun tourWithAnUnsavedPlan() {
        startPlanWithTwoPoints()
        Thread.sleep(if (realRouting) 3_000 else 1_000)
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the plan is followed", 20_000) { guided() }
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { mode() == AppMode.PLAN }
        var unsaved = false
        scenario.onActivity { unsaved = vm(it).planUnsaved }
        assertTrue("the plan made on the way is not saved", unsaved)
    }

    private fun followName(): String? { var n: String? = null; scenario.onActivity { n = vm(it).followName }; return n }
    private fun askOpen(): Boolean { var a = false; scenario.onActivity { a = vm(it).replaceUnsavedAsk }; return a }

    /**
     * Flow map M1 (2026-11-06): "Folgen" on a done tour during a tour kept the plan built on the way – two routes, and an
     * edit to the plan switched the guidance silently back. Now: an unsaved plan asks "Plan speichern?" first; Verwerfen →
     * the plan leaves the map (Undo brings it back) and the tour follows the track.
     */
    @Test fun followingATrackDuringATourAsksForTheUnsavedPlanThenTheTrackAlone() {
        tourWithAnUnsavedPlan()
        val name = "E2E done tour ${System.currentTimeMillis() % 100_000}"
        val id = insertDoneTour(name)
        try {
            scenario.onActivity { vm(it).requestOpen(tourWith(id)); vm(it).answerTrackFollow(true) }
            waitUntil("'Plan speichern?' before the track takes the plan's place", 5_000) { askOpen() }
            assertEquals("nothing changed while it asks", 2, waypointCount())
            scenario.onActivity { vm(it).answerReplaceUnsaved(ReplaceAsk.Answer.DISCARD) }
            waitUntil("the plan has left the map", 10_000) { waypointCount() == 0 }
            waitUntil("the tour follows the track", 10_000) { followName() == name }
            assertTrue("the recording goes on", recording())
            scenario.onActivity { vm(it).undoStep() }
            waitUntil("Undo brings the plan back", 10_000) { waypointCount() == 2 }
        } finally {
            TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext).deleteTour(id)
        }
    }

    /** M1: Abbrechen in "Plan speichern?" changes nothing – the plan stays and is still followed, no track. */
    @Test fun followingATrackDuringATourCancelKeepsThePlan() {
        tourWithAnUnsavedPlan()
        val before = followName()
        val name = "E2E done tour ${System.currentTimeMillis() % 100_000}"
        val id = insertDoneTour(name)
        try {
            scenario.onActivity { vm(it).requestOpen(tourWith(id)); vm(it).answerTrackFollow(true) }
            waitUntil("asked", 5_000) { askOpen() }
            scenario.onActivity { vm(it).answerReplaceUnsaved(ReplaceAsk.Answer.CANCEL) }
            Thread.sleep(1_500)
            assertEquals("the plan stays", 2, waypointCount())
            assertEquals("the plan is still followed", before, followName())
            assertTrue(guided())
        } finally {
            TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext).deleteTour(id)
        }
    }

    /** M1: a saved plan asks nothing – it stays in My routes, leaves the map, the tour follows the track. */
    @Test fun followingATrackDuringATourWithASavedPlanGoesAtOnce() {
        tourWithAnUnsavedPlan()
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
            scenario.onActivity { vm(it).tours.filter { t -> t.name == planName }.forEach { t -> db.deleteTour(t.id) } }
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

    private fun waypointList(): List<GeoPoint> { var l: List<GeoPoint> = emptyList(); scenario.onActivity { l = vm(it).waypoints.toList() }; return l }
}

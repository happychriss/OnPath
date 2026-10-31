package com.onpath.app.e2e

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
        clickText("Go here")
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

    /** A tour from the plan, back to Plan (the plan is being followed) – its points must still be editable. */
    @Test fun navigateThenBackToPlanStillDeletes() {
        startPlanWithTwoPoints()
        Thread.sleep(if (realRouting) 3_000 else 1_000)
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { var m: AppMode? = null; scenario.onActivity { m = vm(it).mode }; m == AppMode.NAVIGATE }
        Thread.sleep(2_000)
        assertPositionDotOnTop("navigate")
        // Navigate shows only the route line, no numbered plan points (owner)
        waitUntil("no plan markers in Navigate", 10_000) { drawnMarkers() == 0 }
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
     * Owner 2026-10-31: no new route during a tour. My routes → a tap on a planned route loads nothing: the plan, the
     * followed route, the activity and the tour stay; the blue box "End the running tour first, then this route can be
     * loaded." shows until OK (core feature C4).
     */
    @Test fun aSavedRouteTappedDuringATourKeepsTheTourRouteAndSaysEndTheTourFirst() {
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
            val box = "End the running tour first, then this route can be loaded."
            assertNotNull("the box", device.wait(Until.findObject(By.text(box)), 5_000))
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
            clickText("OK")
            waitUntil("the box is gone after OK", 5_000) { device.findObject(By.text(box)) == null }
            assertEquals("still recording, not ended", "active", db.getTour(tourId)?.state)
            val trace = com.onpath.app.debug.Problems.events().map { it.message }
            val step = "Load \"$name\" during the tour: not loaded"
            assertTrue("\"$step\" traced: ${trace.take(30)}", trace.any { it.startsWith(step) })
        } finally {
            scenario.onActivity { vm(it).routesOpen = false }
            db.deleteTour(savedId)
        }
    }
}

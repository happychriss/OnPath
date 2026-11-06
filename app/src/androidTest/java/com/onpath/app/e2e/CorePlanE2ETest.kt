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
     * Flow map H1 (2026-11-06, confirmed on the emulator): with no plan point there was no way to start a tour – no
     * green button on any sheet step, none in ☰. Now the sheet's green button says "Los geht's · ohne Route" and starts a
     * plain recording (PlanStart.RECORD_ONLY).
     */
    @Test fun anEmptyPlanStartsARecordingWithoutARoute() {
        scenario.onActivity { a -> if (vm(a).waypoints.isNotEmpty()) vm(a).clearPlan() }
        waitUntil("empty plan", 5_000) { waypointCount() == 0 }
        val label = Txt.s(Res.string.plan_start_recording_for)
        val go = findFresh(By.desc(label), 8_000)
        assertNotNull("the green start button on an empty plan (\"$label\")", go)
        go!!.click()
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
}

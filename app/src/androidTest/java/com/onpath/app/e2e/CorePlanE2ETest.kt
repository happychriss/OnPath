package com.onpath.app.e2e

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.onpath.app.MainActivity
import com.onpath.app.cells.PmTiles
import com.onpath.app.ui.AppViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CORE FEATURES C1 / C2 end to end (docs/core-features.md): the real app, the real MapLibre map, a finger held on the
 * screen (UiAutomator). Runs on an emulator before every release (.github/workflows/e2e.yml, release.yml) – owner
 * 2026-10-21: "not good to find out during walking".
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

    /** Field test 2026-10-22: after a style reload the route points were drawn above the "I am here" dot. */
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

    /** C1 on a turned map (compass, hand rotation – the 0.7.19 bug class). */
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
}

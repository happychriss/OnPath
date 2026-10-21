package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.onpath.app.ui.AppMode
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/**
 * CORE FEATURE C1 under field conditions (owner 2026-10-21: "latest version still doesn't work" – the plain emulator
 * test passed): a moving GPS position in Meran, the map following it in Plan, "Go here" from my location, real
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
        // really is, the map follows that and the plan's points end up off screen (owner's Pixel, 2026-10-21).
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
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("back in Plan", 10_000) { var m: AppMode? = null; scenario.onActivity { m = vm(it).mode }; m == AppMode.PLAN }
        Thread.sleep(2_000)
        holdOnWaypoint(1)
        assertNotNull(device.wait(Until.findObject(By.text("Delete")), 3_000))
        clickText("Delete")
        waitUntil("point deleted during a tour", 15_000) { waypointCount() == 1 }
        var rec = false; scenario.onActivity { rec = vm(it).recState.value != null }
        assertTrue("recording goes on in Plan (core feature C4)", rec)
    }
}

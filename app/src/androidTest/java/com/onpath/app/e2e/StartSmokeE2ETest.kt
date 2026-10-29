package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.data.Prefs
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.AppMode
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The short gate before every release (owner): the app opens – also with a saved planned route, which the ViewModel
 * routes while it is still being built. Kept small, so the release waits only a few minutes; the full E2E suite runs
 * beside it.
 */
@RunWith(AndroidJUnit4::class)
class StartSmokeE2ETest : E2EBase() {

    @Test fun opensWithASavedPlan() {
        launch {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            Prefs(ctx).planWaypoints = listOf(GeoPoint(MERAN_LAT, MERAN_LON), GeoPoint(MERAN_LAT + 0.01, MERAN_LON + 0.01))
        }
        assertEquals("the saved plan is back", 2, waypointCount())
        var mode: AppMode? = null
        scenario.onActivity { mode = vm(it).mode }
        assertEquals(AppMode.PLAN, mode)
        waitUntil("route calculated", 30_000) { var r = true; scenario.onActivity { r = vm(it).routing }; !r }
        assertPositionDotOnTop("start with a plan")
    }

    /**
     * Start with a saved ≈ 8-day plan → "This route is very long" → Cancel: never "Calculating the route…" forever with
     * Start disabled – the points stay, not routed, with "Plan anyway".
     */
    @Test fun veryLongSavedPlanCancelIsNotStuckCalculating() {
        launch {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            Prefs(ctx).planWaypoints = listOf(GeoPoint(MERAN_LAT, MERAN_LON), GeoPoint(53.5511, 9.9937)) // Meran → Hamburg
        }
        waitUntil("long-route question", 15_000) { var a = false; scenario.onActivity { a = vm(it).longRouteAsk != null }; a }
        clickText("Cancel")
        waitUntil("question closed", 5_000) { var a = true; scenario.onActivity { a = vm(it).longRouteAsk != null }; !a }
        var routing = true; var why: com.onpath.app.ui.NotRouted? = null
        scenario.onActivity { routing = vm(it).routing; why = vm(it).notRouted }
        assertEquals("the plan's points stay", 2, waypointCount())
        assertFalse("no routing request runs", routing)
        assertEquals(com.onpath.app.ui.NotRouted.LONG, why)
        assertTrue("not stuck on Calculating – ${state()}", device.findObject(By.textContains("Calculating")) == null)
        assertTrue("Plan anyway offered", device.wait(Until.findObject(By.text("Plan anyway")), 5_000) != null)
    }

    @Test fun opensEmpty() {
        launch()
        assertEquals(0, waypointCount())
    }
}

package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.data.Prefs
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.AppMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The short gate before every release (owner 2026-10-22): the app opens. 0.9.1 crashed at start when a planned route
 * was saved – the ViewModel routed it in its constructor and touched a state that did not exist yet. Kept small, so the
 * release waits only a few minutes; the full E2E suite runs beside it.
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

    @Test fun opensEmpty() {
        launch()
        assertEquals(0, waypointCount())
    }
}

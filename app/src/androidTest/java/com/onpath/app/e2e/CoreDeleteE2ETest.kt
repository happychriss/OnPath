package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CORE FEATURE C1, field 2026-10-21 (Pixel 8, 0.8.5): (1) holds 68 dp from a point added a new point instead of
 * opening its menu; (2) "the menu opened, I tapped Delete, nothing happened". The real app, real MapLibre, real
 * BRouter data when pushed (/data/local/tmp/E10_N45.rd5 – points snapped to ways like on the phone).
 */
@RunWith(AndroidJUnit4::class)
class CoreDeleteE2ETest : E2EBase() {

    @Before fun setUp() = launch()

    private val density: Float get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    /** A finger [dp] above route point [index] (up: away from the plan sheet and the other points). */
    private fun holdAbove(index: Int, dp: Float) {
        val (x, y) = waypointOnScreen(index)
        hold(x, y - dp * density)
    }

    private fun routing(): Boolean { var r = false; scenario.onActivity { r = vm(it).routing }; return r }
    private fun points(): List<GeoPoint> { var p = emptyList<GeoPoint>(); scenario.onActivity { p = vm(it).waypoints.toList() }; return p }
    private fun overlayOpen(): Boolean { var o = false; scenario.onActivity { o = vm(it).mapOverlayOpen }; return o }

    /** Route calculated and points snapped (they move onto ways), map settled. */
    private fun settle() {
        Thread.sleep(300)
        waitUntil("route calculated", 30_000) { !routing() }
        Thread.sleep(700)
    }

    private fun closeMenu() {
        device.pressBack()
        waitUntil("menu closed") { !overlayOpen() }
    }

    private fun threePoints() {
        startPlanWithTwoPoints()
        settle()
        val (w, h) = mapSize()
        hold(w * 0.5f, h * 0.22f)
        waitUntil("third point by a hold") { waypointCount() == 3 }
    }

    /** Catch zone: ≤ 48 dp → the point's menu; 48–80 dp → ask; beyond → a new point. */
    @Test fun catchZoneAt30_45_68_90dp() {
        startPlanWithTwoPoints()
        settle()
        for (dp in listOf(30f, 45f)) {
            holdAbove(1, dp)
            assertNotNull("menu at $dp dp – ${state()}", device.wait(Until.findObject(By.text("Delete")), 5_000))
            assertEquals("no point added at $dp dp", 2, waypointCount())
            closeMenu()
        }
        holdAbove(1, 68f)
        assertNotNull("ask at 68 dp – ${state()}", device.wait(Until.findObject(By.text("New point here")), 5_000))
        assertEquals("no point added at 68 dp", 2, waypointCount())
        clickText("Point 2: options")
        clickText("Delete")
        waitUntil("point 2 deleted via the near-point menu") { waypointCount() == 1 }
        // a point again, then 90 dp away: a new point
        val (w, h) = mapSize()
        hold(w * 0.65f, h * 0.45f)
        waitUntil("second point again") { waypointCount() == 2 }
        settle()
        holdAbove(1, 90f)
        waitUntil("new point at 90 dp") { waypointCount() == 3 }
    }

    /** "New point here" in the near-point menu adds the point after all. */
    @Test fun nearPointMenuNewPointHere() {
        startPlanWithTwoPoints()
        settle()
        holdAbove(1, 68f)
        clickText("New point here")
        waitUntil("point added from the near-point menu") { waypointCount() == 3 }
    }

    /** Delete right after the route was calculated (points just snapped onto ways). */
    @Test fun deleteRightAfterTheRouteFinished() {
        threePoints()
        settle()
        val before = points()
        holdOnWaypoint(1)
        clickText("Delete")
        waitUntil("deleted") { waypointCount() == 2 }
        settle()
        assertEquals(listOf(before[0], before[2]).size, points().size)
        assertFalse("the deleted point never comes back", points().contains(before[1]))
    }

    /**
     * Delete while a route is still being calculated (activity switched with the menu open → a new route request);
     * the delete supersedes it – the older result must not bring the point back.
     */
    @Test fun deleteWhileARouteIsCalculated() {
        threePoints()
        settle()
        holdOnWaypoint(1)
        val held = points()[1]
        var wasRouting = false
        scenario.onActivity { a ->
            val vm = vm(a)
            vm.selectActivity(if (vm.activity == com.onpath.app.routing.Activity.HIKE) com.onpath.app.routing.Activity.MTB
                else com.onpath.app.routing.Activity.HIKE)
            // in the same main-thread step, so the route for 3 points is certainly still running: what the Delete
            // button does (MapOverlays → deletePointFromMenu); the button itself is tapped in the other tests
            wasRouting = vm.routing
            vm.deletePointFromMenu()
        }
        assertTrue("a route was being calculated at the delete", wasRouting)
        waitUntil("deleted while routing") { waypointCount() == 2 }
        settle()
        Thread.sleep(1_500)
        assertEquals("still 2 points after every route result – ${state()}", 2, waypointCount())
        assertFalse(points().contains(held))
    }

    /**
     * The plan changes under an open menu (here: route ⋯ → Points… deletes point 1): Delete in the menu deletes the
     * point it was opened for. Before 0.8.7 the menu kept only an index – point 3's menu then pointed past the end
     * of the list and Delete did nothing.
     */
    @Test fun planChangesUnderAnOpenMenu() {
        threePoints()
        settle()
        holdOnWaypoint(2)
        val before = points()
        scenario.onActivity { vm(it).deletePointFromList(0) }
        Thread.sleep(300)
        clickText("Delete")
        waitUntil("the held point deleted – ${state()}") { waypointCount() == 1 }
        val left = points().single()
        assertTrue("point 2 is left (maybe snapped): $left vs ${before[1]}",
            com.onpath.app.data.Geo.distance(left, before[1]) < 1_000)
    }

    /** The map reloads (new cell generation / style) while the menu is open: Delete still works. */
    @Test fun mapReloadsUnderAnOpenMenu() {
        startPlanWithTwoPoints()
        settle()
        holdOnWaypoint(1)
        scenario.onActivity { vm(it).reloadMapStyleForTest() }
        waitForMap(reloaded = true)
        val del = device.wait(Until.findObject(By.text("Delete")), 3_000)
        if (del == null) { // the menu may close when the map reloads – then it must open again
            holdOnWaypoint(1)
        }
        clickText("Delete")
        waitUntil("deleted after the map reloaded") { waypointCount() == 1 }
    }
}

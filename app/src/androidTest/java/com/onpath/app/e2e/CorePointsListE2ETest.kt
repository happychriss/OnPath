package com.onpath.app.e2e

import android.graphics.Point
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.PlanSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CORE FEATURE C9 (owner 2026-10-21): the full plan sheet is the point editor – opened only by the sheet's handle /
 * a swipe up; delete with ✕, reorder by holding ≡ and dragging, tap a row → the map shows the point, Undo on the map
 * brings the old plan back. No profile / way types in it (those are in the route details).
 */
@RunWith(AndroidJUnit4::class)
class CorePointsListE2ETest : E2EBase() {

    @Before fun setUp() = launch()

    private fun routing(): Boolean { var r = false; scenario.onActivity { r = vm(it).routing }; return r }
    private fun points(): List<GeoPoint> { var p = emptyList<GeoPoint>(); scenario.onActivity { p = vm(it).waypoints.toList() }; return p }
    private fun size(): PlanSize { var s = PlanSize.COMPACT; scenario.onActivity { s = vm(it).planSize }; return s }

    private fun settle() {
        Thread.sleep(300)
        waitUntil("route calculated", 30_000) { !routing() }
        Thread.sleep(700)
    }

    /** Three points by holds, then the sheet at full size (swipe up = handle tap twice from half size). */
    private fun threePointsAndFullSheet() {
        startPlanWithTwoPoints()
        settle()
        val (w, h) = mapSize()
        hold(w * 0.5f, h * 0.22f)
        waitUntil("third point by a hold") { waypointCount() == 3 }
        settle()
        // the grab handle: half → full
        for (i in 0 until 3) {
            if (size() == PlanSize.FULL) break
            desc("Resize panel").click(); Thread.sleep(500)
        }
        waitUntil("full sheet") { size() == PlanSize.FULL }
        assertNotNull("Points section shown – ${state()}", device.wait(Until.findObject(By.text("Points")), 5_000))
        assertNull("no profile in the point editor", device.findObject(By.desc("Route details")))
    }

    private fun desc(text: String) = device.wait(Until.findObject(By.desc(text)), 5_000)
        ?: run { shot("missing-${text.filter { it.isLetterOrDigit() }}"); throw AssertionError("\"$text\" not shown – ${state()}") }

    @Test fun deleteTheSecondPointWithX() {
        threePointsAndFullSheet()
        val before = points()
        desc("Delete point 2").click()
        waitUntil("point 2 deleted from the list") { waypointCount() == 2 }
        assertFalse(points().contains(before[1]))
        // Undo on the map brings it back, in its place (no dialog)
        desc("Undo").click()
        waitUntil("undo restored point 2") { waypointCount() == 3 }
        assertEquals(before, points())
    }

    @Test fun reorderByHoldingAndDraggingTheHandle() {
        threePointsAndFullSheet()
        val before = points()
        val from = desc("Move point 3 (hold and drag)").visibleCenter
        val to = desc("Move point 1 (hold and drag)").visibleCenter
        // a finger resting ~0.6 s on ≡ (long press), then dragged up two rows and released
        device.swipe(arrayOf(Point(from.x, from.y), Point(from.x, from.y), Point(from.x, from.y + 1),
            Point(to.x, to.y - 10)), 120)
        waitUntil("reordered", 5_000) { points() != before }
        settle()
        assertEquals("no point lost or added", 3, points().size)
        assertTrue("the dragged point is the new start: ${points()} vs $before",
            com.onpath.app.data.Geo.distance(points()[0], before[2]) < 1_000)
        desc("Undo").click()
        waitUntil("undo restored the order") { points().size == 3 &&
            com.onpath.app.data.Geo.distance(points()[0], before[0]) < 1_000 }
    }

    @Test fun tapARowShowsThePointOnTheMap() {
        threePointsAndFullSheet()
        // move the map away first, so the tap must bring the point back into view
        val target = points()[1]
        onMap { m ->
            m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLng(
                org.maplibre.android.geometry.LatLng(target.lat + 0.05, target.lon + 0.05)))
        }
        Thread.sleep(500)
        device.wait(Until.findObject(By.descStartsWith("Show point 2 on the map")), 5_000)!!.click()
        waitUntil("half sheet after the tap") { size() == PlanSize.COMPACT }
        Thread.sleep(1_200) // camera glide
        var highlight: GeoPoint? = null
        scenario.onActivity { highlight = vm(it).highlight }
        assertEquals("point highlighted", target, highlight)
        val (x, y) = waypointOnScreen(1)
        val (w, h) = mapSize()
        assertTrue("point 2 on screen at ($x, $y) of $w x $h", x in 0f..w && y in 0f..h * 0.6f)
    }
}

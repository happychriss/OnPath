package com.onpath.app.e2e

import android.graphics.Point
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
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
 * brings the old plan back. Owner 2026-10-22: small → profile → points (the points are the sheet's last step, below the
 * profile); way types are in the route details only.
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
        // the rows' ✕ (content descriptions) – UiAutomator found the "Points" header text only now and then on CI,
        // although it was on screen (screenshots 2026-10-22); its cache kept the tree without the list (2026-10-25):
        // look afresh each time
        if (findFresh(By.descStartsWith("Delete point"), 10_000) == null) {
            shot("no-points-section")
            var rec = false; scenario.onActivity { rec = vm(it).recState.value != null }
            throw AssertionError("Points section shown – tour running $rec – UiAutomator sees: ${seenDescriptions()} – ${state()}")
        }
        // owner 2026-10-22: profile first, then the points (one scrolling area)
        val profile = desc("Elevation profile").visibleBounds
        val firstRow = desc("Move point 1 (hold and drag)").visibleBounds
        assertTrue("profile above the points: $profile vs $firstRow", profile.bottom <= firstRow.top + 2)
    }

    /**
     * Like waitUntil (same 10 s), but a timeout says where the sheet is and what it did: the events list carries the
     * release line of the last swipe ("Sheet let go at … px (steps …) → step N", StepSheet) – CI 2026-10-26 failed
     * with only "timeout: half sheet after a swipe down".
     */
    private fun waitForSize(what: String, want: PlanSize) {
        val end = System.currentTimeMillis() + 10_000
        while (size() != want) {
            if (System.currentTimeMillis() > end) {
                shot("size-${want.name.lowercase()}")
                throw AssertionError("timeout: $what – size ${size()} – ${state()}")
            }
            Thread.sleep(100)
        }
    }

    private fun desc(text: String) = findFresh(By.desc(text), 5_000)
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

    /**
     * A slow, realistic finger: rests on ≡ of point 3 with a little wobble (like a real thumb), then moves up in small
     * steps to above point 1 and lets go. Field test 0.8.9: "moving the points doesn't work" – the wobble let the sheet
     * / list steal the gesture. The sheet must not move meanwhile.
     */
    @Test fun reorderByHoldingAndDraggingTheHandle() {
        threePointsAndFullSheet()
        val before = points()
        val from = desc("Move point 3 (hold and drag)").visibleCenter
        val to = desc("Move point 1 (hold and drag)").visibleCenter
        val path = ArrayList<Point>()
        repeat(12) { path += Point(from.x + (it % 3) - 1, from.y + (it % 2)) }  // ~0.4 s resting, wobbling 1–2 px
        val steps = 30
        for (k in 1..steps) path += Point(from.x, from.y + (to.y - 12 - from.y) * k / steps)
        repeat(6) { path += Point(from.x, to.y - 12) }  // a moment over the new place before letting go
        device.swipe(path.toTypedArray(), 6)  // 6 steps ≈ 30 ms per point
        waitUntil("reordered", 5_000) { points() != before }
        assertEquals("the sheet did not move while a row was dragged", PlanSize.FULL, size())
        settle()
        assertEquals("no point lost or added", 3, points().size)
        assertTrue("the dragged point is the new start: ${points()} vs $before",
            com.onpath.app.data.Geo.distance(points()[0], before[2]) < 1_000)
        desc("Undo").click()
        waitUntil("undo restored the order") { points().size == 3 &&
            com.onpath.app.data.Geo.distance(points()[0], before[0]) < 1_000 }
    }

    /**
     * Field test 0.8.9 ("the swipe is jumping"), owner 2026-10-22 (three steps): the sheet follows a slow drag of its
     * handle and settles at the nearest step – small → profile (first swipe) → points (second swipe) → profile.
     */
    @Test fun swipeTheSheetHalfFullHalf() {
        startPlanWithTwoPoints()
        settle()
        waitUntil("half sheet") { size() == PlanSize.COMPACT }
        val (_, h) = mapSize()
        fun handle() = desc("Resize panel").visibleCenter
        fun dragHandle(dy: Int) {
            val c = handle()
            val path = ArrayList<Point>()
            val steps = 25
            for (k in 0..steps) path += Point(c.x, c.y + dy * k / steps)
            repeat(4) { path += Point(c.x, c.y + dy) }  // stop before letting go: no fling, the distance decides
            device.swipe(path.toTypedArray(), 6)
        }
        // where the handle rests at the profile step: the swipe back down from the points aims there (see below)
        val profileY = handle().y
        dragHandle((h * 0.2f).toInt())
        waitForSize("small sheet after a swipe down", PlanSize.MINI)
        Thread.sleep(600)
        // first swipe up: the profile, not yet the points
        dragHandle(-(h * 0.2f).toInt())
        waitForSize("profile step after the first swipe up", PlanSize.COMPACT)
        assertNotNull("profile shown", findFresh(By.desc("Elevation profile"), 5_000))
        assertTrue("no points list in the profile step", goneFresh(By.descStartsWith("Delete point"), 3_000))
        Thread.sleep(600)
        // second swipe up: the points
        dragHandle(-(h * 0.25f).toInt())
        waitForSize("full sheet after the second swipe up", PlanSize.FULL)
        if (findFresh(By.descStartsWith("Delete point"), 10_000) == null) {
            shot("no-points-full")
            throw AssertionError("points shown – UiAutomator sees: ${seenDescriptions()} – ${state()}")
        }
        Thread.sleep(600)
        // swipe down 90 % of the way to where the profile step rests: clearly nearer the profile than the points.
        // It was a fixed 20 % of the screen and failed once on CI (2026-10-26): the points → profile gap is 664 px on the
        // CI phone, so 480 px less the 21 px touch slop ended only 127 px past the midpoint – the narrowest of the four
        // swipes (the others ≥ 247 px).
        val fullY = handle().y
        dragHandle(((profileY - fullY) * 0.9f).toInt())
        waitForSize("half sheet after a swipe down", PlanSize.COMPACT)
        // the list leaves with the sheet's animation: wait until it is gone (a single look found a stale node, CI 2026-10-22)
        if (!goneFresh(By.descStartsWith("Delete point"), 8_000)) {
            shot("points-still-there")
            throw AssertionError("no points list in the half sheet – size ${size()} – ${state()}")
        }
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
        (findFresh(By.descStartsWith("Show point 2 on the map"), 5_000)
            ?: throw AssertionError("row of point 2 shown – UiAutomator sees: ${seenDescriptions()} – ${state()}")).click()
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

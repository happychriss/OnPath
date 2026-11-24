package com.onpath.app.ui

import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Hotel
import com.onpath.app.routing.NightMark
import com.onpath.app.time.Factors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The phone's multi-day tour (stage 4): a synced 3-day tour opened → its overview; day 2 picked → the plan to follow; an
 * edit of the day ("Planen" on the way) goes back into the whole tour with every night mark; a restart keeps the day;
 * another route leaves the tour; Save stores the days with the plan's points.
 */
class MultiDayFlowTest {
    private val dir = Files.createTempDirectory("multiday").toFile()
    /** The preferences the flow keeps its state in: one memory object, so a "restart" ([flow] again) reads what the first one wrote. */
    private val sp = com.onpath.app.testkit.MemoryMultiDayPrefs()
    private var linked = 7L

    @Before fun setUp() { Problems.init(dir); Problems.clear() }

    /** The unreadable-state test logs an ERROR: cleared, else later Navigate screenshots in this JVM show the announced problem. */
    @org.junit.After fun tearDown() = Problems.clear()

    /** MultiDayFlow holds Compose state: each test writes it in a snapshot of its own (OwnComposeSnapshot says why). */
    @get:org.junit.Rule val composeState = com.onpath.app.testkit.OwnComposeSnapshot()

    private fun flow() = MultiDayFlow(sp) { linked }

    /** 90 km north, 1 km per line point. */
    private val line = (0..90).map { GeoPoint(46.0 + it * 0.009, 11.0, 500.0) }
    /** Points at km 0, 20, 30 (Übernachtung), 60 (hotel), 90 – 3 days. */
    private val pts = listOf(0, 20, 30, 60, 90).map { GeoPoint(line[it].lat, line[it].lon) }
    private val hotel = NightMark(Hotel("Gasthof Post"))
    private val days = MultiDay(3, nights = mapOf(2 to NightMark.OVERNIGHT, 3 to hotel), description = "Drei Tage")
    private val tour = Tour(7, TourKind.PLANNED, "Drei Tage", 1L, Activity.BIKE_TOURING.name, 2, 90_000.0, 0.0, 0.0, 0.0, "done",
        pts.mapIndexed { i, p -> GpxWaypoint(p.lat, p.lon, if (i == 3) "Gasthof Post" else null) }, uuid = "u7", days = days)

    private fun opened(f: MultiDayFlow): MultiDayFlow {
        f.opened(tour, pts)
        val plan = f.plan(pts, Activity.BIKE_TOURING, 2, emptySet(), mapOf(3 to "Gasthof Post"))!!
        f.show(f.compute(plan, line, Factors.ONE, "r"))
        return f
    }

    @Test fun aMultiDayTourOpensToItsOverview() {
        val f = opened(flow())
        assertTrue(f.overview)
        assertEquals(3, f.view!!.days.days.size)
        assertEquals(listOf(30, 60), f.view!!.days.days.mapNotNull { it.end?.lineIndex })
        // Save stores the days with the plan's points (night marks by index, from the marks by value)
        assertEquals(days, f.daysFor(pts))
        // the plan cleared (no longer linked): a normal plan, no days saved
        linked = -1
        assertFalse(f.overview)
        assertNull(f.daysFor(pts))
        linked = 7
        assertTrue("Undo back to the tour: the overview again", f.overview)
    }

    /** Day 2's first point moves onto the way: a snap, no edit – the anchors on the legs at the day's border stay in the tour. */
    @Test fun aSnapOfADaysBorderPointKeepsTheToursDetourPoints() {
        fun on(km: Int) = GeoPoint(line[km].lat, line[km].lon)
        val anchors = mapOf(1 to com.onpath.app.routing.Anchors(after = listOf(on(25))),
            2 to com.onpath.app.routing.Anchors(listOf(on(27)), listOf(on(40))))
        val f = opened(flow())
        f.tapDay(2)
        val whole = f.plan(pts, Activity.BIKE_TOURING, 2, emptySet(), mapOf(3 to "Gasthof Post"), anchors)!!
        val part = f.pickDay(whole, pts)!!
        val moved = GeoPoint(pts[2].lat + 0.0001, pts[2].lon)
        val edited = listOf(moved, pts[3])
        // the run that snapped is of the tour's own profile (second review: with null the profile check was skipped)
        f.snapped(mapOf(pts[2] to moved), com.onpath.app.routing.AnchorProfile.of(Activity.BIKE_TOURING, 2))
        f.pointsChanged(edited, emptySet(), com.onpath.app.routing.AnchorSet.of(part.anchors, Activity.BIKE_TOURING, 2)) { null }
        assertEquals(anchors, f.day!!.whole.anchors)
        assertEquals(moved, f.day!!.whole.points[2])
        assertEquals("a restart keeps them", anchors, flow().day!!.whole.anchors)
    }

    /** The same snap made by a run of ANOTHER profile than the detour points' (review finding 4): the moved point is new, its detour points go. */
    @Test fun aSnapOfARunUnderAnotherProfileDropsTheDetourPointsOfTheMovedPoint() {
        fun on(km: Int) = GeoPoint(line[km].lat, line[km].lon)
        val anchors = mapOf(1 to com.onpath.app.routing.Anchors(after = listOf(on(25))),
            2 to com.onpath.app.routing.Anchors(listOf(on(27)), listOf(on(40))))
        val f = opened(flow())
        f.tapDay(2)
        val whole = f.plan(pts, Activity.BIKE_TOURING, 2, emptySet(), mapOf(3 to "Gasthof Post"), anchors)!!
        f.pickDay(whole, pts)!!
        val moved = GeoPoint(pts[2].lat + 0.0001, pts[2].lon)
        f.snapped(mapOf(pts[2] to moved), com.onpath.app.routing.AnchorProfile.of(Activity.HIKE, 3)) // the tour's anchors lie on BIKE_TOURING
        assertEquals(moved, f.day!!.whole.points[2])
        assertEquals(emptyMap<Int, com.onpath.app.routing.Anchors>(), f.day!!.whole.anchors)
    }

    @Test fun aNormalRouteHasNoDays() {
        val f = flow()
        f.opened(tour.copy(days = null), pts)
        assertFalse(f.overview)
        assertNull(f.daysFor(pts))
    }

    /** Pick day 2 → its plan; "Planen" adds a point; the whole tour gets it; a restart keeps all of it. */
    @Test fun dayTwoIsFollowedAndAnEditGoesBackIntoTheTour() {
        val f = opened(flow())
        f.tapDay(2)
        assertEquals(2..2, f.selected)
        val whole = f.plan(pts, Activity.BIKE_TOURING, 2, emptySet(), mapOf(3 to "Gasthof Post"))!!
        val part = f.pickDay(whole, pts)!!
        assertEquals(listOf(pts[2], pts[3]), part.points)
        assertTrue(f.following)
        assertFalse(f.overview)
        assertEquals(null, f.daysFor(part.points)) // a day's plan is no multi-day plan of its own
        // "Planen" on the way: a point between
        val extra = GeoPoint(46.0 + 45 * 0.009, 11.01)
        f.pointsChanged(listOf(pts[2], extra, pts[3]), emptySet()) { if (it == pts[3]) "Gasthof Post" else null }
        val c = f.day!!
        assertEquals(listOf(pts[0], pts[1], pts[2], extra, pts[3], pts[4]), c.whole.points)
        assertEquals(mapOf(2 to NightMark.OVERNIGHT, 4 to hotel), c.whole.nights)
        assertEquals(3, c.whole.days)
        // the app restarts: the day and the whole tour come back
        val g = flow()
        assertTrue(g.following)
        assertEquals(c.whole.points, g.day!!.whole.points)
        assertEquals(c.whole.nights, g.day!!.whole.nights)
        assertEquals(2, g.day!!.part.first)
        // back to all days: the whole tour with the edit, its marks by value
        val (back, opened) = g.leaveDay()!!
        assertEquals(c.whole.points, back.points)
        assertEquals(pts, opened)
        assertEquals(NightMark.OVERNIGHT, g.nightOf(pts[2]))
        assertEquals(hotel, g.nightOf(pts[3]))
    }

    @Test fun severalDaysInARow() {
        val f = opened(flow())
        f.tapDay(2); f.holdDay(3)
        assertEquals(2..3, f.selected)
        f.tapDay(3)
        assertEquals(3..3, f.selected)
        f.tapDay(3)
        assertNull(f.selected)
        f.holdDay(1)
        assertEquals(1..1, f.selected)
    }

    @Test fun anotherRouteLeavesTheTour() {
        val f = opened(flow())
        f.linked(9)
        assertNull(f.tour)
        assertNull(flow().tour) // also after a restart
    }

    @Test fun snappedPointsKeepTheirMarks() {
        val f = opened(flow())
        val moved = GeoPoint(pts[2].lat + 0.00001, pts[2].lon)
        f.snapped(mapOf(pts[2] to moved))
        assertEquals(NightMark.OVERNIGHT, f.nightOf(moved))
        assertNull(f.nightOf(pts[2]))
    }

    /**
     * Robust first (coordinator 2026-11-18): the followed day's state is unreadable after a restart (corrupt prefs) while
     * the plan on the map is that day – linked to the saved 3-day tour. It must never be saved over the whole tour: the
     * start reloads the stored tour as the plan (MultiDaySlice.reloadsTour), and Save's "changed elsewhere" check saves a
     * copy if it ever gets there (SaveRule.keepsOtherEdit: opened = the day's points, stored = the tour's).
     */
    @Test fun anUnreadableDayAfterARestartNeverOverwritesTheTour() {
        val f = opened(flow())
        f.tapDay(2)
        val part = f.pickDay(f.plan(pts, Activity.BIKE_TOURING, 2, emptySet(), mapOf(3 to "Gasthof Post"))!!, pts)!!
        // the app dies; the day's state is damaged on the disk
        sp.planDayFollow = "{\"tour\": broken"
        val g = flow()
        assertTrue("the loss is known", g.dayLost)
        assertFalse(g.following)
        val stored = pts.map { it.lat to it.lon }
        val opened = part.points.map { it.lat to it.lon }
        // the start reloads the whole stored tour …
        assertTrue(com.onpath.app.ui.plan.MultiDaySlice.reloadsTour(dayLost = true, following = false, storedHasDays = true, opened, stored))
        // … also when the day's state is simply missing (the plan's saved points are the day's, not the tour's)
        assertTrue(com.onpath.app.ui.plan.MultiDaySlice.reloadsTour(dayLost = false, following = false, storedHasDays = true, opened, stored))
        // never for the tour itself (its overview, edited or not), a followed day, or a normal route
        assertFalse(com.onpath.app.ui.plan.MultiDaySlice.reloadsTour(dayLost = false, following = false, storedHasDays = true, stored, stored))
        val snapped = stored.map { (la, lo) -> la + 0.0002 to lo } // ~22 m: the router put them onto their ways
        assertFalse(com.onpath.app.ui.plan.MultiDaySlice.reloadsTour(dayLost = false, following = false, storedHasDays = true, snapped, stored))
        assertFalse(com.onpath.app.ui.plan.MultiDaySlice.reloadsTour(dayLost = false, following = true, storedHasDays = true, opened, stored))
        assertFalse(com.onpath.app.ui.plan.MultiDaySlice.reloadsTour(dayLost = true, following = false, storedHasDays = false, opened, stored))
        // and Save, should it come first, writes a copy instead of the tour (the existing check of the view model's overwrite)
        val key = com.onpath.app.sync.SaveRule::pointsKey
        assertTrue(com.onpath.app.sync.SaveRule.keepsOtherEdit(key(opened), key(stored)))
    }

    @Test fun unreadableStateNeverCrashes() {
        sp.planMultiDay = "{broken"; sp.planDayFollow = "[]"
        val f = flow()
        assertNull(f.tour); assertNull(f.day)
        assertTrue(Problems.snapshot().any { it.area == "plan" && it.message.contains("Multi-day") })
    }

    /** Review of the writers (W1): a stitch made on a day of a tour that had no detour points yet reaches the whole tour, with its profile. */
    @Test fun aStitchOnADayOfATourWithoutAnchorsReachesTheTour() {
        fun on(km: Int) = GeoPoint(line[km].lat, line[km].lon)
        val f = opened(flow())
        f.tapDay(2)
        val whole = f.plan(pts, Activity.BIKE_TOURING, 2, emptySet(), mapOf(3 to "Gasthof Post"))!!
        f.pickDay(whole, pts, null)!!
        val extra = GeoPoint(46.0 + 45 * 0.009, 11.01)
        val stitch = mapOf(1 to com.onpath.app.routing.Anchors(listOf(on(40)), listOf(on(50))))
        val profile = com.onpath.app.routing.AnchorProfile.of(Activity.BIKE_TOURING, 2)
        f.pointsChanged(listOf(pts[2], extra, pts[3]), emptySet(), com.onpath.app.routing.AnchorSet(stitch, profile)) { if (it == pts[3]) "Gasthof Post" else null }
        assertEquals(listOf(pts[0], pts[1], pts[2], extra, pts[3], pts[4]), f.day!!.whole.points)
        assertEquals("the day's new anchors are in the tour", mapOf(3 to stitch[1]!!), f.day!!.whole.anchors)
        assertEquals("with the profile they lie on", profile, f.day!!.anchorProfile)
        assertEquals("a restart keeps them", mapOf(3 to stitch[1]!!), flow().day!!.whole.anchors)
    }
}

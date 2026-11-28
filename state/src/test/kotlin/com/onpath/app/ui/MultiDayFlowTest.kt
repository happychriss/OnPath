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
import com.onpath.strings.multiday_day_of
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The phone's multi-day trip (docs/design/multi-day-tours.md §0, owner 2026-11-28): day 2 of a synced 3-day trip picked in My
 * routes → the plan to follow; an edit of the day ("Planen" on the way) goes back into the whole trip with every night mark; a
 * restart keeps the day; another route leaves the trip; the phone never changes the number of days (MD-1).
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

    private fun opened(f: MultiDayFlow): MultiDayFlow { f.opened(tour, pts); return f }

    /** The trip as a plan, as MultiDaySlice.prepareDay builds it from the stored tour. */
    private fun whole(anchors: Map<Int, com.onpath.app.routing.Anchors> = emptyMap(), d: MultiDay = days) =
        d.into(com.onpath.app.routing.SharedPlan(pts, Activity.BIKE_TOURING, 2, emptySet(), mapOf(3 to "Gasthof Post"), "Drei Tage", anchors = anchors))

    /** Day [n] of the trip is the plan (My routes' day sheet): its part, followed by [f]. */
    private fun pick(f: MultiDayFlow, n: Int = 2, anchors: Map<Int, com.onpath.app.routing.Anchors> = emptyMap(),
                     profile: com.onpath.app.routing.AnchorProfile? = anchors.takeIf { it.isNotEmpty() }?.let { com.onpath.app.routing.AnchorProfile.of(Activity.BIKE_TOURING, 2) }
    ): com.onpath.app.domain.plan.DayPart {
        val w = whole(anchors)
        val v = f.compute(w, line, Factors.ONE, "r")!!
        val part = com.onpath.app.domain.plan.DayFollow.part(w, v.days, v.pointAt, v.line, n, n)!!
        f.follow(tour, w, v, part, pts, profile)
        return part
    }

    /** MD-1 (owner 2026-11-28): the phone never changes the number of days – limits per day or not, whatever the pace. */
    @Test fun theDaysKeepTheTripsNumber() {
        val f = flow()
        val limited = whole(d = MultiDay(3, limits = com.onpath.app.routing.DayLimits(maxTimeMin = 60)))
        val v = f.compute(limited, line, Factors.ONE, "r")!!
        assertEquals("three days, as the planner set them", 3, v.days.days.size)
        assertEquals(listOf(30, 60), f.compute(whole(), line, Factors.ONE, "r")!!.days.days.mapNotNull { it.end?.lineIndex })
    }

    /** A whole trip kept as the plan by an older version: nothing to show, but Save keeps its days (never a trip saved without them). */
    @Test fun aWholeTripKeptByAnOlderVersionKeepsItsDaysForSave() {
        val f = opened(flow())
        assertTrue(f.overview)
        assertEquals(days, f.daysFor(pts))
        linked = -1
        assertFalse(f.overview)
        assertNull(f.daysFor(pts))
        linked = 7
        assertTrue(f.overview)
    }

    /** Day 2's first point moves onto the way: a snap, no edit – the anchors on the legs at the day's border stay in the tour. */
    @Test fun aSnapOfADaysBorderPointKeepsTheToursDetourPoints() {
        fun on(km: Int) = GeoPoint(line[km].lat, line[km].lon)
        val anchors = mapOf(1 to com.onpath.app.routing.Anchors(after = listOf(on(25))),
            2 to com.onpath.app.routing.Anchors(listOf(on(27)), listOf(on(40))))
        val f = flow()
        val part = pick(f, 2, anchors)
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
        val f = flow()
        pick(f, 2, anchors)
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
        val f = flow()
        val part = pick(f, 2)
        assertEquals(listOf(pts[2], pts[3]), part.points)
        assertEquals("the trip's night marks by value", hotel, f.nightOf(pts[3]))
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
        // MD-3: start and goal stayed – still the trip's day; MD-4: the goal moved – no longer
        assertTrue(g.day!!.endsKept(listOf(pts[2], extra, pts[3])))
        assertFalse(g.day!!.endsKept(listOf(pts[2], extra)))
        assertEquals(com.onpath.strings.Txt.s(com.onpath.strings.Res.string.multiday_day_of, "2", "3"), g.dayLabel())
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
     * start unlinks the plan and opens the trip's day sheet (MultiDaySlice.reloadsTour), and Save's "changed elsewhere" check saves a
     * copy if it ever gets there (SaveRule.keepsOtherEdit: opened = the day's points, stored = the tour's).
     */
    @Test fun anUnreadableDayAfterARestartNeverOverwritesTheTour() {
        val f = flow()
        val part = pick(f, 2)
        // the app dies; the day's state is damaged on the disk
        sp.planDayFollow = "{\"tour\": broken"
        val g = flow()
        assertTrue("the loss is known", g.dayLost)
        assertFalse(g.following)
        val stored = pts.map { it.lat to it.lon }
        val opened = part.points.map { it.lat to it.lon }
        // the start unlinks the plan (and opens the day sheet) …
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
        val f = flow()
        pick(f, 2, profile = null)
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

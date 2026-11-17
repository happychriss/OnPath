package com.onpath.app.ui

import com.onpath.app.data.MapPrefs
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Prefs
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
    private val sp = MapPrefs()
    private var linked = 7L

    @Before fun setUp() { Problems.init(dir); Problems.clear() }

    /** The unreadable-state test logs an ERROR: cleared, else later Navigate screenshots in this JVM show the announced problem. */
    @org.junit.After fun tearDown() { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); Problems.clear() }

    private fun flow() = MultiDayFlow(Prefs(sp, dir)) { linked }

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

    @Test fun unreadableStateNeverCrashes() {
        sp.edit().putString("planMultiDay", "{broken").putString("planDayFollow", "[]").apply()
        val f = flow()
        assertNull(f.tour); assertNull(f.day)
        assertTrue(Problems.snapshot().any { it.area == "plan" && it.message.contains("Multi-day") })
    }
}

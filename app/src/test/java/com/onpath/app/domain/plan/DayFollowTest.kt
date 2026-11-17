package com.onpath.app.domain.plan

import com.onpath.app.routing.Activity
import com.onpath.app.routing.DayLine
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Hotel
import com.onpath.app.routing.NightMark
import com.onpath.app.routing.PlanDays
import com.onpath.app.routing.SharedPlan
import com.onpath.app.routing.WaypointInsertion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following a day of a multi-day tour (stage 4, docs/design/multi-day-tours.md §3 "Phone"): the picked day becomes a
 * plan of its own (a grey day end its destination point), an edit of it goes back into the whole tour with every night
 * mark where it belongs, the day's new line goes in place of the old stretch.
 */
class DayFollowTest {
    /** 300 km straight north, 1 km per line point, 3 min per km. */
    private val n = 300
    private val pts = (0..n).map { GeoPoint(46.0 + it * 0.009, 11.0) }
    private val line = DayLine(pts, DoubleArray(n + 1) { it * 1000.0 }, DoubleArray(n + 1), DoubleArray(n + 1), DoubleArray(n + 1) { it * 180.0 })

    /** Plan points at km 0, 50, 100 (Übernachtung), 160, 220 (hotel), 300 – 4 days: ends at 100 (blue), 160 (grey), 220 (green). */
    private val at = intArrayOf(0, 50, 100, 150, 220, 300)
    private val hotel = NightMark(Hotel("Gasthof Post", link = "https://b.example/p"))
    private val tour = SharedPlan(at.map { pts[it] }, Activity.BIKE_TOURING, pinned = setOf(1, 3), names = mapOf(4 to "Gasthof Post"),
        days = 4, nights = mapOf(2 to NightMark.OVERNIGHT, 4 to hotel))
    private val days = PlanDays.of(line, WaypointInsertion.routeIndices(tour.points, pts), 4, tour.limits, tour.nights)
    private fun part(first: Int, last: Int = first) = DayFollow.part(tour, days, WaypointInsertion.routeIndices(tour.points, pts), pts, first, last)!!

    @Test fun theDaysAreAsPlanned() {
        assertEquals(4, days.days.size)
        assertEquals(listOf(100, 160, 220), days.days.mapNotNull { it.end?.lineIndex })
    }

    @Test fun dayOneRunsFromTheStartToTheFirstNight() {
        val p = part(1)
        assertEquals(listOf(pts[0], pts[50], pts[100]), p.points)
        assertNull(p.startAdded); assertNull(p.endAdded)
        assertEquals(0, p.from); assertEquals(2, p.to)
        assertEquals(setOf(1), p.pinned)
        assertEquals(mapOf(2 to NightMark.OVERNIGHT), p.nights)
        assertEquals(0, p.startLine); assertEquals(100, p.endLine)
    }

    /** Day 2 ends at a grey proposal: that place becomes the day's destination point (no point of the tour). */
    @Test fun aGreyDayEndBecomesTheDestination() {
        val p = part(2)
        assertEquals(listOf(pts[100], pts[150], pts[160]), p.points)
        assertNull(p.startAdded)
        assertEquals(pts[160], p.endAdded)
        assertEquals(2, p.from); assertEquals(3, p.to)
        assertEquals(setOf(1), p.pinned) // the pin of point 3 is point 1 of the day
        // day 3 starts there and ends at the hotel
        val q = part(3)
        assertEquals(listOf(pts[160], pts[220]), q.points)
        assertEquals(pts[160], q.startAdded); assertNull(q.endAdded)
        assertEquals(mapOf(1 to "Gasthof Post"), q.names)
        assertEquals(mapOf(1 to hotel), q.nights)
    }

    @Test fun severalDaysInARowAndTheLastDay() {
        val p = part(2, 4)
        assertEquals(listOf(pts[100], pts[150], pts[220], pts[300]), p.points)
        assertNull(p.endAdded)
        assertEquals(mapOf(0 to NightMark.OVERNIGHT, 2 to hotel), p.nights)
        assertEquals(listOf(pts[220], pts[300]), part(4).points)
        assertNull(DayFollow.part(tour, days, WaypointInsertion.routeIndices(tour.points, pts), pts, 3, 2))
        assertNull(DayFollow.part(tour, days, WaypointInsertion.routeIndices(tour.points, pts), pts, 1, 5))
    }

    /** Scenario O2: "Planen" on day 2 – a point added; it goes into the whole tour, the marks after it keep their points. */
    @Test fun anEditOfTheDayGoesBackIntoTheWholeTour() {
        val p = part(2)
        val extra = GeoPoint(46.0 + 130 * 0.009, 11.01)
        val edited = listOf(p.points[0], extra, p.points[1], p.points[2])
        val pins = setOf(p.points[1])
        val (whole, now) = DayFollow.writeBack(tour, p, edited, pins, { null }, { if (it == pts[100]) NightMark.OVERNIGHT else null })
        assertEquals(listOf(pts[0], pts[50], pts[100], extra, pts[150], pts[220], pts[300]), whole.points)
        assertEquals(4, whole.days)
        assertEquals(mapOf(2 to NightMark.OVERNIGHT, 5 to hotel), whole.nights)
        assertEquals(mapOf(5 to "Gasthof Post"), whole.names)
        assertEquals(setOf(1, 4), whole.pinned)
        assertEquals(2, now.from); assertEquals(4, now.to)
        // the next edit (the extra point deleted again) on the part as it is now gives the tour back
        val (back, _) = DayFollow.writeBack(whole, now, p.points, pins, { null }, { if (it == pts[100]) NightMark.OVERNIGHT else null })
        assertEquals(tour.points, back.points)
        assertEquals(tour.nights, back.nights)
        assertEquals(tour.pinned, back.pinned)
    }

    /** The day's own start deleted on the way (its Übernachtung point): the night goes, N stays (R2: a grey day comes back). */
    @Test fun aDeletedNightPointOfTheDayGoesNStays() {
        val p = part(2)
        val (whole, _) = DayFollow.writeBack(tour, p, p.points.drop(1), emptySet(), { null }, { null })
        assertEquals(listOf(pts[0], pts[50], pts[150], pts[220], pts[300]), whole.points)
        assertEquals(mapOf(3 to hotel), whole.nights)
        assertEquals(4, whole.days)
        assertTrue(PlanDays.proposalCount(whole) == 2)
    }

    @Test fun theDaysNewLineGoesInPlaceOfTheOldStretch() {
        val p = part(2)
        val day = listOf(pts[100], GeoPoint(46.95, 11.05), pts[160])
        val (spliced, end) = DayFollow.splice(pts, p, day)
        assertEquals(pts.subList(0, 100) + day + pts.subList(161, pts.size), spliced)
        assertEquals(102, end)
        assertEquals(pts, DayFollow.splice(pts, p, emptyList()).first)
    }
}

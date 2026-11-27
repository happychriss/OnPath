package com.onpath.app.ui.routes

import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.DayEnd
import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Hotel
import com.onpath.app.routing.NightMark
import com.onpath.app.routing.TourDay
import com.onpath.app.routing.TourDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The day sheet's rules (owner 2026-11-27): names per day, km / time per day, the days ridden, the next open day. */
class DayPickTest {
    private val pts = listOf(GeoPoint(51.17, 6.94), GeoPoint(50.36, 7.59), GeoPoint(50.0, 8.27), GeoPoint(48.14, 11.58))
    private val route = Tour(1, TourKind.PLANNED, "Von Hilden nach München", 0L, "BIKE_TOURING", 3, 616_000.0, 4200.0, 4100.0, 0.0, "done",
        listOf(GpxWaypoint(pts[0].lat, pts[0].lon, "Hilden"), GpxWaypoint(pts[1].lat, pts[1].lon, "Koblenz"),
            GpxWaypoint(pts[2].lat, pts[2].lon, null), GpxWaypoint(pts[3].lat, pts[3].lon, "München")),
        uuid = "uuid-hilden", days = MultiDay(4, nights = mapOf(1 to NightMark.OVERNIGHT, 2 to NightMark(Hotel("Hotel Mainzer Hof")))))

    private fun day(n: Int, km: Double, h: Double, end: DayEnd?) = TourDay(n, 0.0, 0.0, km * 1000, 100.0 * n, 50.0, h * 3600, end, emptySet())
    private val days = TourDays(listOf(
        day(1, 120.0, 6.0, DayEnd(DayEndKind.OVERNIGHT, 120_000.0, 10, pts[1], planIndex = 1)),
        day(2, 81.0, 4.5, DayEnd(DayEndKind.HOTEL, 201_000.0, 20, pts[2], planIndex = 2)),
        day(3, 200.0, 9.0, DayEnd(DayEndKind.PROPOSAL, 401_000.0, 30, GeoPoint(49.0, 10.0))),
        day(4, 215.0, 10.0, null),
    ), emptyList(), null)

    private fun rode(id: Long, day: Int, ms: Long, deleted: Long? = null, uuid: String = "uuid-hilden", state: String = "done") =
        Tour(id, TourKind.RECORDED, "Tour $id", ms, "BIKE_TOURING", 3, 1.0, 0.0, 0.0, 0.0, state, emptyList(), uuid = "r$id",
            deletedMs = deleted, planUuid = uuid, planDay = day)

    @Test fun eachDayCarriesTheNamesAsThePlanHasThemElseNone() {
        val rows = DayPick.rows(route, days, emptyList())
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.n })
        assertEquals("Hilden" to "Koblenz", rows[0].from to rows[0].to)
        // a point without a name: its night's hotel name
        assertEquals("Koblenz" to "Hotel Mainzer Hof", rows[1].from to rows[1].to)
        // a proposed day end has no name: the row says "Tag 3" only
        assertEquals("Hotel Mainzer Hof", rows[2].from); assertNull(rows[2].to)
        assertNull(rows[3].from); assertEquals("München", rows[3].to)
    }

    @Test fun eachDayCarriesItsKmAscentTimeAndEnd() {
        val rows = DayPick.rows(route, days, emptyList())
        assertEquals(81_000.0, rows[1].distanceM, 0.1)
        assertEquals(4.5 * 3600, rows[1].timeS, 0.1)
        assertEquals(200.0, rows[1].ascentM, 0.1)
        assertEquals(listOf(DayEndKind.OVERNIGHT, DayEndKind.HOTEL, DayEndKind.PROPOSAL, null), rows.map { it.end })
        // the stretches of the line follow each other
        assertEquals(listOf(0 to 10, 10 to 20, 20 to 30), rows.take(3).map { it.fromLine to it.toLine })
        assertEquals(30, rows[3].fromLine)
    }

    @Test fun ridenDaysAreThoseOfRecordedToursNotInTheBin() {
        val tours = listOf(route, rode(10, 1, 1_000L), rode(11, 2, 2_000L, deleted = 5_000L), rode(12, 1, 3_000L),
            rode(13, 3, 4_000L, uuid = "other"), rode(14, 4, 6_000L, state = "active"))
        val r = DayPick.ridden("uuid-hilden", tours)
        assertEquals("day 1: the newest tour's date; day 2's tour is in the bin; the other route's and a running tour do not count",
            mapOf(1 to 3_000L), r)
        assertTrue(DayPick.ridden("", tours).isEmpty())
        assertEquals(3_000L, DayPick.rows(route, days, tours)[0].riddenMs)
        assertNull(DayPick.rows(route, days, tours)[1].riddenMs)
    }

    @Test fun theNextDayIsTheOneAfterTheLastRiddenElseTheFirstOpen() {
        assertEquals(1, DayPick.next(8, emptySet()))
        assertEquals(3, DayPick.next(8, setOf(1, 2)))
        assertEquals("skipped day 1 by train: the day after day 2", 3, DayPick.next(8, setOf(2)))
        assertEquals("the last day ridden: the first open one", 1, DayPick.next(8, setOf(8)))
        assertEquals(2, DayPick.next(3, setOf(1, 3)))
        assertNull(DayPick.next(3, setOf(1, 2, 3)))
    }

    @Test fun aSavedMultiDayRouteOpensTheDaySheetOthersTheOverview() {
        assertTrue(DayPick.opens(route))
        assertFalse(DayPick.opens(route.copy(days = null)))
        assertFalse(DayPick.opens(route.copy(draft = true)))
        assertFalse(DayPick.opens(rode(10, 1, 0L)))
        assertEquals(route, DayPick.route("uuid-hilden", listOf(rode(10, 1, 0L), route)))
        assertNull(DayPick.route("uuid-hilden", listOf(route.copy(deletedMs = 1L))))
    }

    @Test fun gemachtNamesTheRouteAndOpensItOnlyWhileItIsInMyRoutes() {
        val named = DayPick.named(listOf(route, rode(10, 3, 0L), rode(11, 2, 0L, uuid = "gone"), rode(12, 0, 0L))) { id -> if (id == 11L) "Alte Tour" else null }
        assertEquals("Von Hilden nach München" to true, named[1].planName to named[1].planOpens)
        assertEquals("the route is gone: the plan copy's name, no tap", "Alte Tour" to false, named[2].planName to named[2].planOpens)
        assertNull("no day: nothing", named[3].planName)
    }

    @Test fun theDaysAlongTheStoredLineAddUpToTheRoute() {
        val line = (0..400).map { i -> GeoPoint(51.0 - i * 0.01, 7.0 + i * 0.005, 100.0) }
        val t = route.copy(waypoints = listOf(GpxWaypoint(line.first().lat, line.first().lon, "A"), GpxWaypoint(line.last().lat, line.last().lon, "B")),
            days = MultiDay(3))
        val (d, _) = DayPick.days(t, line, Activity.BIKE_TOURING, com.onpath.app.time.Factors.ONE)!!
        assertEquals(3, d.days.size)
        val total = com.onpath.app.data.Geo.length(line)
        assertEquals(total, d.days.sumOf { it.distanceM }, total * 0.01)
        assertTrue(d.days.all { it.timeS > 0 })
        assertNull("no line: no days", DayPick.days(t, emptyList(), Activity.BIKE_TOURING, com.onpath.app.time.Factors.ONE))
    }
}

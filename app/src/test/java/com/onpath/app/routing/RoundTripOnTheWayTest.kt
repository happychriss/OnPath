package com.onpath.app.routing

import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.PlanGpx
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.WaypointInsertion.Kind
import com.onpath.app.ui.MapGestures
import com.onpath.app.ui.MapGestures.PointAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-03 ("Rundtour bleibt Rundtour", decision 4): while the app leads to a round trip, a point set on the
 * way (the bakery) is a **stop on the way** – the loop 1 → 2 → 3 → 1 stays, the lead goes via the new point to ①.
 * The plan stays a plain list of points: [B, 1, 2, 3, 1] = stop on the way B, then the loop. "Hier starten" on a loop
 * point rotates only the loop (a round trip taken over from someone else); nothing ever goes behind the closing point.
 */
class RoundTripOnTheWayTest {
    // the loop near "home" ~2 km away (Hilden-like), ① south-east of the position
    private val p1 = GeoPoint(51.1650, 6.9400)
    private val p2 = GeoPoint(51.1680, 6.9450)
    private val p3 = GeoPoint(51.1620, 6.9480)
    private val loop = listOf(p1, p2, p3, p1)
    private val here = GeoPoint(51.1850, 6.9200)          // being led there, ~2.5 km north-west of ①
    private val bakery = GeoPoint(51.1760, 6.9300)        // on the way between here and ①

    @Test fun aPointOnTheWayToARoundTripIsAStopOnTheWay() {
        val d = WaypointInsertion.decide(loop, bakery, null, 30.0, here = here)
        assertEquals(WaypointInsertion.Decision(0, Kind.ON_THE_WAY), d)
        val after = loop.toMutableList().apply { add(d.index, bakery) }
        assertEquals(listOf(bakery, p1, p2, p3, p1), after)
        assertTrue("still a round trip", PlanEdits.isRoundTrip(after))
        assertEquals("the loop starts behind the stop on the way", 1, PlanEdits.loopStart(after))
    }

    @Test fun aNormalRouteStillGetsANewStart() {
        val route = listOf(p1, p2, p3)
        assertEquals(WaypointInsertion.Decision(0, Kind.START), WaypointInsertion.decide(route, bakery, null, 30.0, here = here))
    }

    @Test fun aRoundTripNeverGetsAPointBehindItsClosingPoint() {
        // far beyond ③ – for a normal route this would be a new destination
        val beyond = GeoPoint(51.1500, 6.9700)
        assertEquals(Kind.DESTINATION, WaypointInsertion.decide(listOf(p1, p2, p3), beyond, null, 30.0).kind)
        val d = WaypointInsertion.decide(loop, beyond, null, 30.0)
        assertEquals(Kind.VIA_BEST_FIT, d.kind)
        assertTrue("inside the loop", d.index in 1..3)
        val after = loop.toMutableList().apply { add(d.index, beyond) }
        assertTrue(PlanEdits.isRoundTrip(after)); assertEquals(0, PlanEdits.loopStart(after))
        // also with a stop on the way before the loop
        val withStop = listOf(bakery) + loop
        val d2 = WaypointInsertion.decide(withStop, beyond, null, 30.0)
        assertTrue(d2.index in 2..4)
        assertEquals(1, PlanEdits.loopStart(withStop.toMutableList().apply { add(d2.index, beyond) }))
    }

    @Test fun recognisedWithStopsOnTheWay() {
        assertEquals(0, PlanEdits.loopStart(loop))
        assertEquals(1, PlanEdits.loopStart(listOf(bakery) + loop))
        assertEquals(2, PlanEdits.loopStart(listOf(here, bakery) + loop))
        // an out-and-back A → B → C → B is no loop with a stop on the way (a loop needs three points)
        assertNull(PlanEdits.loopStart(listOf(bakery, p1, p2, p1)))
        assertFalse(PlanEdits.isRoundTrip(listOf(bakery, p1, p2, p3)))
    }

    @Test fun startHereRotatesOnlyTheLoop() {
        val plan = listOf(bakery, p1, p2, p3, p1)
        // ③ is the new ①: the stop on the way stays in front
        assertEquals(listOf(bakery, p3, p1, p2, p3), PlanEdits.startHere(plan, 3))
        assertEquals(listOf(bakery, p3, p1, p2, p3), PlanEdits.rotateLoop(plan, 3))
        // ① itself: the stop on the way is dropped, the loop stays as it is
        assertEquals(loop, PlanEdits.startHere(plan, 1))
        // the classic round trip as before
        assertEquals(listOf(p2, p3, p1, p2), PlanEdits.startHere(loop, 1))
    }

    @Test fun pointMenuOffersLoopStartOnlyOnLoopPoints() {
        // plan [B, 1, 2, 3, 1]: B is the start, ① (index 1) "Start here", ② / ③ "Start & finish here"
        assertEquals(emptyList<PointAction>(), MapGestures.pointMenu(0, 5, false, roundTrip = true, loopStart = 1).filter { it != PointAction.DELETE })
        assertTrue(PointAction.MAKE_START in MapGestures.pointMenu(1, 5, false, roundTrip = true, loopStart = 1))
        assertTrue(PointAction.LOOP_START in MapGestures.pointMenu(2, 5, false, roundTrip = true, loopStart = 1))
        assertTrue(PointAction.LOOP_START in MapGestures.pointMenu(3, 5, false, roundTrip = true, loopStart = 1))
        // classic round trip unchanged
        assertTrue(PointAction.LOOP_START in MapGestures.pointMenu(1, 4, false, roundTrip = true))
    }

    @Test fun pointsListKeepsTheLoopClosedAtItsOne() {
        val plan = listOf(bakery, p1, p2, p3, p1)
        val rows = PlanPoints.rows(plan, null) { null }
        assertEquals(4, rows.size)
        assertEquals(PointRole.START, rows[0].role)
        assertTrue("① carries the loop", rows[1].loop); assertEquals(PointRole.GOAL, rows[1].role)
        // delete ③: the loop closes at ① again
        assertEquals(listOf(bakery, p1, p2, p1), PlanPoints.delete(plan, 3))
        // delete the stop on the way: the classic round trip
        assertEquals(loop, PlanPoints.delete(plan, 0))
        // the closing point (map menu) = ① = row 1: the next loop point is ①
        assertEquals(listOf(bakery, p2, p3, p2), PlanPoints.deleteAt(plan, 4))
        // move ③ before ②: the loop still closes at ①
        assertEquals(listOf(bakery, p1, p3, p2, p1), PlanPoints.move(plan, 3, 2))
    }

    /** The stored format is unchanged: the plan GPX and the share code carry the list as it is. */
    @Test fun saveAndLoadKeepTheStopOnTheWay() {
        val plan = listOf(bakery, p1, p2, p3, p1)
        val line = plan.zipWithNext().flatMapIndexed { li, (p, q) ->
            (0..20).drop(if (li == 0) 0 else 1).map { k -> val f = k / 20.0; GeoPoint(p.lat + (q.lat - p.lat) * f, p.lon + (q.lon - p.lon) * f, 300.0) }
        }
        val f = PlanGpx.export("Rundtour", listOf(line.map { TrackPoint(it.lat, it.lon, it.ele) }), PlanGpx.stops(plan) { null },
            Activity.HIKE, 2, 1_790_000_000_000L)
        val data = GpxParser.parse(f.text)
        val back = PlanGpx.stopsOf(data.allSegments.flatten().map { it.toGeo() }, data.waypoints)
        assertNotNull(back)
        val pts = back!!.map { GeoPoint(it.lat, it.lon) }
        assertEquals(plan.size, pts.size)
        assertEquals(1, PlanEdits.loopStart(pts))
        val shared = PlanCode.decode(PlanCode.encode(SharedPlan(plan, Activity.HIKE, 2)))
        assertNotNull(shared)
        assertEquals(1, PlanEdits.loopStart(shared!!.points))
    }

    /** The search card / pin menu says what adding does: "Add as a stop on the way" – not "Set as start". */
    @Test fun theAddButtonSaysStopOnTheWay() {
        val d = WaypointInsertion.decide(loop, bakery, null, 30.0, here = here)
        assertEquals("Add as a stop on the way", com.onpath.app.search.AddPreview.label(d, loop.size))
        assertEquals("Set as start", com.onpath.app.search.AddPreview.label(WaypointInsertion.Decision(0, Kind.START), 3))
    }

    /** "↻ Round trip" on a plan that already ends in a loop behind a stop on the way changes nothing. */
    @Test fun roundTripButtonKeepsAnExistingLoop() {
        val plan = listOf(bakery, p1, p2, p3, p1)
        assertEquals(plan, PlanEdits.roundTrip(plan))
        assertEquals(loop, PlanEdits.roundTrip(listOf(p1, p2, p3)))
    }

    /** Points list: the stop on the way is the start, the loop's ① reads "Destination" (where the tour ends). */
    @Test fun pointsListNamesTheRoles() {
        val rows = PlanPoints.rows(listOf(bakery, p1, p2, p3, p1), null) { "Ort $it" }
        assertEquals("Start", PlanPoints.roleText(rows[0]))
        assertEquals("Destination", PlanPoints.roleText(rows[1]))
        assertNull(PlanPoints.roleText(rows[2]))
        // the classic round trip: "Start and finish" on ①
        assertEquals("Start & finish", PlanPoints.roleText(PlanPoints.rows(loop, null) { "Ort $it" }[0]))
    }
}

package com.onpath.app.routing

import com.onpath.app.routing.WaypointInsertion.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class WaypointInsertionTest {

    // A straight west→east line of ~10 km at Meran's latitude.
    private val a = GeoPoint(46.67, 11.10)
    private val b = GeoPoint(46.67, 11.23)
    private fun line(from: GeoPoint, to: GeoPoint, n: Int = 50) =
        (0..n).map { GeoPoint(from.lat + (to.lat - from.lat) * it / n, from.lon + (to.lon - from.lon) * it / n) }

    @Test fun firstPointIsStart() {
        assertEquals(WaypointInsertion.Decision(0, Kind.START), WaypointInsertion.decide(emptyList(), a, null, 30.0))
    }

    @Test fun secondPointIsDestination() {
        assertEquals(WaypointInsertion.Decision(1, Kind.DESTINATION), WaypointInsertion.decide(listOf(a), b, null, 30.0))
    }

    @Test fun pointBesideMiddleBecomesVia() {
        val p = GeoPoint(46.69, 11.165) // ~2 km north of the middle
        val d = WaypointInsertion.decide(listOf(a, b), p, line(a, b), 30.0)
        assertEquals(1, d.index)
        assertEquals(Kind.VIA_BEST_FIT, d.kind)
    }

    @Test fun pointBeyondDestinationBecomesNewDestination() {
        val p = GeoPoint(46.67, 11.25) // ~1.5 km east of B
        val d = WaypointInsertion.decide(listOf(a, b), p, line(a, b), 30.0)
        assertEquals(WaypointInsertion.Decision(2, Kind.DESTINATION), d)
    }

    @Test fun tapOnLineInsertsIntoThatLeg() {
        val c = GeoPoint(46.70, 11.30)
        val route = line(a, b) + line(b, c).drop(1)
        // Tap 10 m off the second leg, close to c – best-fit alone could also say "append".
        val p = GeoPoint(46.6985 + 0.00009, 11.2965)
        val d = WaypointInsertion.decide(listOf(a, b, c), p, route, 40.0)
        assertEquals(WaypointInsertion.Decision(2, Kind.VIA_ON_LINE), d)
    }

    @Test fun cheapestLegIsChosenWithThreeWaypoints() {
        val c = GeoPoint(46.75, 11.23) // A→B east, then B→C north
        val p = GeoPoint(46.71, 11.245) // beside the B→C leg
        val d = WaypointInsertion.decide(listOf(a, b, c), p, null, 30.0)
        assertEquals(WaypointInsertion.Decision(2, Kind.VIA_BEST_FIT), d)
    }

    @Test fun outAndBackRouteMapsLegsInOrder() {
        // A→B→A: a tap near the line must land in a valid leg (0 or 1), never crash.
        val route = line(a, b) + line(b, a).drop(1)
        val leg = WaypointInsertion.legOfRouteIndex(listOf(a, b, a), route, 75)
        assertEquals(1, leg)
    }

    @Test fun segmentDistanceIsMetres() {
        val d = WaypointInsertion.distanceToSegment(GeoPoint(46.671, 11.165), a, b)
        assertEquals(111.0, d, 3.0)
    }
}

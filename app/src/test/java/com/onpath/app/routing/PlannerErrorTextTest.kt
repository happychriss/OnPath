package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A failed leg is one short ERROR line – no "Leg", no "straight dashed line", no advice. */
class PlannerErrorTextTest {
    private val pts = (0..2).map { GeoPoint(46.6 + it * 0.1, 11.1) }

    @Test fun failedLegIsOneShortLine() {
        val e = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                if (waypoints.size > 2 || waypoints[0] == pts[1]) throw RoutingException("No route found between these points for MTB.")
                return RouteResult(waypoints, 1000.0, 0.0, 0.0, 60.0)
            }
        }
        val r = Planner(e).plan(pts, Activity.MTB, 3)
        assertEquals("Point 2 → 3: no path found", r.error)
    }

    @Test fun twoPointsAndTheKinds() {
        assertEquals("Point 1 → 2: no path found", Planner.legError(1, 2, RoutingException("No route found.")))
        assertEquals("Point 1 → 2: took too long", Planner.legError(1, 2, RoutingException("x", timedOut = true)))
        assertEquals("Point 3 → 4: too long – add a point between",
            Planner.legError(3, 4, RoutingException("Not enough memory for this route. Add via points or choose closer points.")))
        // missing routing data keeps its text: the plan sheet turns it into the grey "Map loading" line
        assertEquals(BRouterEngine.NO_DATA, Planner.legError(1, 2, RoutingException(BRouterEngine.NO_DATA)))
        listOf("Leg", "dashed", "Try moving").forEach { w ->
            assertFalse(w, Planner.legError(1, 2, RoutingException("No route found.")).contains(w))
        }
    }
}

package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** After a timeout the planner does not route the other legs (each would wait its own limit). */
class PlannerTimeoutTest {
    private val pts = (0..3).map { GeoPoint(46.6 + it * 0.1, 11.1) }

    @Test fun timeoutGivesStraightLinesAtOnce() {
        val calls = mutableListOf<Int>()
        val e = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                calls += waypoints.size
                throw RoutingException("Route calculation took too long (over 90 s).", timedOut = true)
            }
        }
        val p = Planner(e)
        val r = p.plan(pts, Activity.MTB, 3)
        assertEquals(listOf(2), calls) // the first leg once, the rest not
        assertEquals(3, r.legs.size)
        assertTrue(r.legs.all { it.straight })
        assertTrue(r.error!!.contains("too long"))
        assertEquals(1, p.tries.size)
        assertEquals("timeout", p.tries[0].outcome)
    }

    @Test fun aLegWithoutARouteDoesNotStopTheOthers() {
        val calls = mutableListOf<Int>()
        val e = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                calls += waypoints.size
                if (waypoints[0] == pts[1]) throw RoutingException("No route found.")
                return RouteResult(waypoints, 1000.0, 0.0, 0.0, 60.0)
            }
        }
        val p = Planner(e)
        val r = p.plan(pts, Activity.MTB, 3)
        assertEquals(listOf(2, 2, 2), calls)
        assertEquals(listOf(false, true, false), r.legs.map { it.straight })
        assertEquals(listOf("ok", "failed", "ok"), p.tries.map { it.outcome })
        assertTrue(p.describeTries().startsWith("2 pts "))
    }
}

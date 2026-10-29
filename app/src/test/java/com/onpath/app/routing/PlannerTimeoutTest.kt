package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** After a timeout the planner does not retry leg by leg (each leg its own limit). */
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
        assertEquals(listOf(4), calls) // the whole route once, no legs
        assertEquals(3, r.legs.size)
        assertTrue(r.legs.all { it.straight })
        assertTrue(r.error!!.contains("too long"))
        assertEquals(1, p.tries.size)
        assertEquals("timeout", p.tries[0].outcome)
    }

    @Test fun otherFailuresStillTryLegByLeg() {
        val calls = mutableListOf<Int>()
        val e = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                calls += waypoints.size
                if (waypoints.size > 2) throw RoutingException("No route found.")
                return RouteResult(waypoints, 1000.0, 0.0, 0.0, 60.0)
            }
        }
        val p = Planner(e)
        val r = p.plan(pts, Activity.MTB, 3)
        assertEquals(listOf(4, 2, 2, 2), calls)
        assertTrue(r.legs.none { it.straight })
        assertEquals(listOf("failed", "ok", "ok", "ok"), p.tries.map { it.outcome })
        assertTrue(p.describeTries().startsWith("4 pts "))
    }
}

package com.onpath.app.ui.plan

import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanResult
import com.onpath.app.routing.RouteLimits
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.RoutingException
import com.onpath.app.time.Factors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * The detour hint's background step ([PlanDetourCheck]): the result belongs to exactly the route and points it was found for;
 * a newer plan stops the router and drops the older result; a failed check leaves no hint.
 */
class PlanDetourCheckTest {
    private val a = GeoPoint(46.7000, 11.1000)
    private val p = GeoPoint(46.7000, 11.1100)
    private val b = GeoPoint(46.7000, 11.1200)

    private val pool = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val check = PlanDetourCheck(scope, { pool.asCoroutineDispatcher() }, Dispatchers.Unconfined)

    @After fun close() { scope.cancel(); pool.shutdownNow() }

    /** T1 3 + 3 km via the point, T2 1 + 1 km, the neighbours 1.6 km at both. [atT2] runs on each T2 request. */
    private inner class Engine(val atT2: (RouteLimits) -> Unit = {}) : RoutingEngine {
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int) =
            route(waypoints, activity, sacLimit, RouteLimits(30_000))
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
            if (sacLimit == 2) atT2(limits)
            val from = waypoints.first(); val to = waypoints.last()
            val km = if (from == a && to == b) 1.6 else if (sacLimit == 1) 3.0 else 1.0
            val d = Geo.distance(from, to); val m = km * 1000
            val h = sqrt(((m / 2) * (m / 2) - (d / 2) * (d / 2)).coerceAtLeast(1.0))
            val l = listOf(from, GeoPoint((from.lat + to.lat) / 2 + h / 111_195.0, (from.lon + to.lon) / 2), to)
            return RouteResult(l, Geo.length(l), 0.0, 0.0, 60.0, matched = listOf(from, to))
        }
    }

    private fun result(e: RoutingEngine) = PlanResult.plan(e, listOf(a, p, b), Activity.HIKE, 1, emptySet(), emptySet(), Factors())

    /** Waits until the check's thread has done all it was given. */
    private fun settle() { pool.shutdown(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }

    @Test fun aDetourBelongsToExactlyThisRouteAndThesePoints() {
        val e = Engine(); val res = result(e)
        check.start(res) { e }
        settle()
        assertEquals(1, check.detoursFor(res.route, res.points).single().index)
        assertEquals("the same route with a new time", 1, check.detoursFor(res.route.copy(durationS = 1.0), res.points).size)
        assertEquals("another route", emptyList<Any>(), check.detoursFor(result(e).route, res.points))
        assertEquals("other points", emptyList<Any>(), check.detoursFor(res.route, listOf(a, b)))
        assertEquals("no route", emptyList<Any>(), check.detoursFor(null, res.points))
    }

    @Test fun aNewerPlanStopsTheRouterAndDropsTheOlderResult() {
        val inside = CountDownLatch(1); val release = CountDownLatch(1)
        var cancelled = false
        val e = Engine { limits ->
            limits.cancel?.onCancel { cancelled = true; release.countDown() }
            inside.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw RoutingException("cancelled", kind = RoutingException.Kind.CANCELLED)
        }
        val res = result(e)
        check.start(res) { e }
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        check.stop()
        settle()
        assertTrue("the router was told to stop", cancelled)
        assertNull(check.found)
        assertEquals(emptyList<Any>(), check.detoursFor(res.route, res.points))
    }

    @Test fun aResultThatArrivesAfterANewerPlanIsDropped() {
        val inside = CountDownLatch(1); val release = CountDownLatch(1)
        val e = Engine { inside.countDown(); release.await(10, TimeUnit.SECONDS) } // an engine that ignores the cancel signal
        val res = result(e)
        check.start(res) { e }
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        check.stop()
        release.countDown()
        settle()
        assertNull(check.found)
    }

    @Test fun aFailedCheckLeavesNoHint() {
        val e = Engine { throw IllegalStateException("router broke") }
        val res = result(e)
        check.start(res) { e }
        settle()
        assertNull(check.found)
    }

    @Test fun anEngineThatCannotStartLeavesNoHint() {
        val res = result(Engine())
        check.start(res) { throw IllegalStateException("no engine") }
        settle()
        assertNull(check.found)
    }
}

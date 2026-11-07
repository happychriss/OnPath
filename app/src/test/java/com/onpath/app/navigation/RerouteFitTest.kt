package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.navigation.Trace.walk
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteLimits
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field case tour 221 (owner on foot, Hilden, 2026-11-07 13:42–13:46): he left a 2-point plan and the app made four new
 * routes in 2½ minutes; after taking one, the next off-route question came ~24 s later, and one said "Neue Route. Bitte
 * umdrehen." Rebuilt off-device (GPS points of the tour, BRouter on the JVM): every new route started where the off-route
 * state was confirmed and was the shortest from there. He took it 3–10 s later, 10–40 m on – at 13:43:10 13 m down the
 * left branch of a fork whose right branch the route took; the next question then turned him round.
 *
 * Geometry: the real ways around that fork (BRouter's nodes). He came from the east (E), the fork is J; the route took
 * the north-west branch to K and north to the destination; he walked the south branch to S1 and went north on the
 * parallel way (NS) – a way on to the same K, ~70 m longer.
 */
class RerouteFitTest {
    private fun g(lat: Double, lon: Double) = GeoPoint(lat, lon)
    private val j = g(51.166565, 6.928873)
    private val k = g(51.166750, 6.928437)
    private val s1 = g(51.166174, 6.928300)
    private val dest = g(51.169285, 6.925412)
    private val wayE = listOf(g(51.166585, 6.929253), g(51.166569, 6.929162), g(51.166552, 6.929002), g(51.166554, 6.928935),
        g(51.166558, 6.928910), j)
    private val wayNW = listOf(j, g(51.166709, 6.928562), k)
    private val wayN = listOf(k, g(51.166761, 6.928373), g(51.166809, 6.928289), g(51.166848, 6.928255), g(51.166884, 6.928253),
        g(51.166980, 6.928279), g(51.167094, 6.928305), g(51.167175, 6.928404), g(51.167237, 6.928481), g(51.167337, 6.928605),
        g(51.167402, 6.928686), g(51.167443, 6.928595), g(51.167490, 6.928487), g(51.167521, 6.928412), g(51.167571, 6.928297),
        g(51.167904, 6.928815), g(51.167940, 6.928939), g(51.168152, 6.928611), g(51.168665, 6.928104), g(51.169010, 6.927505),
        g(51.169344, 6.926936), g(51.169556, 6.926573), g(51.169413, 6.925944), dest)
    private val wayS = listOf(j, g(51.166446, 6.928724), g(51.166347, 6.928698), s1)
    private val wayNS = listOf(g(51.165617, 6.928328), g(51.165758, 6.928299), g(51.165951, 6.928307), s1, g(51.166358, 6.928237),
        g(51.166491, 6.928116), g(51.166510, 6.928097), g(51.166631, 6.928308), g(51.166678, 6.928370), k)
    private val engine = WayGraphEngine(listOf(wayE, wayNW, wayN, wayS, wayNS))

    /** The owner's GPS points 13:42:57–13:43:40 (tour 221), one per 1–2 s. */
    private val track = listOf(51.166585 to 6.929253, 51.166582 to 6.929212, 51.166574 to 6.929170, 51.166553 to 6.929097,
        51.166542 to 6.929029, 51.166535 to 6.928947, 51.166534 to 6.928900, 51.166525 to 6.928857, 51.166516 to 6.928815,
        51.166487 to 6.928758, 51.166447 to 6.928714, 51.166400 to 6.928678, 51.166375 to 6.928659, 51.166335 to 6.928612,
        51.166317 to 6.928579, 51.166292 to 6.928539, 51.166271 to 6.928501, 51.166249 to 6.928462, 51.166227 to 6.928421,
        51.166205 to 6.928386, 51.166181 to 6.928364, 51.166149 to 6.928352, 51.166115 to 6.928349, 51.166077 to 6.928340,
        51.166045 to 6.928335, 51.166016 to 6.928329, 51.165986 to 6.928322, 51.165937 to 6.928310).map { g(it.first, it.second) }

    private fun request(upTo: GeoPoint, to: GeoPoint = dest): Rerouter.Request {
        val start = wayE.first()
        val plan = engine.route(listOf(start, to), Activity.HIKE, 1).points
        val nl = NavLine(plan)
        val stops = nl.alongsInOrder(listOf(start, to)).mapIndexed { i, a -> NavStop(listOf(start, to)[i], a) }
        val n = track.indexOf(upTo)
        return Rerouter.Request(upTo, nl, 30.0, stops, emptySet(), Activity.HIKE, 1, recent = track.subList(maxOf(0, n - 29), n + 1))
    }

    private fun near(line: List<GeoPoint>, p: GeoPoint, m: Double, withinM: Double = Double.MAX_VALUE): Boolean {
        val cum = Geo.cumulative(line)
        return line.indices.any { cum[it] <= withinM && Geo.distance(line[it], p) <= m }
    }

    /** 13:43:14, 15 m down the south branch: the shortest way leads back to the fork – the way on is taken (~70 m more). */
    @Test fun pastTheForkTheNewRouteGoesOnTheWayHeWalks() {
        val out = Rerouter(engine).reroute(request(g(51.166400, 6.928678))) as Rerouter.Found
        assertFalse("not back to the fork", near(out.line, j, 10.0))
        assertTrue("on down the south branch to its end and north from there", near(out.line, s1, 5.0, withinM = 60.0))
        assertEquals(dest, out.line.last())
    }

    /** The same without his direction (standing still, just started): the shortest route, back to the fork. */
    @Test fun withoutADirectionTheShortestRouteStays() {
        val out = Rerouter(engine).reroute(request(g(51.166400, 6.928678)).copy(recent = emptyList())) as Rerouter.Found
        assertTrue(near(out.line, j, 5.0, withinM = 40.0))
    }

    /** 13:43:32 at the south end of NS (a dead end on the map): turning round is the only way – it stays. */
    @Test fun aDeadEndAheadStillTurnsRound() {
        val pos = g(51.165937, 6.928310)
        val out = Rerouter(engine).reroute(request(pos)) as Rerouter.Found
        val to = out.line.first { Geo.distance(out.line.first(), it) >= 30.0 }
        assertTrue("goes north, back the way he came", to.lat > pos.lat)
        assertTrue(TurnRound.needed(request(pos).recent, out.line))
    }

    /** Close to the destination going on costs far more than the 15 m back: the way back stays. */
    @Test fun aShortRestKeepsTheWayBackWhenGoingOnCostsMuchMore() {
        val close = g(51.166884, 6.928253)
        val out = Rerouter(engine).reroute(request(g(51.166400, 6.928678), to = close)) as Rerouter.Found
        assertTrue(near(out.line, j, 5.0, withinM = 40.0))
    }

    @Test fun goesBackAndThereAndBack() {
        val recent = track.subList(0, 12)
        // back to the fork over the metres just walked
        assertTrue(Onward.goesBack(recent, listOf(g(51.166400, 6.928678), j, k)))
        // on along the south branch
        assertFalse(Onward.goesBack(recent, listOf(g(51.166400, 6.928678), s1, g(51.166358, 6.928237), k)))
        // a spur to a point ahead and back
        assertTrue(Onward.thereAndBack(listOf(g(51.165951, 6.928307), g(51.165617, 6.928328), g(51.165951, 6.928307), s1), 120.0))
        assertFalse(Onward.thereAndBack(wayS + wayNS.drop(4), 200.0))
        assertTrue(Onward.worthIt(400.0, 480.0))
        assertFalse(Onward.worthIt(400.0, 600.0))
        assertTrue("a short rest: half a minute more is fine", Onward.worthIt(40.0, 70.0))
    }

    // ---- "Take new route" after walking on (Navigator) ------------------------------------------------------------

    /** East 1 km, then north 1 km (local metres, [Trace]). */
    private val route = line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 1000.0)
    private fun session() = NavSession.start(NavKind.PLAN, "Test", Activity.HIKE, 3, route,
        listOf(pt(0.0, 0.0), pt(1000.0, 1000.0)))

    /** A new route from [from] back to the corner and north along the route. */
    private fun found(from: Pair<Double, Double>): Rerouter.Found {
        val leg = line(from, 1000.0 to 0.0, 1000.0 to 200.0)
        val l = RerouteTargets.splice(leg, NavLine(route), 1200.0)
        return Rerouter.Found(l, Geo.length(leg), 1200.0, 100.0, 110.0, 0.0, null, emptySet(), 1, 10)
    }

    /** Walked past the corner to (1150, 0): the question with its new route, the job it came from. */
    private fun asked(nav: Navigator): Int {
        val steps = walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0).map { nav.onFix(it) }
        val job = steps.mapNotNull { it.job }.single()
        assertEquals("the walker's positions go with the request", 30, job.request.recent.size)
        nav.onResult(job.id, found(1150.0 to 0.0))
        return (nav.prompt as NavPrompt.Question).id
    }

    @Test fun takenRightAwayTheShownRouteIsTaken() {
        val nav = Navigator(session())
        val q = asked(nav)
        nav.onFix(walk(1150.0 to 0.0, 1155.0 to 0.0, t0Ms = 1_000_000).last())
        val step = nav.take(q)
        assertTrue(step.lineChanged)
        assertNull(step.job)
        assertEquals(found(1150.0 to 0.0).line, nav.session.active)
    }

    /** Field 13:42:45 / 13:43:10: walked 30 m on, beside the new line – it is computed again from here and taken. */
    @Test fun takenAfterWalkingOnTheRouteIsComputedFromHere() {
        val nav = Navigator(session())
        val q = asked(nav)
        val f = walk(1150.0 to 0.0, 1180.0 to 0.0, t0Ms = 1_000_000).onEach { nav.onFix(it) }.last()
        val step = nav.take(q)
        assertFalse("not the stale line", step.lineChanged)
        val job = step.job
        assertNotNull("computed again from here", job)
        assertEquals(0.0, Geo.distance(job!!.request.position, f.point), 0.1)
        assertEquals(NavPrompt.Searching, nav.prompt)
        val fresh = found(1180.0 to 0.0)
        val done = nav.onResult(job.id, fresh)
        assertTrue(done.lineChanged)
        assertNull(nav.prompt)
        assertEquals(fresh.line, nav.session.active)
        assertTrue("it starts behind him: turn round", nav.state.uTurnAt != null)
    }

    @Test fun noRouteFromHereTakesTheShownOne() {
        val nav = Navigator(session())
        val q = asked(nav)
        walk(1150.0 to 0.0, 1180.0 to 0.0, t0Ms = 1_000_000).forEach { nav.onFix(it) }
        val job = nav.take(q).job!!
        nav.onResult(job.id, Rerouter.Failed(Rerouter.Reason.NO_WAY, "none", 5))
        assertEquals(found(1150.0 to 0.0).line, nav.session.active)
        assertNull(nav.prompt)
    }

    @Test fun keepWhileComputingAgainTakesNothing() {
        val nav = Navigator(session())
        val q = asked(nav)
        walk(1150.0 to 0.0, 1180.0 to 0.0, t0Ms = 1_000_000).forEach { nav.onFix(it) }
        val job = nav.take(q).job!!
        nav.keep(-1)
        assertEquals(NavPrompt.Kept, nav.prompt)
        nav.onResult(job.id, Rerouter.Failed(Rerouter.Reason.CANCELLED, "Cancelled", 5)) // the service cancels it
        assertEquals(NavPrompt.Kept, nav.prompt)
        assertFalse(nav.session.rerouted)
    }
}

/**
 * A router on a few real ways: each waypoint is met at the nearest node (like BRouter's snapping), the shortest path
 * between them along the ways (Dijkstra). No way there = RoutingException.
 */
internal class WayGraphEngine(ways: List<List<GeoPoint>>) : RoutingEngine {
    private val nodes = ways.flatten().distinct()
    private val edges = HashMap<GeoPoint, MutableList<Pair<GeoPoint, Double>>>().apply {
        for (w in ways) for (i in 1 until w.size) {
            val d = Geo.distance(w[i - 1], w[i])
            getOrPut(w[i - 1]) { ArrayList() } += w[i] to d
            getOrPut(w[i]) { ArrayList() } += w[i - 1] to d
        }
    }

    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
        route(waypoints, activity, sacLimit, RouteLimits(30_000))

    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
        val met = waypoints.map { w -> nodes.minByOrNull { Geo.distance(it, w) }!! }
        val pts = ArrayList<GeoPoint>()
        for (i in 1 until met.size) {
            val leg = path(met[i - 1], met[i])
            pts.addAll(if (pts.isEmpty()) leg else leg.drop(1))
        }
        if (pts.size < 2) pts += met.last()
        val d = Geo.length(pts)
        return RouteResult(pts, d, 0.0, 0.0, d / 1.2)
    }

    private fun path(a: GeoPoint, b: GeoPoint): List<GeoPoint> {
        val dist = HashMap<GeoPoint, Double>().apply { put(a, 0.0) }
        val prev = HashMap<GeoPoint, GeoPoint>()
        val open = java.util.PriorityQueue<Pair<GeoPoint, Double>>(compareBy { it.second }).apply { add(a to 0.0) }
        while (open.isNotEmpty()) {
            val (n, d) = open.poll()
            if (n == b) break
            if (d > (dist[n] ?: Double.MAX_VALUE)) continue
            for ((m, w) in edges[n].orEmpty()) {
                if (d + w < (dist[m] ?: Double.MAX_VALUE)) { dist[m] = d + w; prev[m] = n; open.add(m to d + w) }
            }
        }
        if (b !in dist) throw com.onpath.app.routing.RoutingException("No route found")
        val out = ArrayList<GeoPoint>()
        var c: GeoPoint? = b
        while (c != null) { out += c; c = prev[c] }
        return out.asReversed()
    }
}

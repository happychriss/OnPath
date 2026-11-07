package com.onpath.app.data

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.Planner
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.Share
import com.onpath.app.routing.StoredLine
import com.onpath.app.routing.WayAttrs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Saved routes load without routing again (owner 2026-11-07, docs/design/long-bike-routes.md "Saved routes"): the legs
 * of a route are kept on the phone (LegFiles) with the routing code and data they were computed with; after an app
 * restart a loaded route takes them – zero router calls, the same line with every attribute – while an edit routes only
 * its new legs, another activity everything, and newer routing data makes them a stand-in that is routed again.
 */
class LegFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Records every request; the line goes a little aside through a middle point carrying way, turn and shape. */
    private class Engine : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            val a = waypoints.first(); val b = waypoints.last()
            val way = WayAttrs("path", "gravel", sac = 2, smoothness = "bad", bicycle = "yes", cycleRoute = true, barrier = "stile")
            val mid = GeoPoint((a.lat + b.lat) / 2 + 0.001, (a.lon + b.lon) / 2, 412.3, way, JunctionCue.ROUNDABOUT, 2, -85.25f,
                JunctionShape(12.5f, 3, 4, listOf(JunctionShape.Branch(-90.75f, 2))))
            val line = listOf(a.copy(ele = 400.0, way = way), mid, GeoPoint(b.lat, b.lon, 401.1))
            return RouteResult(line, 1234.5, 12.25, 11.0, 333.3, listOf(Share("path", 1000.0), Share("road", 234.5)),
                listOf(Share("gravel", 1234.5)), listOf(a, b))
        }
    }

    private val p = (0..6).map { GeoPoint(46.6 + it * 0.02, 11.1 + it * 0.01) }
    private val act = Activity.BIKE_TOURING

    /** Routing data versions by part ("-" = not on the phone). */
    private val versions = HashMap<String, String>()
    private var fp: String? = "r1-0123456789ab"

    /** A clock that moves on with every use (the least used leg is the one with the oldest time). */
    private var clock = 1_700_000_000_000L

    private fun files(maxFiles: Int = LegFiles.MAX_FILES) =
        LegFiles(tmp.root.resolve("legs"), { fp }, { versions[it.key] ?: "1730000000000" }, maxFiles = maxFiles,
            now = { clock += 1_000; clock })

    /** A new process: an empty memory, the same files on the phone. */
    private fun restart() = LegCache(store = files())

    private fun plan(e: Engine, cache: LegCache, vararg pts: GeoPoint, activity: Activity = act, sac: Int = 2) =
        Planner(e, legs = cache).plan(pts.toList(), activity, sac)

    private fun legs(vararg pts: GeoPoint) = pts.toList().zipWithNext { a, b -> listOf(a, b) }

    @Test fun aSavedRouteLoadedAfterARestartRoutesNothingAndGivesTheSameLine() {
        val e = Engine()
        val first = plan(e, restart(), p[0], p[2], p[4], p[6])
        assertEquals(3, e.calls.size)
        e.calls.clear()
        val planner = Planner(e, legs = restart())
        val again = planner.plan(listOf(p[0], p[2], p[4], p[6]), act, 2)
        assertTrue("no router run", e.calls.isEmpty())
        assertEquals(first, again)
        // every attribute back bit for bit, not only lat / lon / ele (GeoPoint's equality)
        assertEquals(first.points.map { listOf(it.way, it.turn, it.exit, it.turnAngle, it.shape) },
            again.points.map { listOf(it.way, it.turn, it.exit, it.turnAngle, it.shape) })
        assertEquals(3, planner.storedLegs)
        assertTrue(planner.describeTries(), planner.describeTries().contains("3 legs from the cache (3 stored)"))
    }

    @Test fun changingOnePointAfterALoadRoutesOnlyItsTwoNeighbouringLegs() {
        val e = Engine()
        plan(e, restart(), p[0], p[2], p[4], p[6])
        e.calls.clear()
        val cache = restart()
        plan(e, cache, p[0], p[2], p[4], p[6])
        assertTrue(e.calls.isEmpty())
        plan(e, cache, p[0], p[3], p[4], p[6])
        assertEquals(legs(p[0], p[3], p[4]), e.calls)
    }

    @Test fun anotherActivityOrTLevelRoutesTheWholePlan() {
        val e = Engine()
        plan(e, restart(), p[0], p[2], p[4], activity = Activity.HIKE, sac = 2)
        e.calls.clear()
        plan(e, restart(), p[0], p[2], p[4], activity = Activity.MTB)
        assertEquals(legs(p[0], p[2], p[4]), e.calls)
        e.calls.clear()
        plan(e, restart(), p[0], p[2], p[4], activity = Activity.HIKE, sac = 3)
        assertEquals(legs(p[0], p[2], p[4]), e.calls)
        e.calls.clear()
        plan(e, restart(), p[0], p[2], p[4], activity = Activity.HIKE, sac = 2)
        assertTrue(e.calls.isEmpty())
    }

    @Test fun newerRoutingDataUnderALegMakesItAStandInThatIsRoutedAgain() {
        // the first leg starts in another 1° routing part than the second leg lies in
        val a = GeoPoint(45.5, 10.5); val b = GeoPoint(46.5, 11.5); val c = GeoPoint(46.6, 11.6)
        val e = Engine()
        val first = plan(e, restart(), a, b, c)
        e.calls.clear()
        // brouter.de's newer build of the data under the first leg only
        versions[Rd5Parts.partOf(a.lat, a.lon).key] = "1740000000000"
        val cache = restart()
        assertFalse(cache.has(a, b, act, 2))
        assertTrue(cache.has(b, c, act, 2))
        // the stand-in: the stored line at once, the stale leg included
        val stand = StoredLine.preview(listOf(a, b, c), act, 2, emptySet(), cache, emptyList())
        assertNotNull(stand)
        assertEquals(1, stand!!.stale)
        assertEquals(0, stand.fromLine)
        assertEquals(first.points, stand.route.points)
        // then the run routes only the stale leg
        plan(e, cache, a, b, c)
        assertEquals(legs(a, b), e.calls)
    }

    @Test fun aRoutingPartLoadedSinceMakesTheLegsNearItStale() {
        val part = Rd5Parts.partOf(p[6].lat, p[6].lon).key
        versions[part] = "-"
        val e = Engine()
        plan(e, restart(), p[4], p[6])
        e.calls.clear()
        versions[part] = "1730000000000"
        plan(e, restart(), p[4], p[6])
        assertEquals(legs(p[4], p[6]), e.calls)
    }

    @Test fun anotherRoutingFingerprintOrNoneRoutesAgain() {
        val e = Engine()
        plan(e, restart(), p[0], p[2])
        e.calls.clear()
        fp = "r1-ffffffffffff" // a new app: other profiles / planner
        plan(e, restart(), p[0], p[2])
        assertEquals(1, e.calls.size)
        e.calls.clear()
        fp = null
        plan(e, restart(), p[0], p[2])
        assertEquals(1, e.calls.size)
    }

    @Test fun anUnreadableDataVersionIsNeverFresh() {
        versions[Rd5Parts.partOf(p[0].lat, p[0].lon).key] = "?"
        val e = Engine()
        plan(e, restart(), p[0], p[2])
        e.calls.clear()
        plan(e, restart(), p[0], p[2])
        assertEquals(1, e.calls.size)
    }

    @Test fun aDamagedLegFileIsDeletedAndTheLegRouted() {
        val e = Engine()
        plan(e, restart(), p[0], p[2])
        val f = tmp.root.resolve("legs").listFiles()!!.single { it.name.endsWith(LegFiles.SUFFIX) }
        f.writeText(f.readText().take(300))
        e.calls.clear()
        plan(e, restart(), p[0], p[2])
        assertEquals(1, e.calls.size)
        assertTrue(f.isFile) // written again by the new run
        assertTrue(files().get(LegCache.key(p[0], p[2], act, 2))!!.fresh)
    }

    @Test fun theStoreKeepsItsLimitByDroppingTheLeastUsed() {
        val e = Engine()
        val cache = LegCache(store = files(maxFiles = 4))
        plan(e, cache, p[0], p[1], p[2], p[3], p[4], p[5], p[6])
        val n = tmp.root.resolve("legs").listFiles()!!.count { it.name.endsWith(LegFiles.SUFFIX) }
        assertTrue("$n legs", n in 1..4)
        // the last leg routed is kept
        assertNotNull(files().get(LegCache.key(p[5], p[6], act, 2)))
    }

    @Test fun theTextFormReadsBackExactly() {
        val leg = Engine().route(listOf(p[0], p[1]), act, 2)
        val key = LegCache.key(p[0], p[1], act, 2)
        val back = LegFiles.decode(LegFiles.encode(LegFiles.Entry(key, "r1-0123456789ab", "x=1", 5L, leg)))
        assertEquals(leg, back.leg)
        assertEquals(leg.points.map { it.shape }, back.leg.points.map { it.shape })
        assertEquals(key, back.key)
        assertNull(files().get(LegCache.key(p[2], p[3], act, 2)))
    }
}

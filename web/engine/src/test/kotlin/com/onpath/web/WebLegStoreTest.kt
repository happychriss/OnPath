package com.onpath.web

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.Share
import com.onpath.app.routing.WayAttrs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The planner keeps finished legs in the browser (owner 2026-11-18, München–Venedig opened again routed every leg for
 * minutes): [WebLegStore] behind the engine's LegCache. A stored leg gives the same plan bit for bit, is used only with
 * the same routing fingerprint and routing data (the phone's LegFiles rule), and a reopened plan routes none of its legs.
 */
class WebLegStoreTest {
    private val a = GeoPoint(46.6712345, 11.1612345)
    private val b = GeoPoint(46.6812345, 11.1712345)

    private fun leg(): RouteResult {
        val w1 = WayAttrs("path", "gravel", 2, -1, null, null, null, null, false, true)
        val w2 = WayAttrs("track", "unpaved", 0, 1, "bad", null, null, "yes", true, false)
        val pts = listOf(
            GeoPoint(46.671234567891, 11.161234567891, 312.25, w1),
            GeoPoint(46.6751, 11.1655, null, w1, JunctionCue.LEFT, 2, -87.12345f, JunctionShape(12.5f, 3, 4, listOf(JunctionShape.Branch(-91.25f, 1)))),
            GeoPoint(46.681234, 11.171234, 1000.0 / 3.0, w2, JunctionCue.KEEP_RIGHT, 0, 0.1f, JunctionShape(-0.0f, 0, 1, emptyList())),
        )
        return RouteResult(pts, 1234.5678912, 0.1 + 0.2, 77.0, 456.789, listOf(Share("path", 1000.0 / 7.0), Share("track", 3.0)),
            listOf(Share("gravel", 1234.5678912)), listOf(GeoPoint(46.6712, 11.1612, 300.5), null))
    }

    private fun same(x: RouteResult, y: RouteResult) {
        assertEquals(x, y) // the numbers and the points' positions + heights
        assertEquals(x.points.map { listOf(it.way, it.turn, it.exit, it.turnAngle.toRawBits(), it.shape) },
            y.points.map { listOf(it.way, it.turn, it.exit, it.turnAngle.toRawBits(), it.shape) })
        assertEquals(x.points.map { it.lat.toRawBits() to it.ele?.toRawBits() }, y.points.map { it.lat.toRawBits() to it.ele?.toRawBits() })
    }

    @Test fun aLegReadsBackBitForBit() {
        val k = LegCache.key(a, b, Activity.HIKE, 3)
        val e = WebLegStore.Entry(k, "r1-0123456789ab", "E10_N45.rd5:E11_N46=1", leg())
        val back = WebLegStore.decode(WebLegStore.encode(e))
        assertEquals(e.key, back.key); assertEquals(e.fingerprint, back.fingerprint); assertEquals(e.data, back.data)
        same(e.leg, back.leg)
    }

    @Test fun aStoredLegCountsOnlyWithTheSameFingerprintAndData() {
        var fp = "r1-0123456789ab"; var version = "100"; var missing = false
        val s = WebLegStore({ fp }, { version }, { missing })
        val k = LegCache.key(a, b, Activity.HIKE, 3)
        s.put(k, a, b, leg())
        val name = WebLegStore.name(k)
        assertEquals(listOf(name), s.taken())
        val text = s.text(name)
        assertTrue(text.contains("E11_N46=100"))
        // a fresh page: the worker hands it over
        assertEquals(listOf(name), s.wanted(listOf(k)))
        s.give(name, text)
        val got = s.get(k)!!
        assertTrue(got.fresh); same(leg(), got.leg); assertEquals(1, s.hits)
        // a cache of the same points for other legs: other activity, other T level – another key, nothing found
        assertNull(s.get(LegCache.key(a, b, Activity.HIKE, 4)))
        assertNull(s.get(LegCache.key(a, b, Activity.BIKE_TOURING, 3)))
        // newer routing data under the leg: stale (shown nowhere, routed again)
        version = "200"; assertFalse(s.get(k)!!.fresh)
        version = "100"; assertTrue(s.get(k)!!.fresh)
        // other profiles / BRouter / planner version: stale
        fp = "r1-ba9876543210"; assertFalse(s.get(k)!!.fresh)
        fp = ""; assertFalse(s.get(k)!!.fresh)
        fp = "r1-0123456789ab"
        // a leg routed while a part under it was missing is not kept
        missing = true; s.put(LegCache.key(b, a, Activity.HIKE, 3), b, a, leg())
        assertEquals(emptyList<String>(), s.taken())
        // garbage handed over: no leg, no throw
        s.wanted(listOf(k)); s.give(name, "nonsense")
        assertNull(s.get(k))
        assertEquals(1, Rd5Parts.forCorridor(listOf(a, b), WebLegStore.MARGIN_M).size)
    }

    // ---- a real plan, opened again on a new page ----
    private val repo = File("../..").canonicalFile
    private val root = Files.createTempDirectory("weblegs").toFile()
    private val segments = File(System.getenv("ONPATH_SEGMENTS") ?: (System.getProperty("user.home") + "/Projects/work/onpath-data/segments"))
    private val hoehenweg = "AQUDDPiLugSE8YcBogbZH94K8yedLO898Q7VHK0ZuUWwL99M6GK6N7AHsFyWPLJgDOyUAauSAdlUAAABEU1lcmFuZXIgSMO2aGVud2VnAhEAR2FzdGhvZiBIb2NobXV0aAIKAUxlaXRlcmFsbQINAkhvY2hnYW5naGF1cwILA0dpZ2dlbGJlcmcCCgRIb2NoZm9yY2gCCwVVbnRlcnN0ZWxsAg4GS2F0aGFyaW5hYmVyZwIHB0Vpc2hvZgIRCFN0ZXR0aW5lciBIw7x0dGUCCQlQZmVsZGVycwIGClVsZmFzAhELR2FzdGhvZiBIb2NobXV0aA"

    @After fun tidy() { root.deleteRecursively() }

    /** The worker's part: what the engine stored goes to the "browser" ([kept]); a new page hands back what it wants. */
    private val kept = HashMap<String, String>()
    private fun keep() { for (n in Engine.legStore.taken()) kept[n] = Engine.legStore.text(n) }
    private fun handOver(pts: String, act: String, sac: Int): Int {
        val names = Regex("\"([^\"]+)\"").findAll(Engine.legsWanted(pts, act, sac)).map { it.groupValues[1] }.toList()
        names.forEach { n -> kept[n]?.let { Engine.legStore.give(n, it) } }
        return names.size
    }
    private fun noMs(json: String) = json.replace(Regex("\"ms\":\\d+"), "\"ms\":0")
    private fun legs() = Engine.lastLegs

    @Test fun aReopenedPlanRoutesNoLegAndIsTheSamePlan() {
        assumeTrue("no E10_N45.rd5 in $segments", File(segments, "E10_N45.rd5").isFile)
        Engine.init(root.path)
        File(root, "segments").mkdirs()
        Files.createSymbolicLink(File(root, "segments/E10_N45.rd5").toPath(), File(segments, "E10_N45.rd5").toPath())
        repo.resolve("app/src/main/assets/brouter").copyRecursively(File(root, "assets/brouter"))
        val plan = PlanCode.decode(hoehenweg)!!
        val pts = plan.points.joinToString(";") { "${it.lat},${it.lon}" }
        val n = plan.points.size - 1
        Engine.legStore.taken() // nothing from other tests
        assertEquals(n, handOver(pts, plan.activity.name, plan.sac))

        var t0 = System.currentTimeMillis()
        val first = Engine.plan(pts, plan.activity.name, plan.sac)
        val firstMs = System.currentTimeMillis() - t0
        assertEquals("{\"routed\":$n,\"cached\":0,\"stored\":0}", legs())
        keep()
        assertEquals(n, kept.size)

        // a reload: a new engine (empty memory), the legs from the browser
        Engine.init(root.path)
        assertEquals(n, handOver(pts, plan.activity.name, plan.sac))
        t0 = System.currentTimeMillis()
        val again = Engine.plan(pts, plan.activity.name, plan.sac)
        val againMs = System.currentTimeMillis() - t0
        println("Höhenweg $n legs: routed ${firstMs} ms, from the stored legs ${againMs} ms")
        assertEquals("{\"routed\":0,\"cached\":$n,\"stored\":$n}", legs())
        assertEquals("the same plan, bit for bit", noMs(first), noMs(again))

        // the same page again: from memory, nothing to read
        assertEquals(0, handOver(pts, plan.activity.name, plan.sac))

        // other profiles (a new routing fingerprint): every leg routed again, the same line
        val brf = File(root, "assets/brouter/hiking-mountain.brf")
        brf.appendText("\n# a changed profile\n")
        Engine.init(root.path)
        assertEquals(n, handOver(pts, plan.activity.name, plan.sac))
        val third = Engine.plan(pts, plan.activity.name, plan.sac)
        assertEquals("{\"routed\":$n,\"cached\":0,\"stored\":0}", legs())
        assertEquals(noMs(first), noMs(third))
        assertNotNull(third)
    }
}

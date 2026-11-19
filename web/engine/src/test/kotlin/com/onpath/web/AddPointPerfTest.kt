package com.onpath.web

import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.BRouterEngine
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.PlanResult
import com.onpath.app.routing.Planner
import com.onpath.app.routing.RouteLimits
import com.onpath.app.routing.RouteResult
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files

/**
 * Measurement only (no checks): where the time of one „add a point" goes in a long multi-day plan (München → Brenner →
 * Venedig, Hike T2 and Bike), how BRouter's time scales with the leg length, and what a „detour splice" (route only
 * anchor → new point → anchor) would cost and change. Off by default; run with
 *   ONPATH_PERF=1 tools/dev/gradle_slot.sh -- ./gradlew -p web/engine test --tests com.onpath.web.AddPointPerfTest -i
 * (needs E10_N45.rd5 in ONPATH_SEGMENTS or ~/Projects/work/onpath-data/segments). PERF_ACTS=HIKE,BIKE_TOURING,
 * PERF_REPS=3, PERF_PARTS=add,scale,splice pick what runs. Prints lines starting with "PERF".
 */
class AddPointPerfTest {
    private val repo = File("../..").canonicalFile
    private val root = Files.createTempDirectory("webperf").toFile()
    private val segments = File(System.getenv("ONPATH_SEGMENTS") ?: (System.getProperty("user.home") + "/Projects/work/onpath-data/segments"))
    private val mx = ManagementFactory.getThreadMXBean()
    private val reps = (System.getenv("PERF_REPS") ?: "3").toInt()
    private val parts = (System.getenv("PERF_PARTS") ?: "add,scale,splice").split(',').toSet()
    private val acts = (System.getenv("PERF_ACTS") ?: "BIKE_TOURING,HIKE").split(',').map { Activity.valueOf(it) }

    private val base = listOf(GeoPoint(48.1374, 11.5755), GeoPoint(47.0030, 11.5060), GeoPoint(45.4380, 12.3190))

    @After fun tidy() { root.deleteRecursively() }

    private class T(val wallMs: Double, val cpuMs: Double)
    private fun <R> timed(f: () -> R): Pair<R, T> {
        val c0 = mx.currentThreadCpuTime; val w0 = System.nanoTime()
        val r = f()
        return r to T((System.nanoTime() - w0) / 1e6, (mx.currentThreadCpuTime - c0) / 1e6)
    }
    private fun f(t: T) = "%.0f/%.0f".format(t.wallMs, t.cpuMs)
    private fun log(s: String) = println("PERF $s")
    private fun pts(l: List<GeoPoint>) = l.joinToString(";") { "%.6f,%.6f".format(java.util.Locale.ROOT, it.lat, it.lon) }
    private fun sacOf(a: Activity) = if (a == Activity.HIKE) 2 else 0

    /** A fresh router (its own small result cache empty). */
    private fun router() = BRouterEngine(WebContext(File(root, "files")), File(root, "segments"))
    private val long = RouteLimits(600_000L)

    private fun leg(e: BRouterEngine, a: GeoPoint, b: GeoPoint, act: Activity): Pair<RouteResult?, T> =
        timed { try { e.route(listOf(a, b), act, sacOf(act), long) } catch (x: Exception) { log("  route failed: $x"); null } }

    /** BRouter itself (as BRouterEngine sets it up) for the nodes it touched and its two passes' coefficients. */
    private fun raw(a: GeoPoint, b: GeoPoint, act: Activity): String {
        val rc = btools.router.RoutingContext()
        rc.localFunction = File(root, "files/brouter/profiles/" + com.onpath.app.routing.RouterRules.profileFile(act)).absolutePath
        rc.keyValues = hashMapOf("turnInstructionMode" to "4", "correctMisplacedViaPoints" to "1",
            "correctMisplacedViaPointsDistance" to com.onpath.app.routing.RouterRules.viaCorrectionM(act).toInt().toString(),
            "processUnusedTags" to "1", "use_dynamic_range" to "0", "waypointCatchingRange" to "1000").apply {
            if (act == Activity.HIKE) { put("SAC_scale_limit", "2"); put("SAC_scale_preferred", "2") }
        }
        val nodes = listOf(a, b).mapIndexed { i, p -> btools.router.OsmNodeNamed().apply {
            name = if (i == 0) "from" else "to"
            ilon = ((p.lon + 180.0) * 1e6 + 0.5).toInt(); ilat = ((p.lat + 90.0) * 1e6 + 0.5).toInt() } }
        val e = btools.router.RoutingEngine(null, null, File(root, "segments"), nodes, rc)
        e.quite = true
        val (_, t) = timed { e.doRun(600_000L) }
        fun field(o: Any, n: String): Any? {
            var c: Class<*>? = o.javaClass
            while (c != null) { try { return c.getDeclaredField(n).also { it.isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass } }
            return "?"
        }
        return "raw ${f(t)} ms, links ${field(e, "linksProcessed")}, pass1 ${field(rc, "pass1coefficient")}, pass2 ${field(rc, "pass2coefficient")}" +
            (e.errorMessage?.let { ", error $it" } ?: ", %.1f km".format((e.foundTrack?.distance ?: 0) / 1000.0))
    }

    /** The point [m] metres along [line]. */
    private fun along(line: List<GeoPoint>, m: Double): Pair<GeoPoint, Int> {
        var s = 0.0
        for (i in 1 until line.size) {
            val d = Geo.distance(line[i - 1], line[i])
            if (s + d >= m) {
                val t = if (d > 0) (m - s) / d else 0.0
                return GeoPoint(line[i - 1].lat + t * (line[i].lat - line[i - 1].lat), line[i - 1].lon + t * (line[i].lon - line[i - 1].lon)) to i
            }
            s += d
        }
        return line.last() to line.lastIndex
    }

    /** [p] moved [m] metres to the left of the line's direction at index [i]. */
    private fun off(line: List<GeoPoint>, i: Int, p: GeoPoint, m: Double): GeoPoint {
        val a = line[maxOf(0, i - 20)]; val b = line[minOf(line.lastIndex, i + 20)]
        val k = Math.cos(Math.toRadians(p.lat))
        val dx = (b.lon - a.lon) * k; val dy = b.lat - a.lat
        val n = Math.hypot(dx, dy)
        val nx = -dy / n; val ny = dx / n // left normal (east = x, north = y)
        return GeoPoint(p.lat + ny * m / 111_320.0, p.lon + nx * m / (111_320.0 * k))
    }

    private fun km(r: RouteResult?) = r?.distanceM?.div(1000) ?: Double.NaN
    private fun up(line: List<GeoPoint>) = Planner.climb(line).first

    private fun setup() {
        assumeTrue("ONPATH_PERF=1 to run", System.getenv("ONPATH_PERF") == "1")
        assumeTrue("no E10_N45.rd5 in $segments", File(segments, "E10_N45.rd5").isFile)
        Engine.init(root.path)
        File(root, "segments").mkdirs()
        Files.createSymbolicLink(File(root, "segments/E10_N45.rd5").toPath(), File(segments, "E10_N45.rd5").toPath())
        repo.resolve("app/src/main/assets/brouter").copyRecursively(File(root, "assets/brouter"))
        log("load " + File("/proc/loadavg").readText().trim() + ", cores " + Runtime.getRuntime().availableProcessors() +
            ", heap max " + Runtime.getRuntime().maxMemory() / 1_000_000 + " MB")
    }

    @Test fun measure() {
        setup()
        for (act in acts) {
            val sac = sacOf(act)
            // the base plan (3 points), cold: every leg routed
            Engine.init(root.path)
            val (j0, t0) = timed { Engine.plan(pts(base), act.name, sac, rounds = 1) }
            val line = Engine.lastPlanPoints(base, act, sac) ?: run { log("$act base plan failed: ${j0.take(300)}"); continue }
            val legs = Engine.lastPlanFor(base)!!.legs
            log("$act base plan 3 points: ${f(t0)} ms wall/cpu, %.1f km, legs %s km".format(Geo.length(line) / 1000,
                legs.joinToString(" + ") { "%.0f".format(Geo.length(it.points) / 1000) }))
            val leg2 = legs[1].points
            val leg2Len = Geo.length(leg2)

            if ("add" in parts) for (rep in 0 until reps) {
                // a point 2 km off the line at the middle of the long leg (a little further along each rep: no cache hit)
                val (mid, mi) = along(leg2, leg2Len / 2 + rep * 3_000.0)
                val q = off(leg2, mi, mid, 2_000.0)
                val wps = listOf(base[0], base[1], q, base[2])
                Engine.init(root.path)
                Engine.plan(pts(base), act.name, sac, rounds = 1) // the base again (its legs in memory, as on the page)
                val (_, tAll) = timed { Engine.plan(pts(wps), act.name, sac, rounds = 1) }
                val legsLast = Engine.lastLegs
                // the same pieces one by one: the two new legs on a fresh router, then the rest with all legs cached
                val e = router()
                val (r1, tl1) = leg(e, base[1], q, act)
                val (r2, tl2) = leg(e, q, base[2], act)
                val cache = LegCache()
                cache.put(base[0], base[1], act, sac, Engine.engine().route(listOf(base[0], base[1]), act, sac, long))
                r1?.let { cache.put(base[1], q, act, sac, it) }; r2?.let { cache.put(q, base[2], act, sac, it) }
                val planner = Planner(e, legs = cache)
                val (planned, tJoin) = timed { planner.plan(wps, act, sac) }
                val (res, tRes) = timed { PlanResult.of(wps, planned, act, sac, emptySet(), emptySet(), WebPace.factors(act)) }
                val (_, tProf) = timed { res.rating; res.profile }
                val (_, tJson) = timed { Engine.planJson(res, emptyMap(), 0) }
                val (_, tParts) = timed { Engine.partsNeeded(pts(wps), act.name) }
                val (_, tDays) = timed { WebDays.view(pts(wps), act.name, sac, "", "", "d\t6") }
                val (_, tKey) = timed { Engine.legsWanted(pts(wps), act.name, sac) }
                log("$act add rep$rep: Engine.plan ${f(tAll)} ms, legs $legsLast | leg A ${f(tl1)} ms %.1f km | leg B ${f(tl2)} ms %.1f km | join+lineClimb ${f(tJoin)} | PlanResult.of ${f(tRes)} | rating+profile ${f(tProf)} | planJson ${f(tJson)} | partsNeeded ${f(tParts)} | WebDays.view(6 d) ${f(tDays)} | legsWanted ${f(tKey)}"
                    .format(km(r1), km(r2)))
                if (rep == 0) log("$act   raw leg A: ${raw(base[1], q, act)} | raw leg B: ${raw(q, base[2], act)}")
            }

            if ("scale" in parts) {
                // the same line cut into 25 / 50 / 100 / 200 km pieces from the leg's start (one fresh router each)
                for (piece in listOf(25_000.0, 50_000.0, 100_000.0, 200_000.0)) {
                    if (piece > leg2Len) continue
                    val a = leg2.first(); val b = along(leg2, piece).first
                    val times = (0 until reps).map { leg(router(), a, b, act) }
                    log("$act scale %3.0f km piece: routed %.1f km, wall %s ms, cpu %s ms | %s".format(piece / 1000, km(times[0].first),
                        times.joinToString("/") { "%.0f".format(it.second.wallMs) }, times.joinToString("/") { "%.0f".format(it.second.cpuMs) },
                        raw(a, b, act)))
                }
            }

            if ("splice" in parts) {
                // samples at 30 / 50 / 70 % of the long leg, 0.5 / 2 / 5 / 10 km off; anchors 3 / 5 / 10 km before / after
                for (frac in listOf(0.3, 0.5, 0.7)) for (offM in listOf(500.0, 2_000.0, 5_000.0, 10_000.0)) {
                    val s = leg2Len * frac
                    val (p, pi) = along(leg2, s)
                    val q = off(leg2, pi, p, offM)
                    val e = router()
                    val (f1, tf1) = leg(e, base[1], q, act)
                    val (f2, tf2) = leg(e, q, base[2], act)
                    if (f1 == null || f2 == null) { log("$act splice ${frac} ${offM}: full routing failed"); continue }
                    val fullKm = Geo.length(f1.points + f2.points) / 1000; val fullUp = up(f1.points + f2.points)
                    val row = StringBuilder("$act splice at %.0f %% (%.0f km), %.1f km off: full 2 legs %.0f ms cpu %.0f, %.1f km ↑%.0f".format(
                        frac * 100, s / 1000, offM / 1000, tf1.wallMs + tf2.wallMs, tf1.cpuMs + tf2.cpuMs, fullKm, fullUp))
                    for (anchorM in listOf(3_000.0, 5_000.0, 10_000.0)) {
                        val (a, ai) = along(leg2, s - anchorM); val (b, bi) = along(leg2, s + anchorM)
                        val e2 = router()
                        val (s1, ts1) = leg(e2, a, q, act); val (s2, ts2) = leg(e2, q, b, act)
                        if (s1 == null || s2 == null) { row.append(" | a${(anchorM / 1000).toInt()}: failed"); continue }
                        val before = leg2.subList(0, ai) + a; val after = listOf(b) + leg2.subList(bi, leg2.size)
                        val splicedLine = before + s1.points + s2.points + after
                        val splicedKm = Geo.length(splicedLine) / 1000
                        row.append(" | a%d: %.0f ms cpu %.0f, %.1f km (%+.1f) ↑%.0f (%+.0f)".format((anchorM / 1000).toInt(),
                            ts1.wallMs + ts2.wallMs, ts1.cpuMs + ts2.cpuMs, splicedKm, splicedKm - fullKm, up(splicedLine), up(splicedLine) - fullUp))
                    }
                    log(row.toString())
                }
            }
        }
    }
}

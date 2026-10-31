import com.onpath.app.navigation.NavLine
import com.onpath.app.navigation.NavStop
import com.onpath.app.navigation.Rerouter
import com.onpath.app.routing.*
import java.io.File
import kotlin.math.*

// Re-routing while navigating with real BRouter data: pass/fail checks for navigation/Rerouter (docs/plan-auto-reroute.md
// §9.2). Position = a point on the planned route at 30 % / 60 %, moved 100 m / 250 m to the right.
// Checks: a way back is found; new remaining length ≤ "straight to the destination" + 10 % (+ 300 m); ≤ 12 s; the
// destination is kept; a T1 way back never uses a way above T1; an unreachable target and cancel stop within the
// budget; no data fails fast. Needs only E10_N45.rd5; the unreachable and no-data checks use places that no way / no
// tile covers, so more tiles in the segments folder change nothing.
// Run: KTTEST=$PWD/tools/routing/jvm-test/RerouterJvmTest.kt tools/routing/jvm-test/run.sh <segmentsDir>

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
    val y = sin(Math.toRadians(b.lon - a.lon)) * cos(Math.toRadians(b.lat))
    val x = cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) -
        sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(Math.toRadians(b.lon - a.lon))
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

fun moved(p: GeoPoint, deg: Double, m: Double): GeoPoint {
    val dLat = m * cos(Math.toRadians(deg)) / 111_320.0
    val dLon = m * sin(Math.toRadians(deg)) / (111_320.0 * cos(Math.toRadians(p.lat)))
    return GeoPoint(p.lat + dLat, p.lon + dLon)
}

var failures = 0
fun check(ok: Boolean, what: String) { println((if (ok) "   ok   " else "   FAIL ") + what); if (!ok) failures++ }

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val rr = Rerouter(eng)

    data class Case(val name: String, val wps: List<GeoPoint>, val act: Activity)
    val cases = listOf(
        Case("Hike Algund->Schenna", listOf(GeoPoint(46.6830, 11.1300), GeoPoint(46.6890, 11.1870)), Activity.HIKE),
        Case("Hike Meran2000->Ifinger hut", listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.6995, 11.2430)), Activity.HIKE),
        Case("Trekking Bozen->Meran", listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500)), Activity.BIKE_TREKKING),
        Case("MTB Algund->Vellau", listOf(GeoPoint(46.6830, 11.1300), GeoPoint(46.6860, 11.1090)), Activity.MTB),
        Case("Trekking Bozen->Mals (long)", listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6880, 10.5470)), Activity.BIKE_TREKKING),
    )
    val times = mutableListOf<Long>()
    for (c in cases) {
        val base = eng.route(c.wps, c.act, 3)
        val line = NavLine(base.points)
        val stops = line.alongsInOrder(c.wps).mapIndexed { i, a -> NavStop(c.wps[i], a) }
        println("${c.name} (${(line.lengthM / 1000).roundToInt()} km)")
        for (frac in listOf(0.3, 0.6)) for (off in listOf(100.0, 250.0)) {
            val along = line.lengthM * frac
            val a = line.pointAt(along - 20); val b = line.pointAt(along + 20)
            val pos = moved(line.pointAt(along), bearingDeg(a, b) + 90, off)
            val out = rr.reroute(Rerouter.Request(pos, line, along, stops, emptySet(), c.act, 3))
            val dest = try { eng.route(listOf(pos, c.wps.last()), c.act, 3).distanceM } catch (e: RoutingException) { null }
            val tag = "at ${(frac * 100).roundToInt()}pct, ${off.roundToInt()} m off"
            if (out !is Rerouter.Found) { check(false, "$tag: ${(out as Rerouter.Failed).reason} ${out.detail}"); continue }
            times += out.tookMs
            val newRemaining = out.legM + (line.lengthM - out.resumeAlongM)
            val limit = dest?.let { it * 1.10 + 300 }
            check(limit == null || newRemaining <= limit,
                "$tag: %d ms, %d tried, leg %.0f m, rejoins in %.0f m, extra %+.0f m, new %.0f m vs to destination %s m".format(
                    out.tookMs, out.tried, out.legM, out.rejoinInM, out.extraM, newRemaining, dest?.roundToInt() ?: "-"))
            check(out.tookMs <= Rerouter.TOTAL_MS + 500, "$tag: within the 12 s budget")
            check(out.line.last() == line.points.last(), "$tag: destination kept")
        }
    }
    println("\nway-back times: median %d ms, max %d ms".format(times.sorted()[times.size / 2], times.max()))

    fun short(o: Rerouter.Outcome) = when (o) {
        is Rerouter.Found -> "Found(leg ${o.legM.roundToInt()} m, ${o.tookMs} ms)"
        is Rerouter.Failed -> "Failed(${o.reason}, ${o.tookMs} ms: ${o.detail})"
    }
    // The way back of a Found: the first points of its line, up to legM (RerouteTargets.splice appends the original rest).
    fun legOf(f: Rerouter.Found): List<GeoPoint> {
        var m = 0.0
        val out = arrayListOf(f.line.first())
        for (k in 1 until f.line.size) {
            m += com.onpath.app.data.Geo.distance(f.line[k - 1], f.line[k])
            if (m > f.legM + 0.5) break
            out += f.line[k]
        }
        return out
    }

    // The T limit holds: the Ifinger summit route planned with T5, the user re-routes with a T1 limit. Whether a way
    // back is found depends on the map data (BRouter snaps a target to a T1 way up to 1000 m away; with the 2026
    // data one lies ~840 m below the summit), so the check is: within the budget, and a way back never uses a way
    // above T1.
    println("\nT limit (hike T1 back to the Ifinger summit route planned with T5):")
    val summit = eng.route(listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.7006, 11.2585)), Activity.HIKE, 5)
    val sl = NavLine(summit.points)
    val p0 = GeoPoint(46.6870, 11.2350)
    val t0 = System.currentTimeMillis()
    val u = rr.reroute(Rerouter.Request(p0, sl, sl.lengthM - 300.0, emptyList(), emptySet(), Activity.HIKE, 1))
    val tu = System.currentTimeMillis() - t0
    val legSac = (u as? Rerouter.Found)?.let { f -> legOf(f).mapNotNull { it.way?.sac }.maxOrNull() ?: 0 }
    check(tu <= Rerouter.TOTAL_MS + 1500 && (legSac == null || legSac <= 1),
        "T1 limit: ${short(u)} after $tu ms (budget 12 s), highest SAC on the way back: ${legSac?.let { "T$it" } ?: "-"}")

    // Unreachable: every rejoin target lies in the Adriatic, ~25 km off the coast and far from the Venice – Istria
    // ferry lines (no way of any profile within BRouter's 1000 m catching range, in any OSM data; still tile
    // E10_N45). The user stands on Lido di Venezia → no way back, a clean failure within the budget.
    println("\nunreachable (rejoin targets out in the Adriatic):")
    val lido = GeoPoint(45.4140, 12.3680)
    val sea = NavLine(listOf(lido, GeoPoint(45.05, 12.75)))
    val t3 = System.currentTimeMillis()
    val un = rr.reroute(Rerouter.Request(lido, sea, sea.lengthM - 300.0, emptyList(), emptySet(), Activity.HIKE, 1))
    val tun = System.currentTimeMillis() - t3
    check(un is Rerouter.Failed && un.reason == Rerouter.Reason.NO_WAY && tun <= Rerouter.TOTAL_MS + 1500,
        "unreachable: ${short(un)} after $tun ms (budget 12 s)")

    // Cancel: a request that cannot finish fast – a fresh MTB route Bozen → Munich (~260 km; > 8 s on a PC, so the
    // request would time out) – cancelled after 300 ms stops at once with CANCELLED.
    println("\ncancel (MTB Bozen -> Munich, cancelled after 300 ms):")
    val bz = GeoPoint(46.4970, 11.3580); val muc = GeoPoint(48.1370, 11.5750)
    val longLine = NavLine(listOf(bz, muc))
    val longStops = longLine.alongsInOrder(listOf(bz, muc)).mapIndexed { i, a -> NavStop(listOf(bz, muc)[i], a) }
    val cancel = RouteCancel()
    Thread { Thread.sleep(300); cancel.cancel() }.start()
    val t1 = System.currentTimeMillis()
    val cu = rr.reroute(Rerouter.Request(moved(bz, 0.0, 30.0), longLine, 0.0, longStops, emptySet(), Activity.MTB, 3), cancel)
    val tc = System.currentTimeMillis() - t1
    check(cu is Rerouter.Failed && cu.reason == Rerouter.Reason.CANCELLED && tc < 2_000, "cancel: ${short(cu)} after $tc ms")

    // Outside the routing data: mid North Atlantic (tile W45_N35 – open ocean, BRouter has no such file) → NO_DATA, fast.
    val far = NavLine(listOf(GeoPoint(37.5, -42.5), GeoPoint(37.51, -42.5)))
    val t2 = System.currentTimeMillis()
    val nd = rr.reroute(Rerouter.Request(GeoPoint(37.5, -42.498), far, 0.0, emptyList(), emptySet(), Activity.HIKE, 3))
    val tnd = System.currentTimeMillis() - t2
    check(nd is Rerouter.Failed && nd.reason == Rerouter.Reason.NO_DATA && tnd < 1_000, "no data: ${short(nd)} after $tnd ms")

    println(if (failures == 0) "\nALL CHECKS PASSED" else "\n$failures CHECK(S) FAILED")
    if (failures > 0) System.exit(1)
}

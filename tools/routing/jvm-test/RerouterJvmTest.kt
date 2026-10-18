import com.onpath.app.navigation.NavLine
import com.onpath.app.navigation.NavStop
import com.onpath.app.navigation.Rerouter
import com.onpath.app.routing.*
import java.io.File
import kotlin.math.*

// Re-routing while navigating with real BRouter data: pass/fail checks for navigation/Rerouter (docs/plan-auto-reroute.md
// §9.2). Position = a point on the planned route at 30 % / 60 %, moved 100 m / 250 m to the right.
// Checks: a way back is found; new remaining length ≤ "straight to the destination" + 10 % (+ 300 m); ≤ 12 s; the
// destination is kept; unreachable target and cancel stop within the budget; no data fails fast.
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

    // Unreachable: Ifinger summit route planned with T5, user re-routes with a T1 limit → nothing found within budget.
    println("\nunreachable (hike T1 to the Ifinger summit):")
    val summit = eng.route(listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.7006, 11.2585)), Activity.HIKE, 5)
    val sl = NavLine(summit.points)
    // user back at Meran 2000 with progress near the summit: the only candidate is the summit (not reachable with T1)
    val at = sl.lengthM - 300.0
    val p0 = GeoPoint(46.6870, 11.2350)
    fun short(o: Rerouter.Outcome) = when (o) {
        is Rerouter.Found -> "Found(leg ${o.legM.roundToInt()} m, ${o.tookMs} ms)"
        is Rerouter.Failed -> "Failed(${o.reason}, ${o.tookMs} ms: ${o.detail})"
    }
    val t0 = System.currentTimeMillis()
    val u = rr.reroute(Rerouter.Request(p0, sl, at, emptyList(), emptySet(), Activity.HIKE, 1))
    val tu = System.currentTimeMillis() - t0
    check(u is Rerouter.Failed && tu <= Rerouter.TOTAL_MS + 1500, "unreachable: ${short(u)} after $tu ms (budget 12 s)")

    // Cancel: same unreachable request, cancelled after 300 ms.
    val cancel = RouteCancel()
    Thread { Thread.sleep(300); cancel.cancel() }.start()
    val t1 = System.currentTimeMillis()
    val cu = rr.reroute(Rerouter.Request(moved(p0, 0.0, 30.0), sl, at, emptyList(), emptySet(), Activity.HIKE, 1), cancel)
    val tc = System.currentTimeMillis() - t1
    check(cu is Rerouter.Failed && tc < 2_000, "cancel: ${short(cu)} after $tc ms")

    // Outside the routing data (no rd5 for lat 51): fails fast with NO_DATA.
    val far = NavLine(listOf(GeoPoint(51.0, 11.0), GeoPoint(51.01, 11.0)))
    val t2 = System.currentTimeMillis()
    val nd = rr.reroute(Rerouter.Request(GeoPoint(51.0, 11.002), far, 0.0, emptyList(), emptySet(), Activity.HIKE, 3))
    check(nd is Rerouter.Failed && nd.reason == Rerouter.Reason.NO_DATA && System.currentTimeMillis() - t2 < 1_000, "no data: $nd")

    println(if (failures == 0) "\nALL CHECKS PASSED" else "\n$failures CHECK(S) FAILED")
    if (failures > 0) System.exit(1)
}

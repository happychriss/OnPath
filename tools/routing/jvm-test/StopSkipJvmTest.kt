import com.onpath.app.navigation.NavLine
import com.onpath.app.navigation.NavStop
import com.onpath.app.navigation.RerouteTargets
import com.onpath.app.navigation.Rerouter
import com.onpath.app.navigation.StopSkip
import com.onpath.app.routing.*
import com.onpath.app.time.RouteTimes
import java.io.File
import kotlin.math.*

// Re-routing and the next stop with real BRouter data (owner 2026-10-30, docs/plan-auto-reroute.md §5.4): when one is
// off the plan, does the new route go via the next route point, leave it out, or ask "Skip stop N?" (navigation/StopSkip).
// Cases in Hilden by bike (E5_N50.rd5), like the owner's ride of 2026-10-28 (left the plan at km 0.5–0.8 and 6.0–6.8, up
// to 380 m off – the trace itself is not in the repo, so the excursions are rebuilt on a Hilden round ride):
//  1. excursions on a parallel street at km 0.5–0.8 and 6.0–6.8, 380 m to either side, asked when the off-route is
//     confirmed (~60 m off) and at the farthest point;
//  2. "the next stop now lies aside": a plan dragged over a route point beside the direct road, the rider on that road;
//     the same point pinned (POI) / named → never left out without asking;
//  3. round trip: just after the start the end is next to you → never "skip the loop".
// "Before" = the route the app gave before the change (always via every stop not passed).
// Run: KTTEST=$PWD/tools/routing/jvm-test/StopSkipJvmTest.kt tools/routing/jvm-test/run.sh ~/onpath-routing/segments

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

val act = Activity.BIKE_TOURING
lateinit var eng: BRouterEngine

fun secondsTo(line: List<GeoPoint>, stops: List<GeoPoint>): Pair<Double, Double> {
    val at = NavLine(line).alongsInOrder(stops).last()
    val t = RouteTimes.of(line, act)
    return (t.total() - t.remainingFrom(at)) to at
}

/** One re-route: before (via every stop) vs now; returns the outcome. */
fun case(tag: String, line: NavLine, stops: List<NavStop>, pos: GeoPoint, progressM: Double): Rerouter.Found? {
    val off = line.match(pos)?.distanceM ?: 0.0
    val out = Rerouter(eng).reroute(Rerouter.Request(pos, line, progressM, stops, emptySet(), act, 3))
    if (out !is Rerouter.Found) { check(false, "$tag: ${(out as Rerouter.Failed).reason} ${out.detail}"); return null }
    val base = RerouteTargets.base(line, pos, progressM)
    val ahead = stops.indices.filter { it !in RerouteTargets.passed(stops, base, emptySet()) && it > 0 }
    val n = ahead.first()
    var detail = ""
    if (ahead.size >= 2) {
        val m = ahead[1]
        val via = eng.route(listOf(pos) + ahead.map { stops[it].point }, act, 3)
        val past = eng.route(listOf(pos) + ahead.drop(1).map { stops[it].point }, act, 3)
        val (vs, va) = secondsTo(via.points, listOf(pos, stops[n].point, stops[m].point))
        val (ss, sa) = secondsTo(past.points, listOf(pos, stops[m].point))
        val t = RouteTimes.of(line.points, act)
        val ps = t.remainingFrom(base) - t.remainingFrom(stops[m].alongM)
        detail = ("to stop ${m + 1}: via stop ${n + 1} %.0f s / %.0f m, straight %.0f s / %.0f m, plan %.0f s / %.0f m " +
            "(via ×%.2f straight, ×%.2f plan)").format(vs, va, ss, sa, ps, stops[m].alongM - base, vs / ss, vs / ps)
    }
    val now = when {
        out.ask != null -> "ASK \"Skip stop ${out.skipStop!! + 1}?\""
        out.skipStop != null -> "SKIP stop ${(out.skipStop ?: 0) + 1}"
        else -> "VIA stop ${n + 1}"
    }
    println("  $tag: %.0f m off the plan, next stop ${n + 1} (${if (stops[n].pinned) "pinned" else if (stops[n].name != null) "named" else "route point"})".format(off))
    if (detail.isNotEmpty()) println("      $detail")
    println("      before: VIA stop ${n + 1} · now: $now · new route %.1f km, %d ms".format(out.legM / 1000, out.tookMs))
    return out
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))

    // 1. Hilden round ride: Mitte → Itter → south → Benrath → north-west → Mitte (route points every 1–4 km)
    val mitte = GeoPoint(51.16923, 6.92560)
    val wps = listOf(mitte, GeoPoint(51.16405, 6.92868), GeoPoint(51.15810, 6.92152), GeoPoint(51.16136, 6.87100),
        GeoPoint(51.18000, 6.90000), mitte)
    val plan = eng.route(wps, act, 3)
    val line = NavLine(plan.points)
    val stops = line.alongsInOrder(wps).mapIndexed { i, a -> NavStop(wps[i], a) }
    println("1. Hilden round ride %.1f km, route points at %s km".format(line.lengthM / 1000,
        stops.joinToString(" / ") { "%.1f".format(it.alongM / 1000) }))
    for ((from, to) in listOf(500.0 to 800.0, 6_000.0 to 6_800.0)) for (side in listOf(90.0, -90.0)) {
        val mid = (from + to) / 2
        val dir = bearingDeg(line.pointAt(mid - 20), line.pointAt(mid + 20)) + side
        // the street beside the plan: 380 m off as on the ride, else the nearest one that really leaves it (≥ 150 m)
        var rode: RouteResult? = null
        var offs: List<Double> = emptyList()
        for (d in listOf(380.0, 300.0, 220.0, 450.0)) {
            val r = try { eng.route(listOf(line.pointAt(from), moved(line.pointAt(mid), dir, d), line.pointAt(to)), act, 3) }
                catch (e: RoutingException) { continue }
            val o = r.points.map { p -> line.match(p, from - 300, to + 2_000)?.distanceM ?: 0.0 }
            if (o.max() >= 150.0) { rode = r; offs = o; break }
        }
        if (rode == null) { println("  km ${from / 1000} ${if (side > 0) "right" else "left"}: no street beside the plan"); continue }
        // where the app asks: off route confirmed (first point ≥ 60 m off, after the 15 s), and the farthest point
        val first = offs.indexOfFirst { it >= 60.0 }
        val far = offs.indices.maxByOrNull { offs[it] } ?: continue
        val name = "km %.1f–%.1f %s".format(from / 1000, to / 1000, if (side > 0) "right" else "left")
        for ((i, what) in listOf(first to "confirmed", far to "farthest")) {
            val out = case("$name, $what", line, stops, rode.points[i], from) ?: continue
            check(out.skipStop == null || out.ask != null || stops[out.skipStop ?: 0].name == null, "$name: no deliberate stop left out silently")
        }
    }

    // 2. Next stop aside: Mitte → Benrath dragged south over a route point beside the direct road; rider on the direct road
    println("\n2. Next stop aside (Mitte → Benrath, route point dragged south of the direct road)")
    val benrath = GeoPoint(51.16136, 6.87100)
    val direct = NavLine(eng.route(listOf(mitte, benrath), act, 3).points)
    for (asideM in listOf(400.0, 900.0, 1_500.0)) {
        val atD = direct.lengthM * 0.45
        val dir = bearingDeg(direct.pointAt(atD - 20), direct.pointAt(atD + 20)) + 90 // right of the way west = north
        val x = moved(direct.pointAt(atD), dir, asideM)
        val wp2 = listOf(mitte, x, GeoPoint(51.16300, 6.86400), GeoPoint(51.15500, 6.85000))
        val pl = NavLine(eng.route(wp2, act, 3).points)
        for (kind in listOf("route point", "pinned", "named")) {
            val st = pl.alongsInOrder(wp2).mapIndexed { i, a ->
                NavStop(wp2[i], a, if (i == 1 && kind == "named") "Café" else null, pinned = i == 1 && kind == "pinned")
            }
            // the rider: on the direct road at 25 % (left the plan where it turns towards the route point)
            val pos = direct.pointAt(direct.lengthM * 0.25)
            val progress = pl.match(pos, 0.0, st[1].alongM)?.alongM ?: 0.0
            val out = case("aside ${asideM.toInt()} m, $kind", pl, st, pos, progress) ?: continue
            if (kind != "route point") check(out.skipStop == null || out.ask != null, "aside ${asideM.toInt()} m: $kind never left out silently")
            if (kind == "route point" && asideM >= 1_500.0) check(out.skipStop == 1, "aside 1500 m: an ordinary route point is left out or asked")
        }
    }

    // 3. Round trip: Mitte → north-west → Mitte; 150 m beside the start, shortly after leaving
    println("\n3. Round trip, just after the start")
    val loopW = listOf(mitte, GeoPoint(51.18000, 6.90000), mitte)
    val loop = NavLine(eng.route(loopW, act, 3).points)
    val ls = loop.alongsInOrder(loopW).mapIndexed { i, a -> NavStop(loopW[i], a) }
    val p0 = moved(loop.pointAt(150.0), bearingDeg(loop.pointAt(130.0), loop.pointAt(170.0)) + 90, 150.0)
    val lo = case("round trip", loop, ls, p0, 100.0)
    check(lo != null && lo.skipStop == null && lo.legM > loop.lengthM * 0.6, "round trip: the loop is kept (new route ${lo?.legM?.roundToInt()} m)")

    println(if (failures == 0) "\nALL CHECKS PASSED" else "\n$failures CHECK(S) FAILED")
    if (failures > 0) System.exit(1)
}

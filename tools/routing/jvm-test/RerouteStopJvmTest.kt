import com.onpath.app.data.Geo
import com.onpath.app.navigation.NavLine
import com.onpath.app.navigation.NavStop
import com.onpath.app.navigation.RerouteTargets
import com.onpath.app.navigation.Rerouter
import com.onpath.app.navigation.VisitRadius
import com.onpath.app.routing.*
import java.io.File
import kotlin.math.*

// A re-route never drops a stop without the question (core feature C5, owner 2026-10-30 / 2026-11-01 / 2026-11-03) –
// with real BRouter data (Meran, needs E10_N45.rd5), pass/fail.
// Bug G02 (website pictures 2026-11-07, emulator, 0.17.3): Hike T2 Vellau (Korblift valley station) → Leiteralm with
// Gasthof Hochmuth added as stop ② (named, "Als Zwischenstopp"). Walking off the route the box offered only "Neue Route bis
// zum Ziel: 3,8 km (4,5 km kürzer)" – the new route left the inn out, no "Über ②" / "② auslassen".
// Here: the plan as the app makes it (Planner, leg by leg), then walk-offs along the way to the inn – a real way off the
// route, both sides, asked where the off-route is confirmed (≥ 60 m off) and further out – each re-route must either pass
// the inn (within the "reached" radius) or ask about it (skipStop = 1 with the way via it in Found.ask).
// Run: KTTEST=$PWD/tools/routing/jvm-test/RerouteStopJvmTest.kt tools/routing/jvm-test/run.sh ~/onpath-routing/segments
// Env: DUMP=1 prints every case, not only the failures.

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
var cases = 0
fun check(ok: Boolean, what: String) { if (!ok || System.getenv("DUMP") != null) println((if (ok) "   ok   " else "   FAIL ") + what); if (!ok) failures++ }

val act = Activity.HIKE
const val SAC = 2

/** The line passes [p] within the "reached" radius (a stop on it counts as visited). */
fun passes(line: List<GeoPoint>, p: GeoPoint): Boolean {
    val nl = NavLine(line)
    return nl.isUsable && (nl.match(p)?.distanceM ?: Double.MAX_VALUE) <= VisitRadius.of(act)
}

/** Positions every ~1.2 m (1 fix/s on foot) along [pts]. */
fun walk(pts: List<GeoPoint>): List<GeoPoint> {
    val nl = NavLine(pts)
    if (!nl.isUsable) return pts
    val out = ArrayList<GeoPoint>()
    var a = 0.0
    while (a <= nl.lengthM) { out += nl.pointAt(a); a += 1.2 }
    return out
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))

    // the A-019 plan link (Vellau Korblift (Talstation) → Leiteralm) + Gasthof Hochmuth (next to the Hochmuth cable car's
    // top station, OSM way 27166910) added as stop ②
    val vellau = GeoPoint(46.69560, 11.10935)
    val hochmuth = GeoPoint(46.70255, 11.13140)
    val leiteralm = GeoPoint(46.70605, 11.11125)
    val wps = listOf(vellau, hochmuth, leiteralm)
    val names = listOf("Vellau Korblift (Talstation)", "Gasthof Hochmuth", "Leiteralm")
    val plan = Planner(eng).plan(wps, act, SAC)
    val line = NavLine(plan.points)
    val stops = line.alongsInOrder(wps).mapIndexed { i, a -> NavStop(wps[i], a, names[i]) }
    println("Plan Hike T2 %.1f km, stops at %s km, the inn %.0f m off the line".format(line.lengthM / 1000,
        stops.joinToString(" / ") { "%.2f".format(it.alongM / 1000) }, line.match(hochmuth)?.distanceM ?: -1.0))
    check(passes(plan.points, hochmuth), "the plan itself passes the inn")

    val inn = stops[1].alongM
    var along = 150.0
    while (along < inn - 150.0) {
        val dir0 = bearingDeg(line.pointAt(along - 15), line.pointAt(along + 15))
        for (side in listOf(90.0, -90.0)) for (d in listOf(250.0, 500.0)) {
            // a real way off the route: from the line to a point beside it (the router picks the ways)
            val off = moved(line.pointAt(along), dir0 + side, d)
            val r = try { eng.route(listOf(line.pointAt(along), off), act, SAC) } catch (e: RoutingException) { continue }
            val fixes = walk(r.points)
            val offs = fixes.map { p -> line.match(p, max(0.0, along - 300), along + 3_000)?.distanceM ?: 0.0 }
            val first = offs.indexOfFirst { it >= 60.0 }
            if (first < 0) continue
            val far = offs.indices.maxByOrNull { offs[it] }!!
            for (i in listOf(first, far).distinct()) {
                val pos = fixes[i]
                val recent = fixes.subList(max(0, i - 30), i + 1)
                val tag = "km %.2f %s %d m, %s (%.0f m off)".format(along / 1000, if (side > 0) "right" else "left", d.toInt(),
                    if (i == first) "confirmed" else "farthest", offs[i])
                val out = Rerouter(eng).reroute(Rerouter.Request(pos, line, along, stops, emptySet(), act, SAC, recent = recent))
                if (out !is Rerouter.Found) { println("   --   $tag: ${(out as Rerouter.Failed).reason} ${out.detail}"); continue }
                cases++
                val base = RerouteTargets.base(line, pos, along)
                val passed = RerouteTargets.passed(stops, base, emptySet())
                val via = passes(out.line, hochmuth)
                val asked = out.skipStop == 1 && out.ask != null && passes(out.ask.line, hochmuth)
                val kind = when {
                    out.ask != null -> "ASK skip ${out.skipStop?.plus(1)} (via-way passes the inn: ${passes(out.ask.line, hochmuth)})"
                    out.skipStop != null -> "SKIP ${out.skipStop + 1} unasked"
                    via -> "VIA the inn"
                    else -> "DROPS the inn"
                }
                check(via || asked, "$tag: $kind · %s route %.1f km (%+.1f km), base %.2f km, passed %s".format(
                    if (out.rejoinInM.isNaN()) "fresh" else "way back", out.legM / 1000, out.extraM / 1000, base / 1000,
                    passed.map { it + 1 }))
            }
        }
        along += 300.0
    }
    println(if (failures == 0) "\nALL $cases CASES PASSED" else "\n$failures OF $cases CASE(S) FAILED")
    if (failures > 0) System.exit(1)
}

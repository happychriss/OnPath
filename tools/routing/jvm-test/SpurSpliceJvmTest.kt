import com.onpath.app.data.Geo
import com.onpath.app.routing.*
import java.io.File
import kotlin.system.exitProcess

// The Bug-01 pass re-routes only around the cut stop (owner 2026-10-31, "Option 1"; Planner.splice), with the owner's
// plan and real BRouter data, pass/fail:
//   KTTEST=.../SpurSpliceJvmTest.kt JAVA_XMX=1g run.sh <segmentsDir with E5_N50.rd5>
// Hike T2, 70.6 km, 11 points from TU Dortmund to Hilden, points 1, 2 and 11 pinned. BRouter's via correction cuts
// point 5 in the 68 km chunk from point 2; the planner used to route that whole chunk again with point 5 as a chunk
// end (2.5 s more). Now it keeps the first line and routes only 4 → 5 → 6, spliced in at points 4 and 6.
// Prints the planner's time (fresh engine = empty route cache; the first run also loads the segments) and the length
// against the old way (the same plan with point 5 pinned = the old second pass).

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

var failures = 0
fun check(ok: Boolean, what: String) { println((if (ok) "   ok   " else "   FAIL ") + what); if (!ok) failures++ }

val WPS = listOf(
    GeoPoint(51.49275, 7.41772), GeoPoint(51.4906, 7.42465), GeoPoint(51.4872, 7.41724), GeoPoint(51.48471, 7.41236),
    GeoPoint(51.48362, 7.42875), GeoPoint(51.22429, 6.99459), GeoPoint(51.22052, 6.99428), GeoPoint(51.21558, 6.99643),
    GeoPoint(51.20045, 7.00439), GeoPoint(51.19222, 6.96206), GeoPoint(51.16826, 6.93204),
)
val PINS = setOf(0, 1, 10)

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val seg = File("/home/user/build/routing-test/segments")
    fun engine() = BRouterEngine(FakeCtx(files), seg)
    val act = Activity.HIKE

    // three runs, each with a fresh engine (no cached routes); the first one also loads the segments
    var p: PlannedRoute? = null
    val ms = (1..3).map {
        val planner = Planner(engine())
        val t0 = System.nanoTime()
        p = planner.plan(WPS, act, 2, PINS)
        ((System.nanoTime() - t0) / 1_000_000).also { t -> println("   run $it: $t ms, tries: ${planner.describeTries()}") }
    }
    val r = p!!
    println("   Planner: %.0f m, warm %d ms (best of runs 2–3)".format(r.distanceM, minOf(ms[1], ms[2])))

    // the old second pass: the same plan with point 5 as a chunk end
    val old = Planner(engine()).plan(WPS, act, 2, PINS + 4)
    println("   point 5 pinned (the old way): %.0f m → spliced %+.0f m".format(old.distanceM, r.distanceM - old.distanceM))

    check(r.error == null && r.legs.none { it.straight }, "routed, no error (${r.error})")
    check(r.skipped.isEmpty(), "no stop is left skipped (${r.skipped})")
    check(r.snapped.size == WPS.size, "one snapped spot per point")
    val whole = engine().route(WPS.subList(1, WPS.size), act, 2) // the first pass's chunk from point 2
    val spot = whole.matched.getOrNull(3)
    check(spot != null && WaypointSnap.missesMatched(WPS[4], spot, whole.points), "BRouter alone cuts point 5 in the chunk from point 2 (the case)")
    if (spot != null) check(WaypointSnap.distToLine(spot, r.points) <= 10.0,
        "the planned line passes point 5's matched spot (%.1f m)".format(WaypointSnap.distToLine(spot, r.points)))
    check(kotlin.math.abs(r.distanceM - old.distanceM) <= 0.03 * old.distanceM,
        "the length stays within 3 %% of the old way (%.0f vs %.0f m)".format(r.distanceM, old.distanceM))
    check(kotlin.math.abs(Geo.length(r.points) - r.distanceM) <= 0.01 * r.distanceM,
        "distance and line agree (%.0f vs %.0f m)".format(Geo.length(r.points), r.distanceM))
    check(r.ascentM > 0.0 && r.descentM > 0.0, "ascent and descent known (%.0f / %.0f m; old way %.0f / %.0f m)".format(r.ascentM, r.descentM, old.ascentM, old.descentM))
    val ways = r.wayTypes.sumOf { it.meters }; val surf = r.surfaces.sumOf { it.meters }
    check(kotlin.math.abs(ways - r.distanceM) <= 0.01 * r.distanceM && kotlin.math.abs(surf - r.distanceM) <= 0.01 * r.distanceM,
        "way types and surfaces add up to the distance (%.0f / %.0f m)".format(ways, surf))
    // no doubled piece: no stretch of the line is walked twice right after itself (a U-turn at a splice point)
    val back = uTurns(r.points)
    check(back.isEmpty(), "no U-turn at a splice point (${back.joinToString { "%.5f,%.5f".format(it.lat, it.lon) }})")
    // the same points give the same route (app = web = plan link)
    val again = Planner(engine()).plan(WPS, act, 2, PINS)
    check(again.points == r.points && again.distanceM == r.distanceM, "the same points give the same route")

    println(if (failures == 0) "SpurSpliceJvmTest: all OK" else "SpurSpliceJvmTest: $failures FAILED")
    if (failures > 0) exitProcess(1)
}

/** Points where the line turns back on itself (20 m on, within 3 m of the 20 m before) – except at the plan points. */
fun uTurns(line: List<GeoPoint>): List<GeoPoint> {
    val cum = Geo.cumulative(line)
    val out = ArrayList<GeoPoint>()
    for (k in 1 until line.lastIndex) {
        if (WPS.any { Geo.distance(it, line[k]) < 60.0 }) continue // a spur to a stop goes there and back on purpose
        val ahead = (k + 1 until line.size).firstOrNull { cum[it] - cum[k] >= 20.0 } ?: continue
        val before = line.subList((0..k).first { cum[k] - cum[it] <= 20.0 }, k + 1)
        if (before.size >= 2 && WaypointSnap.distToLine(line[ahead], before) < 3.0) out += line[k]
    }
    return out
}

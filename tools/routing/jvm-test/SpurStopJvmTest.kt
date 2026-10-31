import com.onpath.app.routing.*
import java.io.File
import kotlin.system.exitProcess

// "A point on a way is always visited" (core feature C3) with real BRouter data (needs E5_N50.rd5), pass/fail:
// KTTEST=.../SpurStopJvmTest.kt run.sh <segmentsDir>
// Field bug 2026-10-30, TU Dortmund by bike (touring.brf): stop 3 lies on a path (bicycle=yes) south of some steps,
// BRouter matches it 4.5 m from the tap – but in the whole 5-point route its via correction (up to 400 m for bikes)
// cuts the there-and-back over the steps, and the line passes 47 m from the matched spot. Planner must route to it
// (2 → down the path over the steps to 3 → back → west), so the line passes within 10 m of the matched spot.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

var failures = 0
fun check(ok: Boolean, what: String) { println((if (ok) "   ok   " else "   FAIL ") + what); if (!ok) failures++ }

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val wps = listOf(GeoPoint(51.49275, 7.41772), GeoPoint(51.48722, 7.41716), GeoPoint(51.48667, 7.41685),
        GeoPoint(51.48686, 7.41162), GeoPoint(51.48471, 7.41236))
    val act = Activity.BIKE_TOURING

    // BRouter alone: stop 3 matched on its path, but the whole route's line cuts it (the field bug)
    val whole = eng.route(wps, act, 2)
    val spot = whole.matched.getOrNull(2)
    check(spot != null, "BRouter reports where it matched stop 3 (OnPathHints.matched)")
    if (spot == null) { println("SpurStopJvmTest: $failures FAILED"); exitProcess(1) }
    val onWay = WaypointSnap.distM(wps[2], spot)
    val cut = WaypointSnap.distToLine(spot, whole.points)
    println("   BRouter: %.0f m, stop 3 matched %.1f m from the tap, line misses that spot by %.1f m".format(whole.distanceM, onWay, cut))
    check(onWay < 10.0, "stop 3 is matched on the path next to it (%.1f m)".format(onWay))
    check(WaypointSnap.missesMatched(wps[2], spot, whole.points), "the whole route counts as missing stop 3 (rule max(15 m, 3 × %.1f m))".format(onWay))

    // the planner: route again with stop 3 as a chunk end → the line passes it
    val planner = Planner(eng)
    val p = planner.plan(wps, act, 2)
    val line = p.legs.flatMap { it.points }
    val miss = WaypointSnap.distToLine(spot, line)
    println("   Planner: %.0f m (+%.0f m), tries: %s".format(p.distanceM, p.distanceM - whole.distanceM, planner.describeTries()))
    check(miss <= 10.0, "the planned line passes stop 3's matched spot within 10 m (%.1f m)".format(miss))
    check(p.distanceM - whole.distanceM in 40.0..250.0, "the detour to stop 3 and back adds about 95 m (%.0f m)".format(p.distanceM - whole.distanceM))
    check(p.skipped.isEmpty(), "no stop is left skipped (${p.skipped})")
    check(p.error == null, "no route error (${p.error})")
    val s3 = p.snapped.getOrNull(2)
    check(s3 != null && WaypointSnap.distM(s3, spot) <= 10.0, "stop 3 lies on the line where BRouter matched it")
    for (i in listOf(0, 1, 3, 4)) {
        val s = p.snapped.getOrNull(i)
        check(s != null && WaypointSnap.distM(s, wps[i]) <= 10.0, "stop ${i + 1} stays where it was tapped (%.1f m)".format(s?.let { WaypointSnap.distM(it, wps[i]) } ?: -1.0))
    }

    // the same stops on foot: BRouter goes over the steps by itself – no second pass
    val hikePlanner = Planner(eng)
    val h = hikePlanner.plan(wps, Activity.HIKE, 2)
    check(hikePlanner.tries.size == 1, "on foot one request is enough (${hikePlanner.describeTries()})")
    check(WaypointSnap.distToLine(spot, h.legs.flatMap { it.points }) <= 10.0, "on foot the line passes stop 3")

    println(if (failures == 0) "SpurStopJvmTest: all OK" else "SpurStopJvmTest: $failures FAILED")
    if (failures > 0) exitProcess(1)
}

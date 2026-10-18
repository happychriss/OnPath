import com.onpath.app.routing.*
import java.io.File

// Plan-point snapping with real BRouter data (KTTEST=.../SnapTest.kt run.sh <segmentsDir>).
// A point in a meadow near Algund must end up on the path BRouter used, as start, via and destination;
// routing again with the snapped points must not move them again (no recalculation loop).

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun f(p: GeoPoint?) = if (p == null) "null" else String.format("%.6f,%.6f", p.lat, p.lon)

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val a = GeoPoint(46.6830, 11.1300) // Algund
    val b = GeoPoint(46.6860, 11.1090) // towards Vellau
    var failures = 0
    // ~10 m and ~80 m off the nearest way BRouter uses for hiking
    for (meadow in listOf(GeoPoint(46.684, 11.100), GeoPoint(46.684, 11.0985))) for ((name, wps) in listOf(
        "${f(meadow)} as start" to listOf(meadow, a),
        "${f(meadow)} as destination" to listOf(a, meadow),
        "${f(meadow)} as via" to listOf(a, meadow, b),
    )) {
        try {
            val r = eng.route(wps, Activity.HIKE, 3)
            val on = WaypointSnap.onRoute(wps, r.points, r.matched)
            val snapped = WaypointSnap.apply(wps, on) ?: wps
            println("$name: ${r.points.size} pts, ${"%.0f".format(r.distanceM)} m")
            wps.indices.forEach { i ->
                println("   #$i ${f(wps[i])} matched=${f(r.matched.getOrNull(i))} onRoute=${f(on[i])} " +
                    "moved=${"%.1f".format(WaypointSnap.distM(wps[i], snapped[i]))} m " +
                    "toLine=${"%.2f".format(WaypointSnap.distToLine(snapped[i], r.points))} m")
            }
            val mi = wps.indexOf(meadow)
            val moved = WaypointSnap.distM(meadow, snapped[mi])
            val onLine = WaypointSnap.distToLine(snapped[mi], r.points)
            if (moved <= WaypointSnap.MIN_MOVE_M || onLine > 1.0) { println("   FAIL: meadow point not moved onto the line"); failures++ }
            // second run with the snapped points: nothing should move any more
            val r2 = eng.route(snapped, Activity.HIKE, 3)
            val again = WaypointSnap.apply(snapped, WaypointSnap.onRoute(snapped, r2.points, r2.matched))
            if (again != null) {
                println("   FAIL: second run moves points again: " + snapped.indices.joinToString { "%.1f".format(WaypointSnap.distM(snapped[it], again[it])) })
                failures++
            } else println("   stable: second run moves nothing (${"%.0f".format(r2.distanceM)} m)")
        } catch (e: RoutingException) {
            println("$name: RoutingException ${e.message}"); failures++
        }
    }
    println(if (failures == 0) "ALL OK" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}

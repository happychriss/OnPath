import com.onpath.app.routing.*
import java.io.File
import kotlin.system.exitProcess

// A bike route follows a shared foot / cycle path with a compacted surface instead of zig-zagging through side streets
// (KTTEST=.../ItterPathTest.kt run.sh <segmentsDir with E5_N50.rd5>; pass / fail).
// Case (Hilden, Trekking bike): plan points 1–4 south of the station, 2–4 on the path along the Itter
// (OSM ways 26650741 / 891696635: highway=path, bicycle=designated, foot=designated, surface=compacted; sign DE:240).
// Costing a compacted surface like loose ground (5.15 per metre, Touring 11.15 – more than a primary road) sends the
// route between points 3 and 4 over 700 m of residential / service streets; with compacted counted as firm
// (trekking.brf / touring.brf, docs/design/bike-routing.md decision 11) the leg is ~460 m on the path. The whole plan (to Rosellen, 20 km) keeps only real pushing: an 18 m sidewalk at the start and a 161 m
// footway without a bicycle tag at the destination.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun dist(a: GeoPoint, b: GeoPoint): Double {
    val x = Math.toRadians(b.lon - a.lon) * Math.cos(Math.toRadians((a.lat + b.lat) / 2))
    val y = Math.toRadians(b.lat - a.lat)
    return Math.sqrt(x * x + y * y) * 6_371_000.0
}

/** Pushing as the plan sheet counts it for touring / trekking / e-bike (ui/RouteWarnings PUSH, without merging). */
fun push(w: WayAttrs?): Boolean = w != null && w.wayType != "steps" && when {
    w.bicycle == "dismount" -> true
    w.wayType != "footway" && w.wayType != "path" -> false
    w.bicycle in setOf("yes", "designated", "permissive", "mtb") -> false
    w.bicycle == "no" -> true
    w.wayType == "footway" -> !(w.cycleRoute && !w.pedestrian)
    else -> w.sac >= 2 || w.mtb >= 2
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    // the owner's plan (app prefs "plan")
    val p1 = GeoPoint(51.169296, 6.925455); val p2 = GeoPoint(51.164046, 6.928682)
    val p3 = GeoPoint(51.160713, 6.926101); val p4 = GeoPoint(51.158100, 6.921517)
    val rosellen = GeoPoint(51.13035, 6.7337)
    var failures = 0
    for (a in listOf(Activity.BIKE_TREKKING, Activity.BIKE_TOURING, Activity.EBIKE)) {
        for ((name, wps, maxM, maxStreetM, maxPushM) in listOf(
            Case("3 -> 4 (along the Itter)", listOf(p3, p4), 520.0, 30.0, 0.0),
            Case("1 -> 2 -> 3 -> 4", listOf(p1, p2, p3, p4), 2300.0, Double.MAX_VALUE, 50.0),
            Case("1 -> 4 -> Rosellen (whole plan)", listOf(p1, p2, p3, p4, rosellen), 21500.0, Double.MAX_VALUE, 200.0),
        )) {
            try {
                val r = eng.route(wps, a, 2)
                var street = 0.0; var pushM = 0.0
                val ways = sortedMapOf<String, Double>()
                for (i in 1 until r.points.size) {
                    val w = r.points[i - 1].way; val d = dist(r.points[i - 1], r.points[i])
                    ways.merge(w?.wayType ?: "?", d, Double::plus)
                    if (w?.wayType in setOf("quiet_road", "road", "busy_road")) street += d
                    if (push(w)) pushM += d
                }
                val ok = r.distanceM <= maxM && street <= maxStreetM && pushM <= maxPushM
                if (!ok) failures++
                println(String.format("%-4s %-34s %-13s %6.0f m (max %.0f)  streets %4.0f m  pushing %4.0f m  %s", if (ok) "OK" else "FAIL",
                    name, a, r.distanceM, maxM, street, pushM, ways.entries.joinToString(" ") { String.format("%s=%.0f", it.key, it.value) }))
            } catch (e: RoutingException) {
                failures++
                println(String.format("FAIL %-34s %-13s %s", name, a, e.message))
            }
        }
    }
    println(if (failures == 0) "ItterPathTest: all OK" else "ItterPathTest: $failures FAILED")
    if (failures > 0) exitProcess(1)
}

data class Case(val name: String, val wps: List<GeoPoint>, val maxM: Double, val maxStreetM: Double, val maxPushM: Double)

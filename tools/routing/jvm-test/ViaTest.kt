import com.onpath.app.routing.*
import java.io.File

// Via-point tests (run with KTTEST=.../ViaTest.kt run.sh <segmentsDir>; add NO_VIA_CORRECTION=1 to compare).
// Reports for each case: km, delta vs. the route without the via, and metres of "out-and-back"
// (track segments that are later driven in the opposite direction = spikes).

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun key(p: GeoPoint) = Math.round(p.lat * 1e6) * 1_000_000_000L + Math.round(p.lon * 1e6)

fun dist(a: GeoPoint, b: GeoPoint): Double {
    val x = Math.toRadians(b.lon - a.lon) * Math.cos(Math.toRadians((a.lat + b.lat) / 2))
    val y = Math.toRadians(b.lat - a.lat)
    return Math.sqrt(x * x + y * y) * 6_371_000.0
}

/** Metres of track that are traversed again in the opposite direction. */
fun backtrackM(pts: List<GeoPoint>): Double {
    val seen = HashSet<Pair<Long, Long>>()
    for (i in 1 until pts.size) seen.add(key(pts[i - 1]) to key(pts[i]))
    var m = 0.0
    for (i in 1 until pts.size) if (seen.contains(key(pts[i]) to key(pts[i - 1]))) m += dist(pts[i - 1], pts[i])
    return m
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    System.getenv("VIA_DUMP")?.let { out ->
        // dump route geometries for picking via points: "name lat lon" per line
        File(out).printWriter().use { w ->
            val bz = listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500))
            for (p in eng.route(bz, Activity.BIKE_TREKKING, 3).points) w.println("bm ${p.lat} ${p.lon}")
            val al = listOf(GeoPoint(46.6830, 11.1300), GeoPoint(46.7006, 11.2585))
            for (p in eng.route(al, Activity.HIKE, 3).points) w.println("al ${p.lat} ${p.lon}")
        }
        return
    }
    // cases: name | activity | sac | lat,lon;lat,lon;...
    val cases = File(System.getenv("VIA_CASES") ?: error("set VIA_CASES")).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    val base = HashMap<String, Double>()
    println(String.format("%-40s %-13s %7s %7s %7s %6s %6s %6s", "case", "activity", "km", "delta", "back_m", "asc", "min", "ms"))
    for (line in cases) {
        val (name, act, sac, pts) = line.split('|').map { it.trim() }
        val wps = pts.split(';').map { val (a, b) = it.split(','); GeoPoint(a.toDouble(), b.toDouble()) }
        val a = Activity.valueOf(act)
        val t0 = System.currentTimeMillis()
        try {
            val r = eng.route(wps, a, sac.toInt())
            val ms = System.currentTimeMillis() - t0
            val group = name.substringBefore(':')
            if (wps.size == 2) base[group] = r.distanceM
            val delta = base[group]?.let { r.distanceM - it } ?: 0.0
            val viaSnap = if (wps.size > 2) wps.subList(1, wps.size - 1).joinToString(",") { v ->
                String.format("%.0f", r.points.minOf { dist(v, it) })
            } else "-"
            println(String.format("%-40s %-13s %7.2f %+7.0f %7.0f %6.0f %6.1f %6d  via->route m: %s", name, act, r.distanceM / 1000, delta,
                backtrackM(r.points), r.ascentM, r.durationS / 60, ms, viaSnap))
            System.getenv("VIA_GEOM")?.let { d -> File(d, name.replace(Regex("[^A-Za-z0-9]+"), "_") + ".txt").writeText(r.points.joinToString("\n") { "${it.lat} ${it.lon}" }) }
        } catch (e: RoutingException) {
            println(String.format("%-40s %-13s -> RoutingException (%d ms): %s", name, act, System.currentTimeMillis() - t0, e.message))
        }
    }
}

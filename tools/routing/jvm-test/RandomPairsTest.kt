import com.onpath.app.routing.*
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/*
 * Random point pairs in a box, routed per bike activity; metres per way type over all routes (A/B of profiles).
 *   RAW_TAP=1 KTTEST=tools/routing/jvm-test/RandomPairsTest.kt [ASSETS_DIR=<variant>] run.sh <segmentsDir> <workDir>
 * Env: RP_BBOX=lat0,lon0,lat1,lon1 (default 51.10,6.75,51.25,7.10 = Düsseldorf / Hilden / Solingen), RP_SEED (42),
 *   RP_N (60), RP_MIN_KM (2), RP_MAX_KM (8), RP_ACTS (BIKE_TOURING,BIKE_TREKKING,EBIKE),
 *   RP_GEOM=<file> writes "act pair km md5-of-line" per route (to count routes that changed between variants).
 * Way classes from BRouter's full way tags: bike way (cycleway / bicycle=designated|yes on path, footway, track),
 * quiet road, road, busy road (primary / trunk, and secondary), footway without a bicycle tag, track / path.
 */

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun dist(a: GeoPoint, b: GeoPoint): Double {
    val x = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
    val y = Math.toRadians(b.lat - a.lat)
    return sqrt(x * x + y * y) * 6_371_000.0
}

fun mkey(lon: Int, lat: Int) = (lon.toLong() shl 32) or (lat.toLong() and 0xffffffffL)
fun mkey(p: GeoPoint) = mkey(Math.round(p.lon * 1e6).toInt(), Math.round(p.lat * 1e6).toInt())

fun spanTags(lon: IntArray, lat: IntArray, messages: List<String?>, into: MutableMap<Pair<Long, Long>, String>) {
    var k = 0
    for (line in messages) {
        val cols = line?.split('\t') ?: continue
        if (cols.size < 10) continue
        val mlon = cols[0].trim().toIntOrNull() ?: continue
        val mlat = cols[1].trim().toIntOrNull() ?: continue
        if (lon[k] == mlon && lat[k] == mlat) continue
        var j = k + 1
        while (j < lon.size && (lon[j] != mlon || lat[j] != mlat)) j++
        if (j >= lon.size) continue
        for (i in k until j) into[mkey(lon[i], lat[i]) to mkey(lon[i + 1], lat[i + 1])] = cols[9].trim()
        k = j
    }
}

fun cls(tags: Map<String, String>): String {
    val hw = tags["highway"] ?: return if (tags["route"] == "ferry") "ferry" else "other"
    val b = tags["bicycle"]
    return when {
        hw == "cycleway" -> "bike_way"
        hw in setOf("path", "footway", "track", "bridleway") && (b == "designated" || b == "yes") -> "bike_way"
        hw == "footway" || hw == "pedestrian" -> if (b == null) "footway_no_bike" else "footway_other"
        hw == "track" || hw == "path" || hw == "bridleway" -> "track_path"
        hw in setOf("residential", "service", "unclassified", "living_street", "road") -> "quiet_road"
        hw.startsWith("tertiary") -> "road"
        hw.startsWith("secondary") || hw.startsWith("primary") || hw.startsWith("trunk") -> "busy_road"
        hw == "steps" -> "steps"
        else -> "other"
    }
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val bb = (System.getenv("RP_BBOX") ?: "51.10,6.75,51.25,7.10").split(',').map { it.trim().toDouble() }
    val rnd = java.util.Random((System.getenv("RP_SEED") ?: "42").toLong())
    val n = (System.getenv("RP_N") ?: "60").toInt()
    val minM = (System.getenv("RP_MIN_KM") ?: "2").toDouble() * 1000; val maxM = (System.getenv("RP_MAX_KM") ?: "8").toDouble() * 1000
    val acts = (System.getenv("RP_ACTS") ?: "BIKE_TOURING,BIKE_TREKKING,EBIKE").split(',').map { Activity.valueOf(it.trim()) }
    val pairs = ArrayList<Pair<GeoPoint, GeoPoint>>()
    while (pairs.size < n) {
        val a = GeoPoint(bb[0] + rnd.nextDouble() * (bb[2] - bb[0]), bb[1] + rnd.nextDouble() * (bb[3] - bb[1]))
        val b = GeoPoint(bb[0] + rnd.nextDouble() * (bb[2] - bb[0]), bb[1] + rnd.nextDouble() * (bb[3] - bb[1]))
        if (dist(a, b) in minM..maxM) pairs += a to b
    }
    val tap = HashMap<Pair<Long, Long>, String>()
    RawTap.sink = { lon, lat, msg -> spanTags(lon, lat, msg, tap) }
    val geom = System.getenv("RP_GEOM")?.let { File(it).printWriter() }
    val keys = listOf("bike_way", "quiet_road", "road", "busy_road", "footway_no_bike", "footway_other", "track_path", "steps", "ferry", "other", "untagged")
    println("activity      routes  km     " + keys.joinToString(" ") { String.format("%9s", it.take(9)) })
    for (act in acts) {
        val m = HashMap<String, Double>(); var km = 0.0; var ok = 0
        pairs.forEachIndexed { i, (a, b) ->
            tap.clear()
            try {
                val r = eng.route(listOf(a, b), act, 3)
                ok++; km += r.distanceM / 1000
                for (j in 1 until r.points.size) {
                    val t = tap[mkey(r.points[j - 1]) to mkey(r.points[j])]
                    val c = if (t == null) "untagged" else cls(BRouterWayStats.parseTags(t))
                    m.merge(c, dist(r.points[j - 1], r.points[j]), Double::plus)
                }
                geom?.println(String.format(Locale.ROOT, "%s %d %.3f %s", act, i, r.distanceM / 1000,
                    java.security.MessageDigest.getInstance("MD5").digest(r.points.joinToString(";") { "${it.lat},${it.lon}" }.toByteArray())
                        .joinToString("") { "%02x".format(it) }))
            } catch (e: RoutingException) {
                geom?.println("$act $i - failed")
            }
        }
        val tot = m.values.sum()
        println(String.format(Locale.ROOT, "%-13s %6d %6.1f ", act, ok, km) + keys.joinToString(" ") { String.format(Locale.ROOT, "%8.1f%%", 100 * (m[it] ?: 0.0) / tot) })
    }
    geom?.close()
}

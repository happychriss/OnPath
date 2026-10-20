import com.onpath.app.routing.*
import java.io.File

// Oneway check for the bike profiles (run with KTTEST=.../OnewayTest.kt ONEWAY_SEGS=<file> run.sh <segmentsDir>):
// routes between points across Meran and counts metres ridden against a oneway where bikes are not exempt.
// ONEWAY_SEGS: "lat1 lon1 lat2 lon2 highway" per wrong-way segment (pyosmium export: oneway=yes reversed, -1 forward,
// oneway:bicycle=no / cycleway=opposite* / contraflow lanes left out).

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

fun key(lat: Double, lon: Double) = Math.round(lat * 1e5) * 100_000_000L + Math.round(lon * 1e5)

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val bad = HashMap<Long, MutableSet<Long>>()
    val hwOf = HashMap<Pair<Long, Long>, String>()
    File(System.getenv("ONEWAY_SEGS")).readLines().filter { it.isNotBlank() }.forEach { l ->
        val f = l.trim().split(' ')
        val a = key(f[0].toDouble(), f[1].toDouble()); val b = key(f[2].toDouble(), f[3].toDouble())
        bad.getOrPut(a) { HashSet() }.add(b); hwOf[a to b] = f[4]
    }
    fun near(k: Long) = (-1..1).flatMap { da -> (-1..1).map { db -> k + da * 100_000_000L + db } }
    fun wrong(a: GeoPoint, b: GeoPoint): String? {
        for (ka in near(key(a.lat, a.lon))) { val s = bad[ka] ?: continue
            for (kb in near(key(b.lat, b.lon))) if (kb in s) return hwOf[ka to kb] ?: "?" }
        return null
    }
    // Meran: station, Kurhaus, Untermais, Obermais, Sinich road, Lazag, Algund edge, Thermen
    val pts = listOf(
        "Bahnhof" to GeoPoint(46.6722, 11.1497), "Kurhaus" to GeoPoint(46.6703, 11.1620),
        "Untermais" to GeoPoint(46.6612, 11.1590), "Obermais" to GeoPoint(46.6705, 11.1795),
        "Lazag" to GeoPoint(46.6760, 11.1545), "Therme" to GeoPoint(46.6680, 11.1555),
        "Rennweg" to GeoPoint(46.6735, 11.1640), "Maria Trost" to GeoPoint(46.6640, 11.1720),
    )
    for (act in listOf(Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.MTB)) {
        var km = 0.0; var wrongM = 0.0; var routes = 0; var affected = 0
        val byHw = sortedMapOf<String, Double>(); val examples = ArrayList<String>()
        for ((na, a) in pts) for ((nb, b) in pts) {
            if (na == nb) continue
            try {
                val r = eng.route(listOf(a, b), act, 3)
                routes++; km += r.distanceM / 1000
                var w = 0.0
                for (i in 1 until r.points.size) {
                    val p = r.points[i - 1]; val q = r.points[i]
                    wrong(p, q)?.let { hw -> val d = dist(p, q); w += d; byHw.merge(hw, d, Double::plus)
                        if (examples.size < 6) examples += String.format("%s->%s @%.5f,%.5f %s", na, nb, p.lat, p.lon, hw) }
                }
                if (w > 0) affected++
                wrongM += w
            } catch (e: RoutingException) { println("$act $na->$nb: no route ${e.message}") }
        }
        println(String.format("%-14s routes %d, %.1f km, against oneway %.0f m in %d routes  %s", act, routes, km, wrongM, affected, byHw))
        examples.forEach { println("   $it") }
    }
}

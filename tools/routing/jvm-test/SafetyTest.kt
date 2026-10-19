import com.onpath.app.routing.*
import java.io.File

// Trail safety check (run with KTTEST=.../SafetyTest.kt run.sh <segmentsDir>): routes real cases in South Tyrol and
// prints km, ascent and metres per SAC grade / way type / via_ferrata / bad trail_visibility along the route.
// via_ferrata is found by geometry: SAFETY_VF=<file with "lat lon" per line of every via_ferrata node> (pyosmium export),
// because the app's WayAttrs map highway=via_ferrata to "path". SAFETY_GEOM=<dir> writes each route's points.

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
    // via_ferrata nodes, keyed at ~1 m (1e-5 deg) with the 8 neighbours so rounding never misses one
    val vf = HashSet<Long>()
    System.getenv("SAFETY_VF")?.let { f ->
        File(f).readLines().filter { it.isNotBlank() }.forEach { l ->
            val (a, b) = l.trim().split(' ').map { it.toDouble() }
            for (da in -1..1) for (db in -1..1) vf.add(key(a + da * 1e-5, b + db * 1e-5))
        }
    }
    val p = { a: Double, b: Double -> GeoPoint(a, b) }
    val m2000 = p(46.6870, 11.2350); val ifinger = p(46.6923, 11.25668)
    val algund = p(46.7000, 11.1200); val hochgang = p(46.7129, 11.08571); val hgScharte = p(46.72091, 11.0831)
    data class C(val name: String, val pts: List<GeoPoint>, val act: Activity, val sac: Int)
    val cases = listOf(
        // (1) hike T2 to destinations only reachable over T3+
        C("1a M2000->Gr.Ifinger", listOf(m2000, ifinger), Activity.HIKE, 2),
        C("1b Hochganghaus->Hochgangscharte", listOf(hochgang, hgScharte), Activity.HIKE, 2),
        C("1c Ulten->Mutegg", listOf(p(46.5560, 10.9180), p(46.54142, 10.9032)), Activity.HIKE, 2),
        // (3) sanity: normal T3 hike
        C("3 Algund->Hochganghaus", listOf(algund, hochgang), Activity.HIKE, 3),
        C("3b Algund->Hochganghaus T2", listOf(algund, hochgang), Activity.HIKE, 2),
        C("3c Algund->Vellau", listOf(p(46.6830, 11.1300), p(46.6860, 11.1090)), Activity.HIKE, 3),
        // (4) via ferrata: Heini-Holzer-Klettersteig (Ifinger) and Knott-Klettersteig
        C("4a M2000->Gr.Ifinger T3", listOf(m2000, ifinger), Activity.HIKE, 3),
        C("4b M2000->Gr.Ifinger T5", listOf(m2000, ifinger), Activity.HIKE, 5),
        C("4c M2000->Kl.Ifinger T4", listOf(m2000, p(46.69193, 11.25769)), Activity.HIKE, 4),
        C("4d ->Knott KS top T5", listOf(p(46.6560, 10.9900), p(46.660647, 10.9846847)), Activity.HIKE, 5),
        C("4e M2000->Gr.Ifinger MTB", listOf(m2000, ifinger), Activity.MTB, 3),
        // (2) bikes over mountain paths
        C("2a Algund->Hochganghaus trek", listOf(algund, hochgang), Activity.BIKE_TREKKING, 3),
        C("2b M2000->Ifinger hut trek", listOf(m2000, p(46.6995, 11.2430)), Activity.BIKE_TREKKING, 3),
        C("2c Vigiljoch->St.Pankraz trek", listOf(p(46.6230, 11.1080), p(46.5860, 11.0040)), Activity.BIKE_TREKKING, 3),
        C("2d Hochganghaus->Leiteralm trek", listOf(hochgang, p(46.6930, 11.0760)), Activity.BIKE_TREKKING, 3),
        C("2e Algund->Hochganghaus touring", listOf(algund, hochgang), Activity.BIKE_TOURING, 3),
        C("2f Hochganghaus->Leiteralm touring", listOf(hochgang, p(46.6930, 11.0760)), Activity.BIKE_TOURING, 3),
        C("2g Hochganghaus->Leiteralm ebike", listOf(hochgang, p(46.6930, 11.0760)), Activity.EBIKE, 3),
        C("2k Vigiljoch->Pankraz track trek", listOf(p(46.6230, 11.1080), p(46.58586, 11.02147)), Activity.BIKE_TREKKING, 3),
        C("2l Vigiljoch->Pankraz track tour", listOf(p(46.6230, 11.1080), p(46.58586, 11.02147)), Activity.BIKE_TOURING, 3),
        C("2m Hafling->Voeran trek", listOf(p(46.6438, 11.22445), p(46.60537, 11.22627)), Activity.BIKE_TREKKING, 3),
        C("2n Schenna->Hafling trek", listOf(p(46.68998, 11.18723), p(46.6438, 11.22445)), Activity.BIKE_TREKKING, 3),
        C("2o Partschins->Algund ebike", listOf(p(46.68385, 11.07352), algund), Activity.EBIKE, 3),
        C("2h M2000->Ifinger hut MTB", listOf(m2000, p(46.6995, 11.2430)), Activity.MTB, 3),
        C("2i Algund->Hochganghaus MTB", listOf(algund, hochgang), Activity.MTB, 3),
        C("2j Hochganghaus->Leiteralm MTB", listOf(hochgang, p(46.6930, 11.0760)), Activity.MTB, 3),
    )
    val tag = System.getenv("SAFETY_TAG") ?: ""
    println(String.format("%-36s %-13s %3s %6s %5s %5s | %-44s | %5s %5s | %s", "case $tag", "activity", "T", "km", "asc", "end_m",
        "m per SAC T0..T6", "vf_m", "badvis", "way types (m)"))
    for (c in cases) {
        try {
            val r = eng.route(c.pts, c.act, c.sac)
            val sac = DoubleArray(7); var vfm = 0.0; var bad = 0.0
            val ways = sortedMapOf<String, Double>()
            for (i in 1 until r.points.size) {
                val a = r.points[i - 1]; val b = r.points[i]; val d = dist(a, b)
                val w = a.way
                sac[(w?.sac ?: 0).coerceIn(0, 6)] += d
                ways.merge(w?.wayType ?: "?", d, Double::plus)
                if (w?.visibility in setOf("bad", "poor", "horrible", "very_bad", "no")) bad += d
                if (vf.contains(key(a.lat, a.lon)) && vf.contains(key(b.lat, b.lon))) vfm += d
            }
            val endM = dist(r.points.last(), c.pts.last())
            println(String.format("%-36s %-13s %3d %6.2f %5.0f %5.0f | %-44s | %5.0f %5.0f | %s", c.name, c.act, c.sac,
                r.distanceM / 1000, r.ascentM, endM, sac.joinToString(" ") { String.format("%5.0f", it) }, vfm, bad,
                ways.entries.joinToString(" ") { String.format("%s=%.0f", it.key, it.value) }))
            System.getenv("SAFETY_GEOM")?.let { d ->
                File(d).mkdirs()
                File(d, tag + "_" + c.name.replace(Regex("[^A-Za-z0-9]+"), "_") + ".txt")
                    .writeText(r.points.joinToString("\n") { "${it.lat} ${it.lon} ${it.way?.code() ?: ""}" })
            }
        } catch (e: RoutingException) {
            println(String.format("%-36s %-13s %3d -> no route: %s", c.name, c.act, c.sac, e.message))
        }
    }
}

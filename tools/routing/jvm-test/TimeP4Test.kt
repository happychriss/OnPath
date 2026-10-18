import com.onpath.app.routing.*
import com.onpath.app.time.RouteTimes
import java.io.File

// Time estimate P4 (docs/plan-time-estimate.md): reference routes timed without way attributes (P0–P3) and with them
// (SAC / surface / steps / MTB scale per span from BRouter's track messages). Normal pace, nothing learned.
// Run: KTTEST=$PWD/tools/routing/jvm-test/TimeP4Test.kt tools/routing/jvm-test/run.sh <segmentsDir>

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun hm(s: Double) = "%d:%02d".format((s / 3600).toInt(), ((s % 3600) / 60).toInt())

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))

    data class Case(val id: String, val name: String, val wps: List<GeoPoint>, val act: Activity, val sac: Int = 3)
    val algund = GeoPoint(46.6830, 11.1300)
    val leiter = GeoPoint(46.6990, 11.0950)
    val cases = listOf(
        Case("H1", "Meran - Lana (valley)", listOf(GeoPoint(46.6725, 11.1490), GeoPoint(46.6150, 11.1560)), Activity.HIKE),
        Case("H2", "Algund - Schenna (Waalweg)", listOf(algund, GeoPoint(46.6890, 11.1870)), Activity.HIKE),
        Case("H3", "Algund - Leiteralm (up)", listOf(algund, leiter), Activity.HIKE),
        Case("H5", "Algund - Leiteralm - Algund", listOf(algund, leiter, algund), Activity.HIKE),
        Case("H6", "Meran 2000 - Ifinger hut", listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.6995, 11.2430)), Activity.HIKE),
        Case("H7", "Lana - Vigiljoch - Lana", listOf(GeoPoint(46.6120, 11.1450), GeoPoint(46.6020, 11.1060), GeoPoint(46.6120, 11.1450)), Activity.HIKE),
        Case("H8", "Saltaus - Hirzer hut (up)", listOf(GeoPoint(46.7360, 11.2140), GeoPoint(46.7400, 11.2370)), Activity.HIKE, 4),
        Case("H8r", "Hirzer hut - Saltaus (down)", listOf(GeoPoint(46.7400, 11.2370), GeoPoint(46.7360, 11.2140)), Activity.HIKE, 4),
        Case("H9", "Meran 2000 - Ifinger summit (T4)", listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.7006, 11.2585)), Activity.HIKE, 5),
        Case("H10", "Texel group T4 traverse", listOf(GeoPoint(46.7261, 11.0768), GeoPoint(46.7229, 11.0245)), Activity.HIKE, 5),
        Case("H11", "Texel group T5 section", listOf(GeoPoint(46.7226, 11.0115), GeoPoint(46.7236, 10.9843)), Activity.HIKE, 5),
        Case("B1", "Bozen - Meran (valley)", listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500)), Activity.BIKE_TREKKING),
        Case("B1t", "Bozen - Meran (valley)", listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500)), Activity.BIKE_TOURING),
        Case("B2", "Meran - Schenna - Meran", listOf(GeoPoint(46.6725, 11.1600), GeoPoint(46.6890, 11.1870), GeoPoint(46.6725, 11.1600)), Activity.BIKE_TREKKING),
        Case("B3", "Lana - Vigiljoch (MTB)", listOf(GeoPoint(46.6120, 11.1450), GeoPoint(46.6020, 11.1060)), Activity.MTB),
        Case("B3e", "Lana - Vigiljoch (e-MTB)", listOf(GeoPoint(46.6120, 11.1450), GeoPoint(46.6020, 11.1060)), Activity.EMTB),
        Case("B4", "Algund - Vellau (MTB)", listOf(algund, GeoPoint(46.6860, 11.1090)), Activity.MTB),
    )
    println("%-4s %-34s %-13s %5s | %6s %6s %6s | %s".format("id", "route", "activity", "km", "before", "P4", "diff", "way mix (km)"))
    for (c in cases) {
        val r = try { eng.route(c.wps, c.act, c.sac) } catch (e: RoutingException) { println("${c.id}: ${e.message}"); continue }
        val known = r.points.count { it.way != null }
        val old = RouteTimes.of(r.points.map { it.copy(way = null) }, c.act).total()
        val new = RouteTimes.of(r.points, c.act).total()
        val mix = LinkedHashMap<String, Double>()
        for (i in 0 until r.points.size - 1) {
            val w = r.points[i].way
            val k = when {
                w == null -> "?"
                c.act == Activity.HIKE -> if (w.wayType == "steps") "steps" else if (w.sac > 0) "T${w.sac}" else w.wayType
                else -> w.surface + (if (w.mtb >= 0) "/S${w.mtb}" else if (w.sac > 0) "/T${w.sac}" else "") +
                    (w.smoothness?.let { "/$it" } ?: "")
            }
            mix.merge(k, com.onpath.app.data.Geo.distance(r.points[i], r.points[i + 1]), Double::plus)
        }
        println("%-4s %-34s %-13s %5.1f | %6s %6s %+5.0f%% | %s".format(c.id, c.name, c.act.name, r.distanceM / 1000,
            hm(old), hm(new), (new / old - 1) * 100,
            mix.entries.sortedByDescending { it.value }.take(6).joinToString(" ") { "%s %.1f".format(it.key, it.value / 1000) }))
        if (known != r.points.size) println("     !! way attributes on $known of ${r.points.size} points")
    }
}

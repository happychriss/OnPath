import com.onpath.app.routing.*
import java.io.File

// Writes the reference routes of docs/plan-time-estimate.md (real BRouter routes, Bozen / Meran data) as small GPX
// files for the unit tests of the time model (app/src/test/resources/time/routes/). The line carries BRouter's DEM
// elevation, exactly what the app's plan sheet and Navigate see. Also prints BRouter's own time for comparison.
// Run: KTTEST=$PWD/tools/routing/jvm-test/ExportTimeRoutes.kt tools/routing/jvm-test/run.sh <segmentsDir>
// Run from the repo root (OUT=<dir> overrides the output folder).

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val out = File(System.getenv("OUT") ?: "app/src/test/resources/time/routes").apply { mkdirs() }

    data class Case(val id: String, val name: String, val wps: List<GeoPoint>, val act: Activity, val sac: Int = 3)
    val algund = GeoPoint(46.6830, 11.1300)
    val leiter = GeoPoint(46.6990, 11.0950)
    val cases = listOf(
        Case("H1", "Meran - Lana (valley)", listOf(GeoPoint(46.6725, 11.1490), GeoPoint(46.6150, 11.1560)), Activity.HIKE),
        Case("H2", "Algund - Schenna (Waalweg)", listOf(algund, GeoPoint(46.6890, 11.1870)), Activity.HIKE),
        Case("H3", "Algund - Leiteralm (up)", listOf(algund, leiter), Activity.HIKE),
        Case("H4", "Leiteralm - Algund (down)", listOf(leiter, algund), Activity.HIKE),
        Case("H5", "Algund - Leiteralm - Algund", listOf(algund, leiter, algund), Activity.HIKE),
        Case("H6", "Meran 2000 - Ifinger hut", listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.6995, 11.2430)), Activity.HIKE),
        Case("H7", "Lana - Vigiljoch - Lana", listOf(GeoPoint(46.6120, 11.1450), GeoPoint(46.6020, 11.1060), GeoPoint(46.6120, 11.1450)), Activity.HIKE),
        Case("H8", "Saltaus - Hirzer hut (up)", listOf(GeoPoint(46.7360, 11.2140), GeoPoint(46.7400, 11.2370)), Activity.HIKE, 4),
        Case("H8r", "Hirzer hut - Saltaus (down)", listOf(GeoPoint(46.7400, 11.2370), GeoPoint(46.7360, 11.2140)), Activity.HIKE, 4),
        Case("H9", "Meran 2000 - Ifinger summit (T4)", listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.7006, 11.2585)), Activity.HIKE, 5),
        Case("B1", "Bozen - Meran (valley)", listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500)), Activity.BIKE_TREKKING),
        Case("B2", "Meran - Schenna - Meran", listOf(GeoPoint(46.6725, 11.1600), GeoPoint(46.6890, 11.1870), GeoPoint(46.6725, 11.1600)), Activity.BIKE_TREKKING),
        Case("B3", "Lana - Vigiljoch (MTB)", listOf(GeoPoint(46.6120, 11.1450), GeoPoint(46.6020, 11.1060)), Activity.MTB),
    )
    for (c in cases) {
        val r = try { eng.route(c.wps, c.act, c.sac) } catch (e: RoutingException) { println("${c.id}: ${e.message}"); continue }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"OnPath ExportTimeRoutes\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("<metadata><name>${c.id} ${c.name}</name><desc>activity=${c.act.name} sac=${c.sac} brouterS=${r.durationS.toInt()} " +
            "distanceM=${r.distanceM.toInt()} ascentM=${r.ascentM.toInt()} descentM=${r.descentM.toInt()}</desc></metadata>\n<trk><trkseg>\n")
        for (p in r.points) {
            sb.append("<trkpt lat=\"%.6f\" lon=\"%.6f\">".format(java.util.Locale.US, p.lat, p.lon))
            p.ele?.let { sb.append("<ele>%.2f</ele>".format(java.util.Locale.US, it)) }
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk>\n</gpx>\n")
        File(out, "${c.id}.gpx").writeText(sb.toString())
        println("%-4s %-34s %5.1f km  BRouter %d s  %d points".format(c.id, c.name, r.distanceM / 1000, r.durationS.toInt(), r.points.size))
    }
}

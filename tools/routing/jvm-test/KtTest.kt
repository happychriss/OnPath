import com.onpath.app.routing.*
import java.io.File

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val bm = listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500))
    val cases = mutableListOf<Triple<String, List<GeoPoint>, Pair<Activity, Int>>>()
    for (a in Activity.values()) cases.add(Triple("Bozen->Meran", bm, a to 3))
    cases.add(Triple("Algund->Vellau", listOf(GeoPoint(46.6830, 11.1300), GeoPoint(46.6860, 11.1090)), Activity.HIKE to 3))
    val m2 = listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.6995, 11.2430))
    cases.add(Triple("Meran2000->Ifinger hut", m2, Activity.HIKE to 2))
    cases.add(Triple("Meran2000->Ifinger hut", m2, Activity.HIKE to 5))
    val sum = listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.7006, 11.2585))
    for (s in listOf(2, 3, 5)) cases.add(Triple("Meran2000->Ifinger summit", sum, Activity.HIKE to s))
    cases.add(Triple("Bozen-via Terlan->Meran", listOf(bm[0], GeoPoint(46.5300, 11.2480), bm[1]), Activity.BIKE_TREKKING to 3))
    cases.add(Triple("Bozen-via Terlan->Meran", listOf(bm[0], GeoPoint(46.5300, 11.2480), bm[1]), Activity.EBIKE to 3))
    cases.add(Triple("ERR no data (Vienna)", listOf(GeoPoint(48.2, 16.37), GeoPoint(48.21, 16.38)), Activity.HIKE to 3))
    cases.add(Triple("ERR far from path (Lake Garda centre)", listOf(GeoPoint(45.575, 10.705), GeoPoint(45.56, 10.62)), Activity.HIKE to 3))
    cases.add(Triple("ERR far from path (Adriatic sea)", listOf(GeoPoint(45.68, 13.39), GeoPoint(45.50, 13.15)), Activity.BIKE_TOURING to 3))
    cases.add(Triple("ERR single point", listOf(GeoPoint(46.4970, 11.3580)), Activity.HIKE to 3))
    println(String.format("%-38s %-14s %3s %8s %6s %6s %8s %6s %5s", "route", "activity", "sac", "km", "asc", "desc", "min", "ms", "pts"))
    for ((name, wps, ap) in cases) {
        val t0 = System.currentTimeMillis()
        try {
            val r = eng.route(wps, ap.first, ap.second)
            val ms = System.currentTimeMillis() - t0
            println(String.format("%-38s %-14s %3d %8.2f %6.0f %6.0f %8.1f %6d %5d  ele[0]=%.0f ele[-1]=%.0f", name, ap.first, ap.second,
                r.distanceM / 1000, r.ascentM, r.descentM, r.durationS / 60, ms, r.points.size, r.points.first().ele, r.points.last().ele))
        } catch (e: RoutingException) {
            println(String.format("%-38s %-14s %3d  -> RoutingException (%d ms): %s", name, ap.first, ap.second, System.currentTimeMillis() - t0, e.message))
        }
    }
}

import com.onpath.app.routing.*
import com.onpath.app.ui.RouteWarnings
import com.onpath.app.ui.SpotKind
import java.io.File

// Route hints (owner 2026-10-26): a route over a Rhine ferry gets the amber "Ferry … – check times" line, from the app's
// real route line (way attributes per point, RouteWarnings.detect). Pass / fail, needs E5_N50.rd5:
//   KTTEST=tools/routing/jvm-test/FerryHintTest.kt run.sh <segmentsDir> <workDir>
// Cases: Zons ⇄ Düsseldorf-Urdenbach and Kaiserswerth ⇄ Meerbusch-Langst (the next bridges are 6–10 km away), every
// activity class (Bike, Gravel bike, MTB, Hike). Also prints the hint line of each route.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val cases = listOf(
        "Zons -> Urdenbach" to listOf(GeoPoint(51.1213, 6.8462), GeoPoint(51.1372, 6.8700)),
        "Kaiserswerth -> Langst" to listOf(GeoPoint(51.3020, 6.7370), GeoPoint(51.2935, 6.7105)),
    )
    var fails = 0
    for ((name, pts) in cases) for (a in listOf(Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.MTB, Activity.HIKE)) {
        val line = try {
            val r = eng.route(pts, a, 2)
            val spots = RouteWarnings.detect(r.points, a, 2)
            val ok = spots.any { it.kind == SpotKind.FERRY }
            if (!ok) fails++
            String.format("%s %5.1f km  %s", if (ok) "ok  " else "FAIL", r.distanceM / 1000, RouteWarnings.text(spots, 2) ?: "(no hint)")
        } catch (e: RoutingException) {
            fails++
            "FAIL no route: ${e.message}"
        }
        println(String.format("%-24s %-14s %s", name, a, line))
    }
    println(if (fails == 0) "FerryHintTest: all routes over a ferry show the hint" else "FerryHintTest: $fails FAILED")
    if (fails > 0) kotlin.system.exitProcess(1)
}

import com.onpath.app.routing.*
import java.io.File
import kotlin.system.exitProcess

// A point that BRouter snaps onto a way without a connection to the rest of the network ("island") must still get a
// route: the app moves it to the next way (KTTEST=.../IslandTest.kt run.sh <segmentsDir with E5_N50.rd5>).
// Field case 2026-10-26: Köln Dom → Neumarkt (~1 km) gave "no route" for every activity. A point in the middle of the
// cathedral is 38 m from a short footway inside it that connects to nothing BRouter knows; BRouter's own island retry
// snaps to the same way five times ("no track found"), as a destination it gives up at once ("target island").

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
    val dom = GeoPoint(50.9413, 6.9583)
    val neumarkt = GeoPoint(50.9360, 6.9475)
    val roncalli = GeoPoint(50.9405, 6.9585) // on the square south of the Dom (not an island)
    var failures = 0
    for (a in Activity.values()) for ((name, wps) in listOf(
        "Dom -> Neumarkt" to listOf(dom, neumarkt),
        "Neumarkt -> Dom" to listOf(neumarkt, dom),
        "Neumarkt -> Dom -> Roncalliplatz" to listOf(neumarkt, dom, roncalli),
    )) {
        try {
            val r = eng.route(wps, a, 2)
            val domAt = r.matched.getOrNull(wps.indexOf(dom))
            val off = domAt?.let { WaypointSnap.distM(dom, it) }
            val ok = r.distanceM in 500.0..4000.0 && (off == null || off < 150.0)
            if (!ok) failures++
            println(String.format("%-4s %-34s %-14s %6.0f m  Dom snapped %s m away (%s)", if (ok) "OK" else "FAIL", name, a,
                r.distanceM, off?.let { "%.0f".format(it) } ?: "?", f(domAt)))
        } catch (e: RoutingException) {
            failures++
            println(String.format("FAIL %-34s %-14s %s", name, a, e.message))
        }
    }
    println(if (failures == 0) "IslandTest: all OK" else "IslandTest: $failures FAILED")
    if (failures > 0) exitProcess(1)
}

import com.onpath.app.routing.*
import java.io.File

// Routing on a partial rd5 (only some 1° parts present, the others empty – docs/design/map-data-size.md §6).
// Run: KTTEST=tools/routing/jvm-test/PartialTest.kt tools/routing/jvm-test/run.sh <dir with a (partial) E10_N45.rd5>
// Parts of E10_N45: index = (lon-10)*5 + (lat-45); Bozen/Meran = 6 (11°E 46°N), Ultental west of 11°E = 1.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val seg = File("/home/user/build/routing-test/segments")
    println("integrity: " + (btools.mapaccess.PhysicalFile.checkFileIntegrity(File(seg, "E10_N45.rd5")) ?: "ok"))
    val eng = BRouterEngine(FakeCtx(files), seg)
    val cases = listOf(
        "part 6 only: Bozen->Meran" to listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500)),
        "part 1 only: Ultental St.Walburg->St.Gertraud" to listOf(GeoPoint(46.5530, 10.9890), GeoPoint(46.5190, 10.9230)),
        "6 -> 1: Lana->Zoggler Stausee" to listOf(GeoPoint(46.6110, 11.1580), GeoPoint(46.5490, 10.9610)),
        "inside 6 near the 11°E edge: St.Pankraz" to listOf(GeoPoint(46.5850, 11.0870), GeoPoint(46.5700, 11.0200)),
    )
    for ((name, wps) in cases) {
        val t0 = System.currentTimeMillis()
        try {
            val r = eng.route(wps, Activity.HIKE, 3)
            println(String.format("%-48s OK %.2f km, %d pts, %d ms", name, r.distanceM / 1000, r.points.size, System.currentTimeMillis() - t0))
        } catch (e: RoutingException) {
            println(String.format("%-48s RoutingException (%d ms): %s", name, System.currentTimeMillis() - t0, e.message))
        }
    }
}

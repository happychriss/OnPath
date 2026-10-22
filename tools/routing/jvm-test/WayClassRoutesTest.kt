import com.onpath.app.routing.*
import java.io.File

// Routes for the router-class consistency check (tools/routing/wayclass/consistency.py runs this):
//   WC_REQ=<requests> WC_OUT=<routes> KTTEST=.../WayClassRoutesTest.kt run.sh <segmentsDir>
// Request lines: "name ACTIVITY sac lat1 lon1 lat2 lon2". Output: "R name" + one "lat lon" line per route point, or
// "E name message" when there is no route (e.g. both ends only on forbidden ways).

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val req = File(System.getenv("WC_REQ") ?: error("WC_REQ missing")).readLines().filter { it.isNotBlank() }
    File(System.getenv("WC_OUT") ?: error("WC_OUT missing")).bufferedWriter().use { out ->
        var ok = 0
        for (line in req) {
            val c = line.trim().split(Regex("\\s+"))
            val name = c[0]
            val act = Activity.valueOf(c[1])
            val sac = c[2].toInt()
            val pts = listOf(GeoPoint(c[3].toDouble(), c[4].toDouble()), GeoPoint(c[5].toDouble(), c[6].toDouble()))
            try {
                val r = eng.route(pts, act, sac)
                out.write("R $name\n")
                for (p in r.points) out.write("${p.lat} ${p.lon} ${p.way?.code() ?: ""}\n")
                ok++
            } catch (e: RoutingException) {
                out.write("E $name ${e.message?.replace('\n', ' ')}\n")
            }
        }
        println("routed $ok of ${req.size}")
    }
}

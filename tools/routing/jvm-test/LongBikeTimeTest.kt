import com.onpath.app.data.Geo
import com.onpath.app.routing.*
import java.io.File

// How long a ~100 km Bike plan takes (owner 2026-11-04, "long bike routes faster", A – step 1: measure):
//   KTTEST=.../LongBikeTimeTest.kt JAVA_XMX=512m run.sh <segmentsDir with E10_N45.rd5>
// Bozen → Meran → Schlanders → Mals → Reschen (Vinschgau cycle way), Bike (touring.brf). Prints: the whole plan with
// the Planner (fresh engine = cold, then warm), each leg alone, and an edit at the end (the last point moved 300 m) –
// what a leg cache (route point → point) would save: only the changed leg is routed again.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

val WPS = listOf(
    GeoPoint(46.4983, 11.3548), GeoPoint(46.6713, 11.1597), GeoPoint(46.6273, 10.7737), GeoPoint(46.6890, 10.5470),
    GeoPoint(46.8340, 10.5100),
)

fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val seg = File("/home/user/build/routing-test/segments")
    fun engine() = BRouterEngine(FakeCtx(files), seg)
    val act = Activity.BIKE_TOURING
    val e = engine()
    repeat(3) { run ->
        val p = Planner(e)
        val t0 = System.nanoTime()
        val r = p.plan(WPS, act, 2)
        println("   whole plan run ${run + 1}: %d ms, %.1f km, error=%s, tries: %s".format(ms(t0), r.distanceM / 1000, r.error, p.describeTries()))
    }
    var sum = 0L
    for (i in 0 until WPS.size - 1) {
        val t0 = System.nanoTime()
        val r = e.route(listOf(WPS[i], WPS[i + 1]), act, 2)
        val t = ms(t0); sum += t
        println("   leg ${i + 1}→${i + 2}: %d ms, %.1f km".format(t, Geo.length(r.points) / 1000))
    }
    println("   legs alone: %d ms in all".format(sum))
    val moved = WPS.dropLast(1) + GeoPoint(WPS.last().lat + 0.0027, WPS.last().lon)
    val t0 = System.nanoTime()
    val r2 = Planner(e).plan(moved, act, 2)
    println("   last point moved 300 m, whole plan again: %d ms, %.1f km".format(ms(t0), r2.distanceM / 1000))
    val t1 = System.nanoTime()
    e.route(listOf(moved[moved.size - 2], moved.last()), act, 2)
    println("   the same edit, only the changed leg: %d ms".format(ms(t1)))
}

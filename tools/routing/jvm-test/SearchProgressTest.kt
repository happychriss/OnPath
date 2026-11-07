import com.onpath.app.routing.*
import java.io.File
import kotlin.system.exitProcess

// The wait bar within one long leg (owner 2026-11-05): BRouterEngine.searchProgress polled from another thread while
// the owner's 98 km Ruhr leg is routed (needs E5_N50.rd5), pass/fail:
//   KTTEST=.../SearchProgressTest.kt run.sh <segmentsDir>
// It must give values for the searching thread only, climb (the guide pass to 20 %, the exact pass on to 100 %) and be
// past 80 % before the route is done; after the search it is null again.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

var failures = 0
fun check(ok: Boolean, what: String) { println((if (ok) "   ok   " else "   FAIL ") + what); if (!ok) failures++ }

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val e = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val pts = listOf(GeoPoint(51.1686, 6.9390), GeoPoint(51.7385, 7.5234))
    var worker: Thread? = null
    val seen = ArrayList<Pair<Long, Double>>()
    var other = false
    val t0 = System.nanoTime()
    val routing = Thread {
        e.route(pts, Activity.BIKE_TOURING, 2)
    }.also { worker = it }
    routing.start()
    while (routing.isAlive) {
        e.searchProgress(worker!!)?.let { seen += (System.nanoTime() - t0) / 1_000_000 to it }
        if (e.searchProgress(Thread.currentThread()) != null) other = true
        Thread.sleep(100)
    }
    val doneMs = (System.nanoTime() - t0) / 1_000_000
    seen.forEach { (t, v) -> println("   %5d ms  %.2f".format(t, v)) }
    println("   route done after $doneMs ms")
    check(seen.size >= 5, "values while the search runs (${seen.size})")
    check(!other, "no value for another thread")
    var best = 0.0; var back = 0.0
    for ((_, v) in seen) { if (v < best) back = maxOf(back, best - v); best = maxOf(best, v) }
    check(best >= 0.8, "past 80 %% before the route is done (%.2f)".format(best))
    check(seen.first().second < 0.3, "starts low (%.2f)".format(seen.first().second))
    println("   largest step back between two polls: %.2f (the bar keeps its highest value – WaitBar)".format(back))
    check(e.searchProgress(worker!!) == null, "null after the search")
    println(if (failures == 0) "SearchProgressTest: all OK" else "SearchProgressTest: $failures FAILED")
    if (failures > 0) exitProcess(1)
}

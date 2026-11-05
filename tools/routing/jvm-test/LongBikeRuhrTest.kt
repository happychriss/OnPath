import com.onpath.app.data.Geo
import com.onpath.app.routing.*
import java.io.File

// Why a ~100 km Bike plan is slow (owner 2026-11-05, "long bike routes faster", phase 1 – analysis):
//   KTTEST=.../LongBikeRuhrTest.kt run.sh <segmentsDir with E5_N50.rd5>
// The owner's field case 2026-11-04: Ruhr → Schloss Nordkirchen, 75 km straight, ~98 km routed, Bike (touring.brf); the
// Pixel took 14.3 s for 2 points, then 18.4 s / 15.4 s for each point set in between (the whole plan again).
// Prints per step: the Planner time (one request for the whole plan, as the app does) and what routing only the new
// legs would cost (a leg cache). Env: MEMCLASS=<MB> is patched into the engine by run.sh (BRouter's node cache limit,
// default 64), STEPS=quick skips the per-leg part.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

// Hilden → Schloss Nordkirchen (75 km straight); A = a point about a third of the way (Essen-Kupferdreh area),
// B = a point between A and the goal (Dortmund-Hörde area), C = a point between A and B (Bochum area).
val START = GeoPoint(51.1686, 6.9390)
val GOAL = GeoPoint(51.7385, 7.5234)
val A = GeoPoint(51.3930, 7.0870)
val B = GeoPoint(51.4930, 7.4560)
val C = GeoPoint(51.4500, 7.2560)

fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val seg = File("/home/user/build/routing-test/segments")
    val e = BRouterEngine(FakeCtx(files), seg)
    val act = Activity.BIKE_TOURING
    val rt = Runtime.getRuntime()
    fun heap() = "heap %d/%d MB".format((rt.totalMemory() - rt.freeMemory()) / 1_048_576, rt.maxMemory() / 1_048_576)

    val steps = listOf(
        "2 points (Hilden → Nordkirchen)" to listOf(START, GOAL),
        "3 points (+A)" to listOf(START, A, GOAL),
        "4 points (+B between A and goal)" to listOf(START, A, B, GOAL),
        "5 points (+C between A and B)" to listOf(START, A, C, B, GOAL),
    )
    val done = HashSet<Pair<GeoPoint, GeoPoint>>() // legs a leg cache would already hold
    for ((name, pts) in steps) {
        val p = Planner(e)
        val t0 = System.nanoTime()
        val r = p.plan(pts, act, 2)
        val whole = ms(t0)
        println("== $name: whole plan %d ms, %.1f km, error=%s, tries: %s, %s".format(whole, r.distanceM / 1000, r.error, p.describeTries(), heap()))
        if (System.getenv("STEPS") == "quick") continue
        // what a leg cache would route: only legs not routed before (each leg its own 2-point request)
        var newLegs = 0L; var allLegs = 0L; val parts = ArrayList<String>()
        for (i in 0 until pts.size - 1) {
            val leg = pts[i] to pts[i + 1]
            val t1 = System.nanoTime()
            val lr = e.route(listOf(leg.first, leg.second), act, 2)
            val t = ms(t1)
            allLegs += t
            val fresh = leg !in done
            if (fresh) newLegs += t
            done += leg
            parts += "%d→%d %.0f km %d ms%s".format(i + 1, i + 2, lr.distanceM / 1000, t, if (fresh) " (new)" else " (cache)")
        }
        println("   legs: ${parts.joinToString("; ")} – new legs only: $newLegs ms")
    }
    // the same 2-point request in a fresh engine (BRouter builds its node cache from the rd5 again every request)
    val e2 = BRouterEngine(FakeCtx(files), seg)
    val moved = GeoPoint(GOAL.lat + 0.0027, GOAL.lon)
    var t0 = System.nanoTime(); e2.route(listOf(START, moved), act, 2)
    println("== 2 points, goal moved 300 m, warm JVM: %d ms, %s".format(ms(t0), heap()))
    t0 = System.nanoTime(); e2.route(listOf(START, GeoPoint(GOAL.lat + 0.0054, GOAL.lon)), act, 2)
    println("== 2 points, goal moved 600 m, warm JVM: %d ms, %s".format(ms(t0), heap()))
}

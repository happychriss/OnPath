import com.onpath.app.data.LegFiles
import com.onpath.app.routing.*
import java.io.File

// Saved routes load without routing again (owner 2026-11-07, docs/design/long-bike-routes.md "Saved routes"):
//   KTTEST=.../StoredLegsTest.kt EXTRA_KT="app/src/main/java/com/onpath/app/data/LegFiles.kt
//     app/src/main/java/com/onpath/app/data/AtomicFiles.kt app/src/main/java/com/onpath/app/routing/StoredLine.kt"
//     JAVA_XMX=1g run.sh <segmentsDir with E5_N50.rd5>
// The owner's ~98 km bike plan Hilden → Nordkirchen in 4 legs: the load before (every leg routed, as after an app
// restart) and after (a new process – an empty leg cache in memory – with the phone's leg store). Pass/fail: the
// loaded line is the routed one and no leg is routed.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

val PLAN = listOf(GeoPoint(51.1686, 6.9390), GeoPoint(51.3930, 7.0870), GeoPoint(51.4500, 7.2560),
    GeoPoint(51.4930, 7.4560), GeoPoint(51.7385, 7.5234))

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val work = File("/home/user/build/routing-test/kt-files"); work.mkdirs()
    val engine = BRouterEngine(FakeCtx(work), File("/home/user/build/routing-test/segments"))
    val legsDir = File(work, "legs").apply { deleteRecursively() }
    fun store() = LegFiles(legsDir, { "r1-0123456789ab" }, { "1" })
    val act = Activity.BIKE_TOURING
    var t0 = System.nanoTime()
    val p1 = Planner(engine, legs = LegCache(store = store()))
    val first = p1.plan(PLAN, act, 2)
    val before = (System.nanoTime() - t0) / 1_000_000
    println("before (every leg routed): $before ms, ${"%.1f".format(first.distanceM / 1000)} km, ${first.points.size} line points; ${p1.describeTries()}")
    val bytes = legsDir.listFiles()!!.sumOf { it.length() }
    println("leg store: ${legsDir.listFiles()!!.size} files, ${bytes / 1024} KB")
    t0 = System.nanoTime()
    val p2 = Planner(engine, legs = LegCache(store = store())) // a new process: empty memory, the same files
    val again = p2.plan(PLAN, act, 2)
    val after = (System.nanoTime() - t0) / 1_000_000
    println("after (a restart, legs from the store): $after ms; ${p2.describeTries()}")
    val ok = p2.tries.isEmpty() && again == first && again.points.map { it.way } == first.points.map { it.way }
    println(if (ok) "PASS: the same line, no router run (${before} ms → ${after} ms)" else "FAIL")
    if (!ok) System.exit(1)
}

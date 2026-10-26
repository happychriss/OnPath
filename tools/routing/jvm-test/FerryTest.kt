import com.onpath.app.routing.*
import java.io.File

// Ferries a bike may not use (bicycle=no) or a hiker (foot=no) must never be in a route (owner standard: no surprises
// for real bikers). KTTEST=.../FerryTest.kt RAW_TAP=1 FERRY_CASES=<file> run.sh <segmentsDir>
// FERRY_CASES: one ferry per line "osmId;name;tags;lat,lon;lat,lon;lengthM" – the two points lie 250 m beyond the
// ferry's ends (made from an Overpass export of route=ferry + bicycle=no / foot=no; not kept in the repo).
// Prints per ferry and activity whether the route used a forbidden ferry; FERRY_ACTS=BIKE_TREKKING,... limits the
// activities. Exit code 1 when any route used a forbidden ferry.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun pt(s: String): GeoPoint { val (a, b) = s.split(","); return GeoPoint(a.toDouble(), b.toDouble()) }

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val cases = File(System.getenv("FERRY_CASES") ?: error("FERRY_CASES not set")).readLines().filter { it.isNotBlank() }
    val acts = System.getenv("FERRY_ACTS")?.split(",")?.map { Activity.valueOf(it) }
        ?: listOf(Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.MTB, Activity.HIKE)
    var messages: List<String?> = emptyList()
    RawTap.sink = { _, _, m -> messages = m }
    val bad = HashMap<Activity, Int>(); val ok = HashMap<Activity, Int>(); val fail = HashMap<Activity, Int>()
    val ferryOk = HashMap<Activity, Int>()
    for (c in cases) {
        val f = c.split(";")
        val (id, name, tags) = Triple(f[0], f[1], f[2])
        val forbiddenFor = { a: Activity -> if (a == Activity.HIKE) "foot=no" in tags.split(" ") else "bicycle=no" in tags.split(" ") }
        for (a in acts) {
            if (!forbiddenFor(a) && a != Activity.HIKE) continue // only bike-forbidden ferries for bikes; hikers: all
            messages = emptyList()
            val line = try {
                val r = eng.route(listOf(pt(f[3]), pt(f[4])), a, 3)
                val ferries = messages.filterNotNull().map { it.split(Regex("\\s+")).toSet() }.filter { "route=ferry" in it }
                val usedBad = ferries.any { t -> if (a == Activity.HIKE) "foot=no" in t else "bicycle=no" in t }
                if (usedBad) bad.merge(a, 1, Int::plus) else ok.merge(a, 1, Int::plus)
                if (ferries.isNotEmpty() && !usedBad) ferryOk.merge(a, 1, Int::plus)
                String.format("%s %6.0f m%s", if (usedBad) "BAD " else "ok  ", r.distanceM, if (ferries.isNotEmpty()) "  ferry: " +
                    ferries.joinToString(" | ") { t -> t.filter { it.startsWith("bicycle=") || it.startsWith("foot=") }.joinToString(" ") } else "")
            } catch (e: RoutingException) {
                fail.merge(a, 1, Int::plus)
                "none " + e.message
            }
            println(String.format("%-11s %-36s %-13s %-40s %s", id, name.take(36), a, tags.take(40), line))
            if (System.getenv("FERRY_FULL") != null) messages.filterNotNull().forEach { println("      $it") }
        }
    }
    for (a in acts) println(String.format("SUMMARY %-13s routes %3d  forbidden ferry used %3d  allowed ferry used %3d  no route %3d",
        a, (ok[a] ?: 0) + (bad[a] ?: 0), bad[a] ?: 0, ferryOk[a] ?: 0, fail[a] ?: 0))
    if (bad.values.sum() > 0) kotlin.system.exitProcess(1)
}

import com.onpath.app.data.Geo
import com.onpath.app.navigation.JunctionCues
import com.onpath.app.routing.*
import java.io.File

/**
 * Bike junction vibration on real town routes: BRouter's hints with the junction's branches, and how many cues a ride
 * at a steady speed gets ("0.11" = 80 m before every junction, "0.12" = the same without slight bends, "after" =
 * JunctionCues.bike() + CueRule at 14 / 20 / 25 km/h).
 * Run: KTTEST=.../BikeCuesTest.kt run.sh <segmentsDir with E5_N50.rd5> (DUMP=1 lists every hint). Report only, no pass / fail.
 */
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
        Triple("Hilden -> Schloss Benrath", listOf(GeoPoint(51.16923, 6.92560), GeoPoint(51.16136, 6.87100)), Activity.BIKE_TOURING),
        Triple("Hilden -> Schloss Benrath", listOf(GeoPoint(51.16923, 6.92560), GeoPoint(51.16136, 6.87100)), Activity.BIKE_TREKKING),
        Triple("Hilden -> Schloss Benrath", listOf(GeoPoint(51.16923, 6.92560), GeoPoint(51.16136, 6.87100)), Activity.MTB),
        Triple("Hilden -> Haan", listOf(GeoPoint(51.16923, 6.92560), GeoPoint(51.19180, 7.01290)), Activity.BIKE_TOURING),
        Triple("Hilden -> Langenfeld", listOf(GeoPoint(51.16923, 6.92560), GeoPoint(51.10990, 6.94820)), Activity.BIKE_TOURING),
    )
    val dump = System.getenv("DUMP") != null
    for ((name, wps, act) in cases) {
        val r = try { eng.route(wps, act, 3) } catch (e: Exception) { println("$name: ${e.message}"); continue }
        val cum = Geo.cumulative(r.points)
        if (dump) {
            println("== $name $act")
            r.points.forEachIndexed { i, p ->
                val t = p.turn ?: return@forEachIndexed
                val s = p.shape
                println(String.format("%6.0f m  %-5s ang %6.1f  %s  -> %s", cum[i], t.code, p.turnAngle, s?.toString() ?: "-",
                    if (s == null) "?" else (if (!com.onpath.app.navigation.CueRule.needed(t, s)) "quiet" else "CUE")))
            }
        }
        val km = r.distanceM / 1000
        val speeds = listOf(4.0, 5.5, 7.0) // 14, 20, 25 km/h
        val sb = StringBuilder()
        // before: fixed 80 m, every junction (up to 0.11) / slight bends skipped (0.12)
        val all = JunctionCues(80.0).let { c -> (0..r.distanceM.toInt() step 5).mapNotNull { m -> c.update(r.points, 1, m.toDouble(), true) } }
        val nBefore = all.count { it != JunctionCue.SLIGHT_LEFT && it != JunctionCue.SLIGHT_RIGHT }
        sb.append(String.format("0.11 %3d  0.12 %3d", all.size, nBefore))
        for (v in speeds) {
            val c = JunctionCues.bike()
            val n = (0..r.distanceM.toInt() step 5).count { m -> c.update(r.points, 1, m.toDouble(), true, v) != null }
            sb.append(String.format("  after@%.0fkmh %3d", v * 3.6, n))
        }
        val junctions = r.points.count { it.turn != null }
        val quiet = r.points.count { p -> p.turn != null && !com.onpath.app.navigation.CueRule.needed(p.turn, p.shape) }
        val noShape = r.points.count { it.turn != null && it.shape == null }
        println(String.format("%-28s %-13s %5.2f km  %3.0f min@20  hints %3d (quiet %2d, no shape %d)  %s", name, act, km,
            km / 20 * 60, junctions, quiet, noShape, sb))
    }
}

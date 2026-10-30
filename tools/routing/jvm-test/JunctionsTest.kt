import com.onpath.app.data.Geo
import com.onpath.app.navigation.CueRule
import com.onpath.app.navigation.JunctionCues
import com.onpath.app.routing.*
import java.io.File

/**
 * Junction vibration on real routes: BRouter's turn instructions per route, and what JunctionCues would vibrate when
 * walking / riding the route. Run: KTTEST=JunctionsTest.kt run.sh <segmentsDir with E10_N45.rd5> (DUMP=1 lists every
 * hint with its ways and the rule's answer).
 * "old" = the hike rule up to 2026-10-30 (every junction, 40 m before, none within 50 m of the junction before it);
 * "worth" = junctions CueRule calls worth a cue (on foot / on the bike); "felt" = JunctionCues at two steady speeds
 * (hike 3 / 5 km/h, bike 14 / 20 km/h; owner 2026-10-30).
 * Fails (exit 1) when a real route gets no junction at all or a cue density that would annoy (> 6 per km on foot).
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
        Triple("Meran Obermais -> Tappeinerweg", listOf(GeoPoint(46.6714, 11.1700), GeoPoint(46.6790, 11.1500)), Activity.HIKE),
        Triple("Algund -> Vellau", listOf(GeoPoint(46.6830, 11.1300), GeoPoint(46.6860, 11.1090)), Activity.HIKE),
        Triple("Meran2000 -> Ifinger hut", listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.6995, 11.2430)), Activity.HIKE),
        Triple("Meran -> Dorf Tirol", listOf(GeoPoint(46.6714, 11.1600), GeoPoint(46.6905, 11.1520)), Activity.HIKE),
        Triple("Meran town (bike)", listOf(GeoPoint(46.6714, 11.1700), GeoPoint(46.6650, 11.1450)), Activity.BIKE_TREKKING),
        Triple("Bozen -> Meran (bike)", listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500)), Activity.BIKE_TOURING),
    )
    val dump = System.getenv("DUMP") != null
    var bad = false
    for ((name, wps, act) in cases) {
        val r = try { eng.route(wps, act, 3) } catch (e: Exception) { println("$name: ${e.message}"); continue }
        val hike = act == Activity.HIKE
        val cum = Geo.cumulative(r.points)
        val turns = r.points.indices.filter { r.points[it].turn != null }
        if (dump) {
            println("== $name $act")
            turns.forEach { i ->
                val p = r.points[i]; val t = p.turn!!
                println(String.format("%6.0f m  %-5s ang %6.1f  %-28s -> %s", cum[i], t.code, p.turnAngle, p.shape?.encode() ?: "-",
                    if (CueRule.needed(t, p.shape, hike)) "CUE" else "quiet"))
            }
        }
        val len = r.distanceM
        var prev = Double.NEGATIVE_INFINITY
        val old = turns.count { i -> (cum[i] - prev >= JunctionCues.MERGE_M).also { prev = cum[i] } }
        val worth = turns.count { i -> CueRule.needed(r.points[i].turn!!, r.points[i].shape, hike) }
        fun felt(speed: Double): List<JunctionCue> {
            val c = JunctionCues().apply { bike = !hike }
            return (0..len.toInt() step 5).mapNotNull { m -> c.update(r.points, 1, m.toDouble(), true, speed) }
        }
        val speeds = if (hike) listOf(0.83, 1.39) else listOf(3.9, 5.56)
        val f = speeds.map { felt(it) }
        val perKm = f.last().size / (len / 1000)
        println(String.format("%-32s %-13s %6.2f km  junctions %3d  old %3d  worth %3d  felt@%.0f/%.0fkmh %3d / %3d  (%.1f/km)  %s",
            name, act, len / 1000, turns.size, old, worth, speeds[0] * 3.6, speeds[1] * 3.6, f[0].size, f[1].size, perKm,
            f.last().take(12).joinToString(" ") { it.code }))
        if (turns.isEmpty()) { println("   FAIL: no junction instructions"); bad = true }
        if (hike && perKm > 6) { println("   FAIL: too many cues per km on foot"); bad = true }
    }
    if (bad) System.exit(1)
}

import com.onpath.app.navigation.JunctionCues
import com.onpath.app.routing.*
import java.io.File

/**
 * Junction vibration on real routes: BRouter's turn instructions per route, and what JunctionCues
 * would vibrate when walking the route (merged switchbacks). Run: KTTEST=JunctionsTest.kt run.sh <segmentsDir>
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
        Triple("Meran town (bike)", listOf(GeoPoint(46.6714, 11.1700), GeoPoint(46.6650, 11.1450)), Activity.BIKE_TREKKING),
        Triple("Bozen -> Meran (bike)", listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500)), Activity.BIKE_TOURING),
    )
    var bad = false
    for ((name, wps, act) in cases) {
        val r = eng.route(wps, act, 3)
        val turns = r.points.withIndex().filter { it.value.turn != null }
        val cues = if (act == Activity.HIKE) JunctionCues() else JunctionCues.bike()
        val len = r.distanceM
        val felt = (0..len.toInt() step 5).mapNotNull { m -> cues.update(r.points, 1, m.toDouble(), true) }
        val perKm = felt.size / (len / 1000)
        println(String.format("%-32s %-13s %6.2f km  junctions %3d  felt %3d  (%.1f/km)  %s", name, act, len / 1000, turns.size,
            felt.size, perKm, felt.take(12).joinToString(" ") { it.code }))
        if (turns.isEmpty()) { println("   FAIL: no junction instructions"); bad = true }
        if (act == Activity.HIKE && perKm > 6) { println("   FAIL: too many cues per km on foot"); bad = true }
    }
    if (bad) System.exit(1)
}

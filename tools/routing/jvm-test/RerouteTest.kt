import com.onpath.app.routing.*
import java.io.File
import kotlin.math.*

// Auto re-routing experiment (docs/plan-auto-reroute.md): how long does BRouter on the JVM take to route from a
// position off the planned route (a) back onto the route a little ahead ("rejoin") vs. (b) to the destination,
// and how long are the results? Also: does BRouter's startDirection (heading) avoid a U-turn?
// Run: KTTEST=$PWD/tools/routing/jvm-test/RerouteTest.kt tools/routing/jvm-test/run.sh <segmentsDir>

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun cum(l: List<GeoPoint>): DoubleArray {
    val c = DoubleArray(l.size)
    for (i in 1 until l.size) c[i] = c[i - 1] + WaypointSnap.distM(l[i - 1], l[i])
    return c
}

fun at(l: List<GeoPoint>, c: DoubleArray, d: Double): Int = c.indexOfFirst { it >= d }.let { if (it < 0) l.lastIndex else it }

fun bearing(a: GeoPoint, b: GeoPoint): Double {
    val y = sin(Math.toRadians(b.lon - a.lon)) * cos(Math.toRadians(b.lat))
    val x = cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) -
        sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(Math.toRadians(b.lon - a.lon))
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

fun angDiff(a: Double, b: Double) = abs(((b - a + 540) % 360) - 180)

/** Point [m] metres from [p] in direction [deg]. */
fun offset(p: GeoPoint, deg: Double, m: Double): GeoPoint {
    val dLat = m * cos(Math.toRadians(deg)) / 111_320.0
    val dLon = m * sin(Math.toRadians(deg)) / (111_320.0 * cos(Math.toRadians(p.lat)))
    return GeoPoint(p.lat + dLat, p.lon + dLon)
}

/** Bearing of the first ~[m] metres of a line. */
fun startBearing(l: List<GeoPoint>, m: Double = 60.0): Double {
    val c = cum(l); return bearing(l[0], l[at(l, c, m)])
}

fun timed(eng: RoutingEngine, w: List<GeoPoint>, a: Activity, sac: Int = 3): Pair<RouteResult?, Long> {
    val t = System.nanoTime()
    val r = try { eng.route(w, a, sac) } catch (e: RoutingException) { println("      ! ${e.message}"); null }
    return r to (System.nanoTime() - t) / 1_000_000
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))

    data class Case(val name: String, val wps: List<GeoPoint>, val act: Activity)
    val cases = listOf(
        Case("Hike Algund->Schenna", listOf(GeoPoint(46.6830, 11.1300), GeoPoint(46.6890, 11.1870)), Activity.HIKE),
        Case("Hike Meran2000->Ifinger hut", listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.6995, 11.2430)), Activity.HIKE),
        Case("Trekking Bozen->Meran", listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500)), Activity.BIKE_TREKKING),
        Case("MTB Algund->Vellau", listOf(GeoPoint(46.6830, 11.1300), GeoPoint(46.6860, 11.1090)), Activity.MTB),
        Case("Trekking Bozen->Mals (long)", listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6880, 10.5470)), Activity.BIKE_TREKKING),
    )
    println("warm-up (loads rd5 + profiles) …")
    for (c in cases) println("  %-30s %6d ms".format(c.name, timed(eng, c.wps, c.act).second))

    println()
    println("%-30s %5s %5s | %-24s | %-24s | %-24s | %s".format("case", "at", "off", "rejoin +300 m (ms, m)",
        "rejoin +1 km (ms, m)", "to destination (ms, m)", "detour vs. plan / start angle"))
    val rejoinMs = mutableListOf<Long>(); val destMs = mutableListOf<Long>()
    for (c in cases) {
        val base = eng.route(c.wps, c.act, 3)
        val line = base.points; val cc = cum(line); val total = cc.last()
        for (frac in listOf(0.3, 0.6)) for (off in listOf(100.0, 250.0)) {
            val i = at(line, cc, total * frac)
            val dir = bearing(line[max(0, i - 3)], line[min(line.lastIndex, i + 3)])
            val pos = offset(line[i], dir + 90, off) // to the right of the walking direction
            val along = cc[i]
            fun target(ahead: Double) = line[at(line, cc, min(total, along + ahead))]
            val (r300, t300) = timed(eng, listOf(pos, target(300.0)), c.act)
            val (r1k, t1k) = timed(eng, listOf(pos, target(1000.0)), c.act)
            val (rd, td) = timed(eng, listOf(pos, line.last()), c.act)
            listOf(t300, t1k).forEach { rejoinMs += it }; destMs += td
            // "rejoin +1 km" total = leg + rest of the planned route after the target
            val rest1k = total - cc[at(line, cc, min(total, along + 1000.0))]
            val viaRejoin = r1k?.let { it.distanceM + rest1k }
            val ang = r1k?.let { angDiff(dir, startBearing(it.points)) }
            println("%-30s %4.0f%% %4.0fm | %6d ms %7.0f m       | %6d ms %7.0f m       | %6d ms %7.0f m       | rejoin=%s dest=%s rest=%.0f m, start %s°".format(
                c.name, frac * 100, off, t300, r300?.distanceM ?: -1.0, t1k, r1k?.distanceM ?: -1.0, td, rd?.distanceM ?: -1.0,
                viaRejoin?.let { "%.0f".format(it) } ?: "-", rd?.distanceM?.let { "%.0f".format(it) } ?: "-",
                total - along, ang?.let { "%.0f".format(it) } ?: "-"))
        }
    }
    fun stats(l: List<Long>) = if (l.isEmpty()) "-" else "median %d ms, max %d ms".format(l.sorted()[l.size / 2], l.max())
    println("\nrejoin legs: ${stats(rejoinMs)}   to destination: ${stats(destMs)}")

    // Unreachable rejoin target: how long until BRouter gives up (timeout path)?
    println("\nunreachable (hike SAC T1 limit, Meran 2000 -> Ifinger summit, ~2 km):")
    val (u, tu) = timed(eng, listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.7006, 11.2585)), Activity.HIKE, 1)
    println("   result=${u != null} after $tu ms")

    // startDirection: does a heading at the start avoid a U-turn? Direct BRouter call (profiles were copied by the engine).
    println("\nstartDirection experiment (direct BRouter call):")
    val profiles = File(files, "brouter/profiles")
    for ((act, prof) in listOf(Activity.BIKE_TREKKING to "trekking.brf", Activity.HIKE to "hiking-mountain.brf")) {
        val base = eng.route(cases[2].wps, act, 3)
        val line = base.points; val cc = cum(line)
        val i = at(line, cc, cc.last() * 0.5)
        val dir = bearing(line[max(0, i - 3)], line[min(line.lastIndex, i + 3)])
        val start = line[i]
        // we are ON the route but heading backwards (a U-turn would be needed to follow the plan)
        for (heading in listOf<Int?>(null, ((dir + 180) % 360).roundToInt(), dir.roundToInt())) {
            val rc = btools.router.RoutingContext()
            rc.localFunction = File(profiles, prof).absolutePath
            rc.keyValues = hashMapOf("turnInstructionMode" to "0")
            if (heading != null) { rc.startDirection = heading; rc.forceUseStartDirection = true }
            val nodes = listOf(start, line.last()).mapIndexed { k, p ->
                btools.router.OsmNodeNamed().apply {
                    name = if (k == 0) "from" else "to"
                    ilon = ((p.lon + 180.0) * 1e6 + 0.5).toInt(); ilat = ((p.lat + 90.0) * 1e6 + 0.5).toInt()
                }
            }
            val t = System.nanoTime()
            val e = btools.router.RoutingEngine(null, null, File("/home/user/build/routing-test/segments"), nodes, rc)
            e.quite = true; e.doRun(30_000)
            val ms = (System.nanoTime() - t) / 1_000_000
            val tr = e.foundTrack
            if (tr == null) { println("   $act heading=$heading: ${e.errorMessage}"); continue }
            val pts = tr.nodes.map { GeoPoint(it.getILat() / 1e6 - 90, it.getILon() / 1e6 - 180) }
            println("   %-14s heading=%-4s (route dir %3.0f°): %6.0f m, start bearing %3.0f°, %d ms".format(
                act, heading?.toString() ?: "none", dir, tr.distance.toDouble(), startBearing(pts), ms))
        }
    }
}

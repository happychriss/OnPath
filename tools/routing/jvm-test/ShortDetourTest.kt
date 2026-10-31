import com.onpath.app.data.Geo
import com.onpath.app.routing.*
import java.io.File
import kotlin.system.exitProcess

// Short-trip detour retry (owner 2026-10-31, Planner.retryShortDetours) with real BRouter data, pass/fail:
//   KTTEST=.../ShortDetourTest.kt run.sh <segmentsDir with E5_N50.rd5>
// Field case Hilden, Bike (touring.brf): 0.73 km straight, BRouter alone rides 2.05 km over signed cycle routes (the
// direct 1.07 km has 13 right-angle turns and a short pedestrian zone); the planner retries via the midpoint and takes
// the direct way. Also as the middle leg of a 3-point plan (the whole chunk is routed again with the extra via).
//
// AB_N=<n> (needs E10_N45.rd5 too for Meran; JAVA_XMX=1g): n random trips 0.4–2 km per region and activity (seed
// AB_SEED, default 42) plus n/2 random 3-point plans – the Planner without vs with the retry: how many change, by how much, and
// that none gets longer; pedestrian-zone / busy-road / T3+ metres before → after; how many retried legs a perpendicular
// offset of the via (± 25 % of the straight distance) would have shortened where the midpoint did not.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

var failures = 0
fun check(ok: Boolean, what: String) { println((if (ok) "   ok   " else "   FAIL ") + what); if (!ok) failures++ }

/** Metres of [line] whose way matches [p]. */
fun metres(line: List<GeoPoint>, p: (WayAttrs) -> Boolean): Double =
    (1 until line.size).sumOf { i -> val w = line[i - 1].way; if (w != null && p(w)) Geo.distance(line[i - 1], line[i]) else 0.0 }

fun pedZone(w: WayAttrs) = w.pedestrian && w.bicycle != "yes" && w.bicycle != "designated"
fun busy(w: WayAttrs) = w.wayType == "busy_road"
fun t3(w: WayAttrs) = w.sac >= 3
fun unpaved(w: WayAttrs) = w.surface in setOf("gravel", "unpaved", "ground", "natural_trail", "alpine")

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val act = Activity.BIKE_TOURING
    val a = GeoPoint(51.169243, 6.925344); val b = GeoPoint(51.169, 6.93578)

    val alone = eng.route(listOf(a, b), act, 2)
    println("   BRouter alone: %.0f m for %.0f m straight".format(alone.distanceM, Geo.distance(a, b)))
    check(alone.distanceM > 2.0 * Geo.distance(a, b), "BRouter alone rides the long detour (the field case; %.0f m)".format(alone.distanceM))
    val planner = Planner(eng)
    val p = planner.plan(listOf(a, b), act, 2)
    println("   Planner: %.0f m, tries: %s".format(p.distanceM, planner.describeTries()))
    check(p.distanceM <= 1300.0, "the planner takes the direct way, ≤ 1.3 km (%.0f m)".format(p.distanceM))
    check(p.error == null && p.legs.none { it.straight }, "routed, no error (${p.error})")
    check(p.snapped.size == 2 && p.skipped.isEmpty(), "the plan keeps its two points (snapped ${p.snapped.size}, skipped ${p.skipped})")
    check(planner.tries.size == 2, "one retry (${planner.tries.size} requests)")

    // the same leg in the middle of a 3-point plan: the whole chunk again with the extra via
    val x = GeoPoint(51.1735, 6.9160)
    val whole = eng.route(listOf(x, a, b), act, 2)
    val p3 = Planner(eng).plan(listOf(x, a, b), act, 2)
    println("   3 points: BRouter alone %.0f m, Planner %.0f m".format(whole.distanceM, p3.distanceM))
    check(p3.distanceM <= whole.distanceM - 600.0, "as the second leg of three points the detour goes too (%.0f → %.0f m)".format(whole.distanceM, p3.distanceM))
    check(p3.snapped.size == 3 && p3.skipped.isEmpty() && p3.error == null, "three points stay three, none skipped")

    // a short trip without a detour: one request
    val q = Planner(eng)
    q.plan(listOf(GeoPoint(51.1663, 6.9396), GeoPoint(51.1676, 6.9452)), act, 2)
    check(q.tries.size == 1, "no detour, no retry (${q.describeTries()})")

    System.getenv("AB_N")?.toIntOrNull()?.let { ab(eng, it) }
    println(if (failures == 0) "ShortDetourTest: all OK" else "ShortDetourTest: $failures FAILED")
    if (failures > 0) exitProcess(1)
}

fun ab(eng: BRouterEngine, n: Int) {
    val rnd = java.util.Random((System.getenv("AB_SEED") ?: "42").toLong())
    val hilden = doubleArrayOf(51.10, 6.75, 51.25, 7.10)  // Düsseldorf / Hilden / Solingen
    val meran = doubleArrayOf(46.62, 11.08, 46.72, 11.22)
    val runs = listOf(
        Triple("Hilden/Ddf", hilden, Activity.BIKE_TOURING), Triple("Hilden/Ddf", hilden, Activity.BIKE_TREKKING),
        Triple("Hilden/Ddf", hilden, Activity.HIKE), Triple("Meran", meran, Activity.BIKE_TOURING),
        Triple("Meran", meran, Activity.MTB), Triple("Meran", meran, Activity.HIKE))
    fun pt(bb: DoubleArray) = GeoPoint(bb[0] + rnd.nextDouble() * (bb[2] - bb[0]), bb[1] + rnd.nextDouble() * (bb[3] - bb[1]))
    fun near(p: GeoPoint, bb: DoubleArray): GeoPoint {
        while (true) { val q = pt(bb); val d = Geo.distance(p, q); if (d in 400.0..2000.0) return q }
    }
    println("\n   A/B: Planner without vs with the retry, random trips 0.4–2 km (seed ${System.getenv("AB_SEED") ?: "42"})")
    for ((region, bb, act) in runs) for (pts in listOf(2, 3)) {
        val count = if (pts == 2) n else n / 2
        var ok = 0; var changed = 0; var longer = 0; var sumBefore = 0.0; var sumAfter = 0.0; var savedMax = 0.0
        var candidates = 0; var offsetWouldHelp = 0
        var pzB = 0.0; var pzA = 0.0; var buB = 0.0; var buA = 0.0; var t3B = 0.0; var t3A = 0.0
        val savings = ArrayList<Double>()
        repeat(count) {
            val wps = ArrayList<GeoPoint>(); wps += pt(bb); repeat(pts - 1) { wps += near(wps.last(), bb) }
            val before = try { Planner(eng, shortDetourRetry = false).plan(wps, act, 2) } catch (e: RoutingException) { return@repeat }
            val after = try { Planner(eng).plan(wps, act, 2) } catch (e: RoutingException) { return@repeat }
            if (before.error != null || after.error != null) return@repeat
            ok++
            sumBefore += before.distanceM; sumAfter += after.distanceM
            val line = after.legs.flatMap { it.points }
            pzB += metres(before.points, ::pedZone); pzA += metres(line, ::pedZone)
            buB += metres(before.points, ::busy); buA += metres(line, ::busy)
            t3B += metres(before.points, ::t3); t3A += metres(line, ::t3)
            if (after.distanceM > before.distanceM + 1.0) { longer++; println("   LONGER: $act ${wps.joinToString(" ") { "%.5f,%.5f".format(it.lat, it.lon) }} %.0f → %.0f".format(before.distanceM, after.distanceM)) }
            if (before.distanceM - after.distanceM > 5.0) {
                changed++; val s = 1.0 - after.distanceM / before.distanceM; savings += s; savedMax = maxOf(savedMax, before.distanceM - after.distanceM)
                fun kinds(l: List<GeoPoint>) = l.zipWithNext().groupBy { it.first.way?.wayType ?: "?" }
                    .map { (k, v) -> k to v.sumOf { Geo.distance(it.first, it.second) } }.sortedByDescending { it.second }
                    .joinToString(" ") { "${it.first} %.0f".format(it.second) }
                println("   changed %s %.0f → %.0f m, unpaved %.0f → %.0f m, ascent %.0f → %.0f m  before [%s]  after [%s]".format(act,
                    before.distanceM, after.distanceM, metres(before.points, ::unpaved), metres(line, ::unpaved),
                    before.ascentM, after.ascentM, kinds(before.points), kinds(line)))
            } else if (pts == 2) {
                // a retried leg the midpoint did not shorten: would a perpendicular offset have?
                val st = Geo.distance(before.snapped.getOrNull(0) ?: wps[0], before.snapped.getOrNull(1) ?: wps[1])
                if (Planner.isShortDetour(st, Geo.length(before.points))) {
                    candidates++
                    val m = Planner.midpoint(wps[0], wps[1])
                    val dLat = wps[1].lat - wps[0].lat; val dLon = (wps[1].lon - wps[0].lon) * Math.cos(Math.toRadians(m.lat))
                    val helped = listOf(1.0, -1.0).any { sgn ->
                        val o = GeoPoint(m.lat + sgn * 0.25 * dLon, m.lon - sgn * 0.25 * dLat / Math.cos(Math.toRadians(m.lat)))
                        try { eng.route(listOf(wps[0], o, wps[1]), act, 2).distanceM <= 0.7 * Geo.length(before.points) } catch (e: RoutingException) { false }
                    }
                    if (helped) offsetWouldHelp++
                }
            }
        }
        savings.sort()
        println(("   %-10s %-13s %d pts: %3d routed, %3d shorter (%.1f %%), %d longer; total %.1f → %.1f km; saving median %.0f %%, " +
            "max %.0f %% / %.0f m; ped. zone %.0f → %.0f m, busy road %.0f → %.0f m, T3+ %.0f → %.0f m; midpoint kept: %d, offset would help %d").format(
            region, act, pts, ok, changed, 100.0 * changed / maxOf(ok, 1), longer, sumBefore / 1000, sumAfter / 1000,
            100 * (savings.getOrNull(savings.size / 2) ?: 0.0), 100 * (savings.lastOrNull() ?: 0.0), savedMax,
            pzB, pzA, buB, buA, t3B, t3A, candidates, offsetWouldHelp))
        if (longer > 0) failures++
    }
}

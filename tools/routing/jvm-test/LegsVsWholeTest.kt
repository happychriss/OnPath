import com.onpath.app.data.Geo
import com.onpath.app.routing.*
import java.io.File

// Leg by leg (PLANNER_VERSION 4, owner 2026-11-05) against the Planner before it (whole requests + cut-stop splice,
// compare/PlannerV3.kt) on real data – the check before switching (docs/design/long-bike-routes.md):
//   EXTRA_KT=tools/routing/jvm-test/compare/PlannerV3.kt KTTEST=.../LegsVsWholeTest.kt run.sh <dir with E5_N50.rd5>
// Random plans around the Ruhr / Rhineland (seeded): Bike 3–6 points 3–15 km apart, Hike 3–6 points 0.5–4 km apart.
// Per plan: length old / new, points the line misses (> 10 m), there-and-back pieces in the line, time. Env: PLANS=<plans>
// per activity (default 25), SEED. Pass: every length within 3 %, no point missed that the old line reached, no more
// there-and-back pieces than before.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

/** Points of [wps] the [line] misses by more than 10 m. */
fun missed(wps: List<GeoPoint>, line: List<GeoPoint>) = wps.indices.filter { WaypointSnap.distToLine(wps[it], line) > 10.0 }.toSet()

/** Pieces where the line goes back the way it came (a node visited, the next one = the one before): count, metres. */
fun backAndForth(line: List<GeoPoint>): Pair<Int, Double> {
    var n = 0; var m = 0.0
    var i = 1
    while (i < line.size - 1) {
        if (Geo.distance(line[i - 1], line[i + 1]) <= 0.5 && Geo.distance(line[i - 1], line[i]) > 0.5) {
            // walk out both ways while the line retraces itself
            var k = 1
            while (i - k - 1 >= 0 && i + k + 1 < line.size && Geo.distance(line[i - k - 1], line[i + k + 1]) <= 0.5) k++
            n++; m += Geo.length(line.subList(i - k, i + 1))
            i += k
        }
        i++
    }
    return n to m
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val seg = File("/home/user/build/routing-test/segments")
    val e = BRouterEngine(FakeCtx(files), seg)
    val n = System.getenv("PLANS")?.toInt() ?: 25
    val rnd = java.util.Random(System.getenv("SEED")?.toLong() ?: 20261105L)
    var bad = 0; var oldMs = 0L; var newMs = 0L
    val dev = ArrayList<Double>()
    for ((act, span) in listOf(Activity.BIKE_TOURING to (3_000.0 to 15_000.0), Activity.HIKE to (500.0 to 4_000.0))) {
        println("== ${act.name}")
        var plans = 0
        var tries = 0
        while (plans < n && tries < n * 4) {
            tries++
            val k = 3 + rnd.nextInt(4)
            val pts = ArrayList<GeoPoint>()
            pts += GeoPoint(51.10 + rnd.nextDouble() * 0.6, 6.75 + rnd.nextDouble() * 0.8)
            while (pts.size < k) {
                val d = span.first + rnd.nextDouble() * (span.second - span.first); val dir = rnd.nextDouble() * 2 * Math.PI
                val p = pts.last()
                pts += GeoPoint(p.lat + d * Math.cos(dir) / 111_195.0, p.lon + d * Math.sin(dir) / (111_195.0 * Math.cos(Math.toRadians(p.lat))))
            }
            val t0 = System.nanoTime()
            val old = try { PlannerV3(e).plan(pts, act, 2) } catch (x: Exception) { null }
            val tOld = ms(t0)
            if (old == null || old.error != null || old.legs.any { it.straight }) continue // a point off the network: no comparison
            val t1 = System.nanoTime()
            val new = Planner(e, legs = LegCache()).plan(pts, act, 2)
            val tNew = ms(t1)
            plans++; oldMs += tOld; newMs += tNew
            val pct = (new.distanceM - old.distanceM) / old.distanceM * 100
            dev += Math.abs(pct)
            val mo = missed(pts, old.points); val mn = missed(pts, new.points)
            val bo = backAndForth(old.points); val bn = backAndForth(new.points)
            val ok = Math.abs(pct) <= 3.0 && (mn - mo).isEmpty() && bn.first <= bo.first && new.error == null
            if (!ok) bad++
            println("%s %d pts: old %.2f km, new %.2f km (%+.2f %%), missed old %s new %s, back-and-forth old %d (%.0f m) new %d (%.0f m), %d / %d ms".format(
                if (ok) "  ok " else "  BAD", pts.size, old.distanceM / 1000, new.distanceM / 1000, pct, mo, mn, bo.first, bo.second,
                bn.first, bn.second, tOld, tNew) + if (ok) "" else "  " + pts.joinToString(" ") { "%.5f,%.5f".format(it.lat, it.lon) })
        }
    }
    dev.sort()
    println("== ${dev.size} plans: median |Δ length| %.2f %%, max %.2f %%; time old %d ms, new %d ms (each with an empty leg cache); %d not ok".format(
        dev[dev.size / 2], dev.last(), oldMs, newMs, bad))
    println(if (bad == 0) "PASS" else "FAIL")
}

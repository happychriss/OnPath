import com.onpath.app.data.Geo
import com.onpath.app.routing.*
import java.io.File
import java.util.Random
import kotlin.math.*

// Time-estimate experiment (docs/plan-time-estimate.md). No app code is changed by this file.
// Part 1: for real BRouter routes (Bozen / Meran data) compare the planning times we show today
//         (BRouter getTotalSeconds = Tobler for hiking / power model for bikes; Geo.estimateSeconds = DIN 33466 on the
//         whole route, used by Navigate "to go") with per-segment models (DIN per 50 m, Tobler, gradient table "B").
// Part 2: replay synthetic hikers with known personal speeds, fatigue, noise and breaks along the real hiking routes and
//         measure how well remaining-time estimators do at 25 / 50 / 75 % of the tour, and how jumpy they are.
// Run: KTTEST=$PWD/tools/routing/jvm-test/TimeTest.kt tools/routing/jvm-test/run.sh <segmentsDir>

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

// ---------------------------------------------------------------------------------------------------------------
// Route -> 50 m segments with DEM gradient

data class Seg(val lenM: Double, val dz: Double) { val grade get() = if (lenM > 0) dz / lenM else 0.0 }

const val STEP = 50.0

fun segments(points: List<GeoPoint>): List<Seg> {
    val c = Geo.cumulative(points)
    val total = c.last()
    var last = points.firstOrNull { it.ele != null }?.ele ?: 0.0
    val e = points.map { p -> (p.ele ?: last).also { last = it } }
    val n = max(1, (total / STEP).roundToInt())
    val step = total / n
    val ele = DoubleArray(n + 1)
    var k = 0
    for (i in 0..n) {
        val d = i * step
        while (k < c.size - 2 && c[k + 1] < d) k++
        val span = c[k + 1] - c[k]
        val t = if (span > 0) ((d - c[k]) / span).coerceIn(0.0, 1.0) else 0.0
        ele[i] = e[k] + (e[k + 1] - e[k]) * t
    }
    // light smoothing (3 samples = 150 m): DEM steps are not real ups and downs
    val sm = DoubleArray(n + 1) { i -> (ele[max(0, i - 1)] + ele[i] + ele[min(n, i + 1)]) / 3 }
    return (0 until n).map { Seg(step, sm[it + 1] - sm[it]) }
}

// ---------------------------------------------------------------------------------------------------------------
// Hiking models: seconds for one segment

/** DIN 33466 applied per segment: 4 km/h, 300 m/h up, 500 m/h down, larger + half of smaller. */
fun dinSeg(s: Seg): Double {
    val h = s.lenM / 4000.0
    val v = if (s.dz > 0) s.dz / 300.0 else -s.dz / 500.0
    return (max(h, v) + min(h, v) / 2) * 3600
}

/** Tobler's hiking function (6 km/h peak at -5 %), as BRouter uses it for foot profiles (without its incline smoothing). */
fun toblerSeg(s: Seg, peakKmh: Double = 6.0): Double = s.lenM / (peakKmh / 3.6 * exp(-3.5 * abs(s.grade + 0.05)))

/**
 * Option B: gradient table (minutes per horizontal km), linear in between. Anchors: 4 km/h on the flat (DIN / AVS
 * signposts), gentle descents a bit faster (Tobler, Langmuir, Wood 2023), steady climbs ~300-340 m/h, steep descents
 * ~500-550 m/h and slower again beyond -30 % (knees, steps).
 */
val TABLE_G = doubleArrayOf(-0.50, -0.40, -0.30, -0.20, -0.10, -0.05, 0.0, 0.05, 0.10, 0.15, 0.20, 0.30, 0.40, 0.50)
val TABLE_MIN_PER_KM = doubleArrayOf(62.0, 46.0, 32.0, 22.0, 13.5, 13.5, 15.0, 18.5, 24.0, 30.0, 37.0, 54.0, 70.0, 88.0)

fun tableMinPerKm(g: Double): Double {
    if (g <= TABLE_G.first()) return TABLE_MIN_PER_KM.first() * g / TABLE_G.first()
    if (g >= TABLE_G.last()) return TABLE_MIN_PER_KM.last() * g / TABLE_G.last()
    var i = 0
    while (TABLE_G[i + 1] < g) i++
    val t = (g - TABLE_G[i]) / (TABLE_G[i + 1] - TABLE_G[i])
    return TABLE_MIN_PER_KM[i] + (TABLE_MIN_PER_KM[i + 1] - TABLE_MIN_PER_KM[i]) * t
}

fun tableSeg(s: Seg): Double = tableMinPerKm(s.grade) * 60 * s.lenM / 1000

/** Gradient classes: steep down, down, flat, up, steep up. */
fun cls(g: Double) = when {
    g < -0.15 -> 0
    g < -0.05 -> 1
    g <= 0.05 -> 2
    g <= 0.15 -> 3
    else -> 4
}
val CLASS_NAMES = listOf("steep down", "down", "flat", "up", "steep up")
/** Learning groups: down (0, 1), flat (2), up (3, 4). */
fun grp(c: Int) = when (c) { 0, 1 -> 0; 2 -> 1; else -> 2 }

fun hm(s: Double) = "%d:%02d".format((s / 3600).toInt(), min(59, ((s % 3600) / 60).roundToInt()))
fun pct(a: Double, b: Double) = "%+4.0f%%".format((a / b - 1) * 100)

// ---------------------------------------------------------------------------------------------------------------

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))

    data class Case(val name: String, val wps: List<GeoPoint>, val act: Activity, val sac: Int = 3)
    val algund = GeoPoint(46.6830, 11.1300)
    val leiter = GeoPoint(46.6990, 11.0950)
    val hikes = listOf(
        Case("H1 Meran -> Lana (valley)", listOf(GeoPoint(46.6725, 11.1490), GeoPoint(46.6150, 11.1560)), Activity.HIKE),
        Case("H2 Algund -> Schenna (Waalweg)", listOf(algund, GeoPoint(46.6890, 11.1870)), Activity.HIKE),
        Case("H3 Algund -> Leiteralm (up)", listOf(algund, leiter), Activity.HIKE),
        Case("H4 Leiteralm -> Algund (down)", listOf(leiter, algund), Activity.HIKE),
        Case("H5 Algund -> Leiteralm -> Algund", listOf(algund, leiter, algund), Activity.HIKE),
        Case("H6 Meran2000 -> Ifinger hut", listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.6995, 11.2430)), Activity.HIKE),
        Case("H7 Lana -> Vigiljoch -> Lana", listOf(GeoPoint(46.6120, 11.1450), GeoPoint(46.6020, 11.1060), GeoPoint(46.6120, 11.1450)), Activity.HIKE),
        Case("H8 Saltaus -> Hirzer hut (up)", listOf(GeoPoint(46.7360, 11.2140), GeoPoint(46.7400, 11.2370)), Activity.HIKE, 4),
        Case("H8r Hirzer hut -> Saltaus (down)", listOf(GeoPoint(46.7400, 11.2370), GeoPoint(46.7360, 11.2140)), Activity.HIKE, 4),
        Case("H9 Meran2000 -> Ifinger summit (T4)", listOf(GeoPoint(46.6870, 11.2350), GeoPoint(46.7006, 11.2585)), Activity.HIKE, 5),
    )
    val bikes = listOf(
        Case("B1 Bozen -> Meran (valley)", listOf(GeoPoint(46.4970, 11.3580), GeoPoint(46.6717, 11.1500)), Activity.BIKE_TREKKING),
        Case("B2 Meran -> Schenna -> Meran", listOf(GeoPoint(46.6725, 11.1600), GeoPoint(46.6890, 11.1870), GeoPoint(46.6725, 11.1600)), Activity.BIKE_TREKKING),
        Case("B3 Lana -> Vigiljoch (MTB)", listOf(GeoPoint(46.6120, 11.1450), GeoPoint(46.6020, 11.1060)), Activity.MTB),
        Case("B4 Algund -> Vellau (MTB)", listOf(algund, GeoPoint(46.6860, 11.1090)), Activity.MTB),
    )

    println("== Part 1a: hiking - planning times (h:mm) ==")
    println("%-36s %5s %5s %5s %4s | %7s %7s | %7s %7s %7s | %s".format("route", "km", "up m", "dn m", "max%",
        "BRouter", "DIN all", "DIN seg", "Tobler", "table B", "plan sheet vs B / to-go vs B / to-go vs plan"))
    val routes = mutableListOf<Pair<String, List<Seg>>>()
    for (c in hikes) {
        val r = try { eng.route(c.wps, c.act, c.sac) } catch (e: RoutingException) { println("  ${c.name}: ${e.message}"); continue }
        val segs = segments(r.points)
        routes += c.name to segs
        val din = Geo.estimateSeconds(r.distanceM, r.ascentM, r.descentM, c.act)
        val dinSeg = segs.sumOf { dinSeg(it) }
        val tob = segs.sumOf { toblerSeg(it) }
        val tb = segs.sumOf { tableSeg(it) }
        val maxG = segs.maxOf { abs(it.grade) } * 100
        println("%-36s %5.1f %5.0f %5.0f %4.0f | %7s %7s | %7s %7s %7s | %s / %s / %s".format(c.name, r.distanceM / 1000,
            r.ascentM, r.descentM, maxG, hm(r.durationS), hm(din), hm(dinSeg), hm(tob), hm(tb),
            pct(r.durationS, tb), pct(din, tb), pct(din, r.durationS)))
        val byCls = DoubleArray(5); segs.forEach { byCls[cls(it.grade)] += it.lenM }
        println("      grade mix: " + CLASS_NAMES.indices.joinToString("  ") { "%s %.1f".format(CLASS_NAMES[it], byCls[it] / 1000) } +
            " km   surfaces: " + r.surfaces.take(4).joinToString(", ") { "%s %.1f".format(it.key, it.meters / 1000) })
    }

    println("\n== Part 1b: steady-grade speeds of the hiking models (horizontal km/h | vertical m/h) ==")
    println("%6s | %-14s | %-14s | %-14s".format("grade", "DIN per seg", "Tobler/BRouter", "table B"))
    for (gp in listOf(-40, -30, -20, -10, -5, 0, 5, 10, 20, 30, 40)) {
        val s = Seg(1000.0, gp * 10.0)
        fun f(sec: Double) = "%4.1f | %5.0f".format(3600 / sec, abs(gp * 10.0) * 3600 / sec)
        println("%5d%% | %-14s | %-14s | %-14s".format(gp, f(dinSeg(s)), f(toblerSeg(s)), f(tableSeg(s))))
    }

    println("\n== Part 1c: bikes - BRouter power model (plan sheet) vs. Geo.estimateSeconds (Navigate to-go) ==")
    for (c in bikes) for (act in if (c.act == Activity.MTB) listOf(Activity.MTB, Activity.EMTB) else listOf(Activity.BIKE_TREKKING, Activity.EBIKE, Activity.BIKE_TOURING)) {
        val r = try { eng.route(c.wps, act, 3) } catch (e: RoutingException) { println("  ${c.name}: ${e.message}"); continue }
        val geo = Geo.estimateSeconds(r.distanceM, r.ascentM, r.descentM, act)
        val segs = segments(r.points)
        val upKm = segs.filter { it.grade > 0.05 }.sumOf { it.lenM } / 1000
        println("%-30s %-13s %5.1f km ↑%4.0f m (%.1f km >5%%) | BRouter %s (%4.1f km/h) | Geo %s (%4.1f km/h) | %s".format(
            c.name, act.name, r.distanceM / 1000, r.ascentM, upKm, hm(r.durationS), r.distanceM / r.durationS * 3.6, hm(geo),
            r.distanceM / geo * 3.6, r.surfaces.take(3).joinToString(", ") { "%s %.1f".format(it.key, it.meters / 1000) }))
    }

    replay(routes)
}

// ---------------------------------------------------------------------------------------------------------------
// Part 2: replay

/** A synthetic hiker: true moving time per segment = table B x personal factor per class x fatigue x noise. */
data class Hiker(val name: String, val f: DoubleArray, val fatigue: Double, val breakRatio: Double)

/** One estimator: sees each passed segment with its real moving time, answers "remaining moving seconds". */
abstract class Estimator(val name: String) {
    abstract fun observe(s: Seg, movingS: Double)
    abstract fun remaining(rest: List<Seg>): Double
}

/** Today in Navigate: DIN 33466 on the whole rest of the route, never learns. */
class StaticDin : Estimator("E0 today (DIN on the rest, no learning)") {
    override fun observe(s: Seg, movingS: Double) {}
    override fun remaining(rest: List<Seg>): Double {
        var up = 0.0; var down = 0.0
        rest.forEach { if (it.dz > 0) up += it.dz else down -= it.dz }
        return Geo.estimateSeconds(rest.sumOf { it.lenM }, up, down, Activity.HIKE)
    }
}

class StaticTable : Estimator("E1 table B, no learning") {
    override fun observe(s: Seg, movingS: Double) {}
    override fun remaining(rest: List<Seg>) = rest.sumOf { tableSeg(it) }
}

/** Garmin-style: rest distance / average moving speed so far (4 km/h until 200 m are done). */
class AvgSpeed : Estimator("E2 rest km / average speed so far") {
    var d = 0.0; var t = 0.0
    override fun observe(s: Seg, movingS: Double) { d += s.lenM; t += movingS }
    override fun remaining(rest: List<Seg>) = rest.sumOf { it.lenM } / (if (d > 200) d / t else 4 / 3.6)
}

/** One factor for the trip: f = (tau + actual) / (tau + predicted), clamped 0.5..2. */
class GlobalFactor(val tauS: Double = 1200.0) : Estimator("E3 table B x one learned factor") {
    var a = 0.0; var p = 0.0
    fun f() = ((tauS + a) / (tauS + p)).coerceIn(0.5, 2.0)
    override fun observe(s: Seg, movingS: Double) { a += movingS; p += tableSeg(s) }
    override fun remaining(rest: List<Seg>) = rest.sumOf { tableSeg(it) } * f()
}

/**
 * Factor per group (down / flat / up). The trip-wide factor f_all is shrunk towards 1 (prior); a group is shrunk towards
 * a *partial* transfer of f_all (1 + rho * (f_all - 1)): being fast uphill says something, but not everything, about
 * downhill. rho = 1 means "unobserved groups copy the trip factor".
 */
class GroupFactors(val tauS: Double = 1200.0, val tauG: Double = 900.0, val rho: Double = 0.5, label: String = "") :
    Estimator("E4 table B x factor per down/flat/up group$label") {
    val a = DoubleArray(3); val p = DoubleArray(3)
    fun fAll() = ((tauS + a.sum()) / (tauS + p.sum())).coerceIn(0.5, 2.0)
    fun f(g: Int) = ((tauG * (1 + rho * (fAll() - 1)) + a[g]) / (tauG + p[g])).coerceIn(0.5, 2.0)
    override fun observe(s: Seg, movingS: Double) { val g = grp(cls(s.grade)); a[g] += movingS; p[g] += tableSeg(s) }
    override fun remaining(rest: List<Seg>): Double {
        val fs = DoubleArray(3) { f(it) }
        return rest.sumOf { tableSeg(it) * fs[grp(cls(it.grade))] }
    }
}

/** E4 + calm display: the shown arrival moves at most 1 min + 2 % of the remaining time per 100 m update. */
class Calm(val inner: Estimator) : Estimator(inner.name.replace("E4", "E5") + " + calm display") {
    var shown = Double.NaN
    var elapsed = 0.0
    override fun observe(s: Seg, movingS: Double) { inner.observe(s, movingS); elapsed += movingS }
    override fun remaining(rest: List<Seg>): Double {
        val raw = inner.remaining(rest)
        if (shown.isNaN()) { shown = elapsed + raw; return raw }
        val target = elapsed + raw
        val lim = 60 + 0.02 * raw
        shown += (target - shown).coerceIn(-lim, lim)
        return max(0.0, shown - elapsed)
    }
}

fun simulate(segs: List<Seg>, h: Hiker, seed: Int): DoubleArray {
    val rnd = Random(seed * 7919L + h.name.hashCode())
    val n = segs.size
    var z = 0.0
    return DoubleArray(n) { i ->
        z = 0.8 * z + 0.6 * rnd.nextGaussian() // terrain / form noise, correlated over ~250 m, sigma ~ 15 %
        tableSeg(segs[i]) * h.f[cls(segs[i].grade)] * (1 + h.fatigue * i / n) * exp(0.15 * z)
    }
}

fun med(l: List<Double>) = l.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
fun p90(l: List<Double>) = l.sorted().let { if (it.isEmpty()) Double.NaN else it[(it.size * 0.9).toInt().coerceAtMost(it.size - 1)] }

fun replay(all: List<Pair<String, List<Seg>>>) {
    println("\n== Part 2: replay of synthetic hikers along the real hiking routes (moving time only) ==")
    // f = personal factor vs. table B per class [steep down, down, flat, up, steep up]
    val hikers = listOf(
        Hiker("as table B", doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0), 0.0, 0.10),
        Hiker("fit (0.8)", doubleArrayOf(0.8, 0.8, 0.8, 0.8, 0.8), 0.05, 0.05),
        Hiker("relaxed (1.3)", doubleArrayOf(1.3, 1.3, 1.3, 1.3, 1.3), 0.10, 0.20),
        Hiker("strong up, bad knees", doubleArrayOf(1.6, 1.3, 1.0, 0.85, 0.8), 0.05, 0.10),
        Hiker("slow up, fast down", doubleArrayOf(0.8, 0.9, 1.0, 1.25, 1.4), 0.10, 0.15),
    )
    val ests = listOf<() -> Estimator>(
        { StaticDin() }, { StaticTable() }, { AvgSpeed() }, { GlobalFactor() },
        { GroupFactors(rho = 1.0, label = " (rho 1)") }, { GroupFactors(rho = 0.5, label = " (rho 0.5)") },
        { Calm(GroupFactors(rho = 0.5, label = " (rho 0.5)")) },
    )
    val routes = all.filter { it.second.sumOf { s -> s.lenM } > 2500 }
    val cps = listOf(0.25, 0.5, 0.75)
    val errs = Array(ests.size) { Array(cps.size) { mutableListOf<Double>() } }
    val startErr = Array(ests.size) { mutableListOf<Double>() }
    val jumps = Array(ests.size) { mutableListOf<Double>() }
    val perHiker = Array(hikers.size) { Array(ests.size) { mutableListOf<Double>() } }
    var runs = 0
    for ((_, segs) in routes) for ((hi, h) in hikers.withIndex()) for (seed in 1..20) {
        runs++
        val truth = simulate(segs, h, seed)
        val n = segs.size
        val es = ests.map { it() }
        es.forEachIndexed { k, e -> startErr[k] += abs(e.remaining(segs) / truth.sum() - 1) }
        val lastArr = DoubleArray(es.size) { Double.NaN }
        var cp = 0; var elapsed = 0.0
        for (i in 0 until n) {
            es.forEach { it.observe(segs[i], truth[i]) }
            elapsed += truth[i]
            if ((i + 1) % 2 != 0 || i + 1 >= n) continue // the display updates every 100 m
            val rest = segs.subList(i + 1, n)
            val trueRest = truth.copyOfRange(i + 1, n).sum()
            val rem = es.map { it.remaining(rest) }
            rem.forEachIndexed { k, r ->
                val arr = elapsed + r
                if (!lastArr[k].isNaN()) jumps[k] += abs(arr - lastArr[k]) / 60
                lastArr[k] = arr
            }
            val done = (i + 1).toDouble() / n
            while (cp < cps.size && done >= cps[cp]) {
                rem.forEachIndexed { k, r ->
                    errs[k][cp] += abs(r / trueRest - 1)
                    if (cp == 1) perHiker[hi][k] += abs(r / trueRest - 1)
                }
                cp++
            }
        }
    }
    println("${routes.size} routes > 2.5 km x ${hikers.size} hikers x 20 seeds = $runs runs; |error| of the remaining moving time")
    println("%-58s | %-11s | %-11s | %-11s | %-11s | %s".format("estimator (median / p90)", "at start", "at 25 %", "at 50 %", "at 75 %", "arrival jump per 100 m: p90 / max (min)"))
    for (k in ests.indices) {
        val c = cps.indices.joinToString(" | ") { "%4.0f%%/%4.0f%%".format(med(errs[k][it]) * 100, p90(errs[k][it]) * 100) }
        println("%-58s | %4.0f%%/%4.0f%% | %s | %4.1f / %5.1f".format(ests[k]().name, med(startErr[k]) * 100, p90(startErr[k]) * 100, c,
            p90(jumps[k]), jumps[k].maxOrNull() ?: 0.0))
    }
    println("\nper hiker at 50 % (median |error|), columns = estimators in the order above:")
    for ((hi, h) in hikers.withIndex()) println("  %-22s ".format(h.name) + ests.indices.joinToString(" ") { "%5.0f%%".format(med(perHiker[hi][it]) * 100) })

    println("\nper route at 50 % (median |error|, all hikers): E0 today / E1 table / E3 one factor / E4 groups rho 0.5")
    for ((name, segs) in routes) {
        val l = Array(4) { mutableListOf<Double>() }
        for (h in hikers) for (seed in 1..20) {
            val truth = simulate(segs, h, seed)
            val es = listOf(StaticDin(), StaticTable(), GlobalFactor(), GroupFactors(rho = 0.5))
            val half = segs.size / 2
            for (i in 0 until half) es.forEach { it.observe(segs[i], truth[i]) }
            val tr = truth.copyOfRange(half, segs.size).sum()
            es.forEachIndexed { k, e -> l[k] += abs(e.remaining(segs.subList(half, segs.size)) / tr - 1) }
        }
        println("  %-36s %s".format(name, l.joinToString(" / ") { "%3.0f%%".format(med(it) * 100) }))
    }

    breaks(routes, hikers)
}

/**
 * Arrival clock = now + remaining moving time + expected remaining breaks. True breaks: the hiker's break ratio of the
 * moving time, taken as blocks (25 % at 1/3, 50 % at the middle, 25 % at 3/4 of the way). Allowances compared:
 * none, fixed 10 %, learned ratio (prior 10 % worth 1 h of moving time). Moving time is assumed known exactly here.
 */
fun breaks(routes: List<Pair<String, List<Seg>>>, hikers: List<Hiker>) {
    println("\n== Part 2b: breaks in the arrival clock (moving time assumed exact) ==")
    val names = listOf("no break allowance", "fixed 10 % allowance", "learned break ratio (prior 10 %, weight 1 h)")
    val res = Array(3) { Array(3) { mutableListOf<Double>() } }
    for ((_, segs) in routes) for (h in hikers) for (seed in 1..20) {
        val truth = simulate(segs, h, seed)
        val n = segs.size
        val total = truth.sum()
        val brk = h.breakRatio * total
        val at = listOf(n / 3 to 0.25 * brk, n / 2 to 0.5 * brk, 3 * n / 4 to 0.25 * brk)
        for ((ci, cp) in listOf(0.25, 0.5, 0.75).withIndex()) {
            val i = (cp * n).toInt()
            val moved = truth.copyOfRange(0, i).sum()
            val restMoving = total - moved
            val breaksSoFar = at.filter { it.first < i }.sumOf { it.second }
            val trueLeft = restMoving + brk - breaksSoFar
            val learned = (0.10 * 3600.0 + breaksSoFar) / (3600.0 + moved)
            val guesses = listOf(restMoving, restMoving * 1.10, restMoving * (1 + learned))
            guesses.forEachIndexed { k, g -> res[k][ci] += abs(g - trueLeft) / 60 }
        }
    }
    println("%-46s | %s".format("arrival clock (median / p90 error, minutes)", "at 25 % | at 50 % | at 75 %"))
    for (k in 0 until 3) println("%-46s | %s".format(names[k], (0 until 3).joinToString(" | ") { "%3.0f / %3.0f".format(med(res[k][it]), p90(res[k][it])) }))
}

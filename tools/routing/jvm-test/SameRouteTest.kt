// SameRouteTest, the JVM part (docs/design/detour-stitch.md §5, §8): the same plan gives the same route on every device.
// Warm = the editing device (a LegCache with the old legs, anchors from PlanAnchors.followInsert, LegSplit.seed, the real
// PlanResult.plan, the snap, LegCache.aliasExpanded; the plan run again after the snap routes NOTHING). Cold = another
// device (PlanCode.encode of the snapped plan with its anchors, decode, expand, route with a cache that holds only really
// routed plain legs, never a cut). Pass: RouteHash (sha256 of the line only) equal, distance within 0.5 %.
//
// NOT covered: speed-up 3 (cut points for adds 5–10 km off) is not built, so the "10 adds 5–10 km" of §5 are out of
// scope here; every add of this test lies 50 m … 2 km beside the line (the stitch case). Nothing is faked for them.
//
// Corpus (all inside E10_N45, every tour has legs over 60 km of line): 1 München → Venezia Bike (a subset of
// MultiDayTourTest.HAND), 2 the same Hike T2, 3 Salzburg → Villach → Trieste Bike, 4 Garmisch → Innsbruck → Bozen → Trento
// Bike. Per tour: 20 independent adds (each from the base plan, its own copy of the base legs), a chain of 3 adds on the
// longest leg (each through followInsert on the current leg line), then a night on the line inside an anchor span
// (followInsert, N on the line), a delete of the first stitched point (follow) and an undo (the snapshot's points and
// anchors). Everything is deterministic: fixed arithmetic, no clock, no random.
//
// RouteHash text form (so that Kotlin/Wasm computes exactly the same): for every point of the line, in order,
//   <round(lat * 1e6)> "," <round(lon * 1e6)> "\n"   (round = floor(x + 0.5) as a decimal integer, no sign for +, '-' for −)
// UTF-8, then sha256, lower-case hex.
//
// Run (needs E10_N45.rd5; own workDir, one JVM at a time; about 1-2 h for the whole corpus):
//   KTTEST=tools/routing/jvm-test/SameRouteTest.kt EXTRA_KT="core/src/main/kotlin/com/onpath/app/routing/PlanResult.kt
//     core/src/main/kotlin/com/onpath/app/domain/PlanGaps.kt core/src/main/kotlin/com/onpath/app/routing/PlanReach.kt
//     core/src/main/kotlin/com/onpath/app/routing/ProfileData.kt" JAVA_XMX=2g tools/routing/jvm-test/run.sh <segmentsDir> <workDir>
// Env: MODE=run (default) | verify (read core/src/test/resources/same-route/*.sha, route every plan code cold from an
//   empty cache, reproduce every hash); TOURS=<comma list of tour names> (default all); ADDS=<n> (default 20, a smoke
//   run); SHA_DIR=<dir> reads / writes the .sha files there instead of core/src/test/resources/same-route;
//   CANON=1 (a measurement, never writes .sha files): the base plan is snapped and routed again from the snapped points until
//   stable (at most 4 runs, really routed legs only, no alias), and after each case's snap every PLAIN leg with a moved end is
//   routed again from the snapped points (cut legs stay and are counted); remaining mismatches are classed against the cut legs;
//   SHA_SELFTEST=<dir> (a run with red cases) writes only the green cases there, to try MODE=verify; WRITE_SHA=0 never writes the files (default: written only when EVERY case of all four tours is green).
import com.onpath.app.data.Geo
import com.onpath.app.routing.*
import com.onpath.app.time.Factors
import java.io.File
import java.security.MessageDigest

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

const val HERE_DIR = "@JVMTEST_DIR@"
val SEG_DIR = File("/home/user/build/routing-test/segments")
val F = Factors()

fun log(s: String) { println(s); System.out.flush() }

class Tour(val name: String, val act: Activity, val sac: Int, val pts: List<GeoPoint>)

val MUC_VENEZIA = listOf(GeoPoint(48.1374, 11.5755), GeoPoint(47.2655, 11.3940), GeoPoint(46.7300, 12.2240),
    GeoPoint(46.1400, 12.2170), GeoPoint(45.4380, 12.3190))

val TOURS = listOf(
    Tour("muc-venezia-bike", Activity.BIKE_TOURING, 2, MUC_VENEZIA),
    Tour("muc-venezia-hike", Activity.HIKE, 2, MUC_VENEZIA),
    Tour("salzburg-trieste-bike", Activity.BIKE_TOURING, 2,
        listOf(GeoPoint(47.8095, 13.0550), GeoPoint(46.6103, 13.8558), GeoPoint(45.6495, 13.7768))),
    Tour("garmisch-trento-bike", Activity.BIKE_TOURING, 2,
        listOf(GeoPoint(47.4917, 11.0955), GeoPoint(47.2692, 11.4041), GeoPoint(46.4983, 11.3548), GeoPoint(46.0679, 11.1211))),
)

class State(val pts: List<GeoPoint>, val anchors: Map<Int, Anchors>)

/** One warm run: the snapped state, its expanded plan, the line after the snap, and what was routed. */
class Step(val state: State, val exp: ExpandedPlan, val route: PlannedRoute, val routed: Int, val rerunRouted: Int, val ok: Boolean, val note: String,
           /** CANON: requests / seconds spent re-routing plain legs with a moved end, cut legs with a moved end that were kept, cut legs of [exp]. */
           val extraReq: Int = 0, val extraS: Double = 0.0, val keptCut: Int = 0, val cutLegs: Set<Int> = emptySet(),
           /** Default mode: requests and seconds spent in Planner.settle (the line settles, §8.15). */
           val settleReq: Int = 0, val settleS: Double = 0.0)

/** CANON=1: the canonical base and the re-routing of plain legs with a moved end (see the header). */
val CANON = System.getenv("CANON") == "1"

fun rounded(p: GeoPoint) = GeoPoint(Math.round(p.lat * 1e6) / 1e6, Math.round(p.lon * 1e6) / 1e6)

fun lineText(line: List<GeoPoint>) = buildString {
    for (p in line) { append(Math.round(p.lat * 1e6)); append(','); append(Math.round(p.lon * 1e6)); append('\n') }
}

fun sha(line: List<GeoPoint>): String = MessageDigest.getInstance("SHA-256").digest(lineText(line).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

fun clean(r: PlannedRoute) = r.error == null && r.legs.none { it.straight }

/** The line of visible leg [v] of [step]: its expanded legs joined. */
fun legLine(exp: ExpandedPlan, route: PlannedRoute, v: Int): List<GeoPoint> {
    val from = exp.expandedIndex(v); val to = exp.expandedIndex(v + 1)
    val out = ArrayList<GeoPoint>()
    for (i in from until to) route.legs[i].points.forEach { if (out.isEmpty() || out.last() != it) out += it }
    return out
}

/** Warm: seed, the real PlanResult.plan, snap, alias, then the plan again from the cache (must route nothing). */
fun warm(engine: BRouterEngine, cache: LegCache, st: State, t: Tour, settle: Boolean = true): Step {
    val exp = PlanAnchors.expand(st.pts, st.anchors, emptySet())
    LegSplit.seed(cache, exp, t.act, t.sac)
    val p1 = Planner(engine, legs = cache)
    val res = PlanResult.plan(p1, exp, t.act, t.sac, emptySet(), F)
    val snapped = res.points
    val expS = PlanAnchors.expand(snapped, st.anchors, emptySet())
    cache.aliasExpanded(exp, expS, t.act, t.sac)
    var extraReq = 0; var keptCut = 0; var extraS = 0.0
    if (CANON) {
        // every plain leg with a moved end is routed again from the snapped points (a really routed leg under its plain key);
        // a cut leg with a moved end stays as it is
        val t0 = System.nanoTime()
        for (i in 0 until exp.points.size - 1) {
            if (key(exp.points[i]) == key(expS.points[i]) && key(exp.points[i + 1]) == key(expS.points[i + 1])) continue
            val a = expS.points[i]; val b = expS.points[i + 1]
            if (expS.touchesAnchor(i) && cache.inMemory(a, b, t.act, t.sac, cut = true) != null) { keptCut++; continue }
            val tmp = LegCache(maxLinePoints = 600_000)
            val pl = Planner(engine, legs = tmp)
            try { pl.plan(listOf(a, b), t.act, t.sac) } catch (e: Exception) { log("   canon: leg $i could not be routed again: $e") } // expected: the alias stays
            extraReq += pl.tries.size
            tmp.get(a, b, t.act, t.sac)?.let { cache.put(a, b, t.act, t.sac, it) }
        }
        extraS = (System.nanoTime() - t0) / 1e9
    }
    val first = Step(State(snapped, st.anchors), expS, res.route, p1.tries.size, 0, clean(res.route),
        if (!clean(res.route)) "first run: ${res.route.error}" else "", extraReq, extraS, keptCut)
    return if (settle && !CANON) settled(engine, cache, first, t) else rerun(engine, cache, first, t, 0, 0.0)
}

/** The plan again from the cache after the snap and alias (and a settle): must route nothing. */
fun rerun(engine: BRouterEngine, cache: LegCache, s: Step, t: Tour, settleReq: Int, settleS: Double): Step {
    val p2 = Planner(engine, legs = cache)
    val again = p2.plan(s.exp, t.act, t.sac)
    val firstClean = s.note.isEmpty()
    val ok = firstClean && clean(again)
    val note = if (!firstClean) s.note else if (!clean(again)) "rerun: ${again.error}" else ""
    val cutLegs = (0 until s.exp.points.size - 1).filter { s.exp.touchesAnchor(it) && cache.inMemory(s.exp.points[it], s.exp.points[it + 1], t.act, t.sac, cut = true) != null }.toSet()
    return Step(s.state, s.exp, again, s.routed, p2.tries.size, ok, note, s.extraReq, s.extraS, s.keptCut, cutLegs, settleReq, settleS)
}

/** The line settles (§8.15): Planner.settle on the snapped plan, then the plan again from the cache. That is the warm line compared. */
fun settled(engine: BRouterEngine, cache: LegCache, s: Step, t: Tour): Step {
    if (!s.ok && s.note.startsWith("first run")) return rerun(engine, cache, s, t, 0, 0.0)
    val ps = Planner(engine, legs = cache)
    val t0 = System.nanoTime()
    val r = ps.settle(s.exp, t.act, t.sac)
    val sec = (System.nanoTime() - t0) / 1e9
    if (r.failed > 0 || r.cancelled) log("   settle: routed ${r.routed}, failed ${r.failed}, cancelled ${r.cancelled}")
    return rerun(engine, cache, s, t, ps.tries.size, sec)
}

/** CANON: route, snap, route again from the snapped points (really routed legs only) until stable at 1e-6 or 4 runs. */
fun canonBase(engine: BRouterEngine, t: Tour): Pair<Step, LegCache> {
    var pts = t.pts
    var last: Step? = null; var cache = LegCache(maxLinePoints = 600_000)
    val t0 = System.nanoTime(); var firstS = 0.0
    var stable = false
    for (r in 1..4) {
        cache = LegCache(maxLinePoints = 600_000)
        val exp = PlanAnchors.expand(pts, emptyMap(), emptySet())
        val p = Planner(engine, legs = cache)
        val tr = System.nanoTime()
        val res = PlanResult.plan(p, exp, t.act, t.sac, emptySet(), F)
        val sec = (System.nanoTime() - tr) / 1e9
        if (r == 1) firstS = sec
        val moved = pts.indices.count { key(res.points[it]) != key(pts[it]) }
        log("canonical base ${t.name}: run $r routed ${p.tries.size} requests in %.1f s; %d of %d points moved when routed from their position".format(sec, moved, pts.size))
        last = Step(State(pts, emptyMap()), exp, res.route, p.tries.size, 0, clean(res.route), if (clean(res.route)) "" else "first run: ${res.route.error}")
        if (!clean(res.route)) break
        if (moved == 0) { stable = true; break }
        pts = res.points
    }
    val total = (System.nanoTime() - t0) / 1e9
    log("canonical base ${t.name}: ${if (stable) "stable" else "NOT stable"} after the last run; total %.1f s, of it %.1f s beyond the first run".format(total, total - firstS))
    return last!! to cache
}

/** The index range of each leg of [route] on its joined line ([PlannedRoute.points]). */
fun legRanges(route: PlannedRoute): Pair<IntArray, IntArray> {
    val start = IntArray(route.legs.size); val end = IntArray(route.legs.size)
    val out = ArrayList<GeoPoint>()
    for ((i, leg) in route.legs.withIndex()) {
        var first = true
        for (p in leg.points) {
            if (out.isEmpty() || out.last() != p) out += p else out[out.size - 1] = p
            if (first) { start[i] = out.size - 1; first = false }
        }
        end[i] = out.size - 1
    }
    return start to end
}

/** CANON: where a differing hunk (warm index range [h0, h1)) lies against the CUT legs of the warm plan. */
fun hunkKind(step: Step, h0: Int, h1: Int): String {
    val (start, end) = legRanges(step.route)
    val lo = h0; val hi = maxOf(h1 - 1, h0)
    val inside = step.cutLegs.any { start[it] < lo && hi < end[it] }
    val join = !inside && step.cutLegs.any { lo <= end[it] + 1 && hi >= start[it] - 1 }
    return if (inside) "inside a CUT leg" else if (join) "at a join with a cut leg" else "elsewhere"
}

class Case(val tour: String, val name: String, val anchors: Int, val code: String, val warmHash: String, val coldHash: String,
           val warmM: Double, val coldM: Double, val routedWarm: Int, val rerunRouted: Int, val problems: List<String>) {
    val green get() = problems.isEmpty()
    // CANON measurements
    var extraReq = 0; var extraS = 0.0; var keptCut = 0
    /** Visible points the cold route moves again (PlanResult.snap on the cold line) / visible points of the plan. */
    var coldMoved = 0; var visiblePts = 0
    /** Default mode: requests and seconds spent in settle for this case. */
    var settleReq = 0; var settleS = 0.0
    /** The control (the base plan, no anchors): reported on its own, no pass or fail. */
    val control get() = name.startsWith("base (control")
}

/** Cold: PlanCode round trip, expand, route with [coldCache] (plain routed legs only), compare with the warm line. */
fun cold(engine: BRouterEngine, coldCache: LegCache, step: Step, t: Tour, name: String): Case {
    val problems = ArrayList<String>()
    if (!step.ok) problems += "warm side not clean: ${step.note}"
    if (step.rerunRouted != 0) problems += "warm rerun after the snap routed ${step.rerunRouted} request(s)"
    val code = PlanCode.encode(SharedPlan(step.state.pts, t.act, t.sac, emptySet(), anchors = step.state.anchors))
    val dec = PlanCode.decode(code)
    if (dec == null) {
        return Case(t.name, name, step.state.anchors.values.sumOf { it.size }, code, "", "", step.route.distanceM, 0.0, step.routed,
            step.rerunRouted, problems + "plan code did not decode")
    }
    // by coordinates: the snapped points carry elevation / way data, a decoded point does not
    fun same(a: List<GeoPoint>, b: List<GeoPoint>) = a.size == b.size && a.indices.all { key(a[it]) == key(b[it]) }
    if (!same(dec.points, step.state.pts)) problems += "decoded points differ from the snapped points: " +
        dec.points.indices.filter { it >= step.state.pts.size || key(dec.points[it]) != key(step.state.pts[it]) }
            .take(3).joinToString { "#$it ${dec.points[it].lat},${dec.points[it].lon} vs ${step.state.pts.getOrNull(it)?.lat},${step.state.pts.getOrNull(it)?.lon}" }
    val anchorsSame = dec.anchors.keys == step.state.anchors.keys && dec.anchors.all { (i, a) ->
        val b = step.state.anchors[i]!!; same(a.before, b.before) && same(a.after, b.after) }
    if (!anchorsSame) problems += "decoded anchors differ"
    val expC = PlanAnchors.expand(dec.points, dec.anchors, dec.pinned)
    val coldRoute = Planner(engine, legs = coldCache).plan(expC, t.act, t.sac)
    if (!clean(coldRoute)) problems += "cold route not clean: ${coldRoute.error}"
    val wl = step.route.points; val cl = coldRoute.points
    val wh = sha(wl); val ch = sha(cl)
    val dPct = if (coldRoute.distanceM > 0) 100.0 * (step.route.distanceM - coldRoute.distanceM) / coldRoute.distanceM else 0.0
    if (wh != ch) problems += describeDiff(wl, cl, step.exp, step.route, dPct, step)
    if (Math.abs(dPct) > 0.5) problems += "distance differs %.2f %% (warm %.0f m, cold %.0f m)".format(dPct, step.route.distanceM, coldRoute.distanceM)
    return Case(t.name, name, step.state.anchors.values.sumOf { it.size }, code, wh, ch, step.route.distanceM, coldRoute.distanceM,
        step.routed, step.rerunRouted, problems).also { c ->
        c.extraReq = step.extraReq; c.extraS = step.extraS; c.keptCut = step.keptCut
        c.settleReq = step.settleReq; c.settleS = step.settleS
        val routedVisible = expC.visible
        val sn = PlanResult.snap(routedVisible, coldRoute, emptySet(), emptySet())
        c.visiblePts = routedVisible.size
        c.coldMoved = routedVisible.indices.count { key(sn.points[it]) != key(routedVisible[it]) }
    }
}

fun key(p: GeoPoint) = Math.round(p.lat * 1e6) to Math.round(p.lon * 1e6)

/** The differing stretch between two lines: its length in metres (warm / cold side), the leg, which one is longer. */
fun describeDiff(w: List<GeoPoint>, c: List<GeoPoint>, exp: ExpandedPlan, route: PlannedRoute, dPct: Double, step: Step? = null): String {
    val m = minOf(w.size, c.size)
    var pre = 0
    while (pre < m && key(w[pre]) == key(c[pre])) pre++
    var suf = 0
    while (suf < m - pre && key(w[w.size - 1 - suf]) == key(c[c.size - 1 - suf])) suf++
    fun stretch(l: List<GeoPoint>) = Geo.length(l.subList(maxOf(pre - 1, 0), minOf(l.size, l.size - suf + 1)))
    val sw = stretch(w); val sc = stretch(c)
    if (System.getenv("DEBUG") != null) {
        log("   debug: sizes ${w.size}/${c.size}, common prefix $pre, suffix $suf")
        for (k in maxOf(pre - 2, 0)..minOf(pre + 4, m - 1)) log("   debug: #$k warm ${key(w[k])} cold ${key(c[k])}")
        for (k in 0 until minOf(3, suf)) log("   debug: suffix pts ${key(w[w.size - 1 - k])}")
    }
    var acc = 0; var leg = 0
    for (i in route.legs.indices) { acc += route.legs[i].points.size - 1; if (pre <= acc) { leg = i; break } }
    var v = 0
    for (i in 0..leg) if (exp.visibleOf(i) >= 0) v = exp.visibleOf(i)
    val hs = hunks(w, c)
    val where = hs.take(4).joinToString("; ") { h ->
        val a = w[maxOf(h[0] - 1, 0)]
        val near = exp.points.indices.minByOrNull { Geo.distance(exp.points[it], a) }!!
        "%d–%d warm %.0f m vs %d–%d cold %.0f m at %.6f,%.6f (%.0f m from %s)".format(h[0], h[1], Geo.length(w.subList(maxOf(h[0] - 1, 0), minOf(h[1] + 1, w.size))),
            h[2], h[3], Geo.length(c.subList(maxOf(h[2] - 1, 0), minOf(h[3] + 1, c.size))), a.lat, a.lon, Geo.distance(exp.points[near], a),
            if (exp.visibleOf(near) >= 0) "plan point ${exp.visibleOf(near)}" else "anchor (expanded #$near)") +
            (if (CANON && step != null) " [" + hunkKind(step, h[0], h[1]) + "]" else "")
    }
    fun hunkLen(l: List<GeoPoint>, a: Int, b: Int) = Geo.length(l.subList(maxOf(a - 1, 0), minOf(b + 1, l.size)))
    val totW = hs.sumOf { hunkLen(w, it[0], it[1]) }; val totC = hs.sumOf { hunkLen(c, it[2], it[3]) }
    val hunkInfo = ", %d differing hunk(s) of %.0f m warm / %.0f m cold in all: %s".format(hs.size, totW, totC, where)
    // same number of points: how many differ and by how much (a one-micro-degree step is 0.1 m)
    val offs = if (w.size == c.size) w.indices.filter { key(w[it]) != key(c[it]) }.map { Geo.distance(w[it], c[it]) } else emptyList()
    val nearPlan = if (w.size == c.size) w.indices.filter { key(w[it]) != key(c[it]) }.count { i -> exp.points.any { Geo.distance(it, w[i]) <= 1.0 } } else 0
    val detail = if (w.size == c.size) ", %d of %d points differ (%d of them within 1 m of a plan point or anchor), largest %.2f m".format(
        offs.size, w.size, nearPlan, offs.maxOrNull() ?: 0.0)
    else ", point counts %d warm / %d cold".format(w.size, c.size)
    return "hash differs: first difference in leg %d (visible %d→%d), outer span warm %.0f m / cold %.0f m%s%s, distance diff %.3f %%, %s line longer".format(
        leg, v, v + 1, sw, sc, detail, hunkInfo, dPct, if (w.size == c.size && sw == sc) "neither" else if (sw > sc) "warm" else "cold")
}

/** The places where two lines part and meet again: (warm from, warm to, cold from, cold to), index ranges that differ. */
fun hunks(w: List<GeoPoint>, c: List<GeoPoint>, window: Int = 4000): List<IntArray> {
    val out = ArrayList<IntArray>()
    var i = 0; var j = 0
    while (i < w.size && j < c.size) {
        if (key(w[i]) == key(c[j])) { i++; j++; continue }
        val at = HashMap<Pair<Long, Long>, Int>()
        for (q in j until minOf(c.size, j + window)) at.putIfAbsent(key(c[q]), q)
        var i2 = -1; var j2 = -1
        for (q in i until minOf(w.size, i + window)) {
            val hit = at[key(w[q])] ?: continue
            if ((0 until 3).all { d -> q + d < w.size && hit + d < c.size && key(w[q + d]) == key(c[hit + d]) }) { i2 = q; j2 = hit; break }
        }
        if (i2 < 0) { out += intArrayOf(i, w.size, j, c.size); break }
        out += intArrayOf(i, i2, j, j2); i = i2; j = j2
    }
    return out
}

fun offsetPoint(line: List<GeoPoint>, fraction: Double, dM: Double, sign: Int): GeoPoint? {
    if (line.size < 6) return null
    val cum = Geo.cumulative(line)
    val target = cum.last() * fraction
    var i = 1
    while (i < line.lastIndex - 1 && cum[i] < target) i++
    val a = line[maxOf(i - 2, 0)]; val b = line[minOf(i + 2, line.lastIndex)]
    val p = line[i]
    val fl = Geo.Flat(p.lat)
    val dx = (b.lon - a.lon) * fl.kx; val dy = (b.lat - a.lat) * fl.ky
    val len = Math.hypot(dx, dy)
    if (len == 0.0) return null
    val nx = -dy / len; val ny = dx / len
    return rounded(GeoPoint(p.lat + sign * dM * ny / fl.ky, p.lon + sign * dM * nx / fl.kx))
}

fun fork(base: LegCache, st: State, t: Tour): LegCache {
    val c = LegCache(maxLinePoints = 600_000)
    for (i in 0 until st.pts.size - 1) base.get(st.pts[i], st.pts[i + 1], t.act, t.sac)?.let { c.put(st.pts[i], st.pts[i + 1], t.act, t.sac, it) }
    return c
}

fun insertAt(st: State, at: Int, p: GeoPoint, line: List<GeoPoint>): State {
    val after = st.pts.toMutableList().also { it.add(at, p) }
    return State(after, PlanAnchors.followInsert(st.pts, after, st.anchors, at, line))
}

fun runTour(engine: BRouterEngine, t: Tour, nAdds: Int): List<Case> {
    val t0 = System.nanoTime()
    fun s() = (System.nanoTime() - t0) / 1_000_000_000
    val out = ArrayList<Case>()
    val coldCache = LegCache(maxLinePoints = 600_000)
    var baseCache = LegCache(maxLinePoints = 600_000)
    log("== ${t.name}: base plan, ${t.pts.size} points")
    val base = if (CANON) canonBase(engine, t).let { (st, c) -> baseCache = c; st } else warm(engine, baseCache, State(t.pts, emptyMap()), t)
    if (!base.ok) {
        out += Case(t.name, "base", 0, "", "", "", 0.0, 0.0, base.routed, 0, listOf("the base plan cannot be routed: ${base.note}"))
        return out
    }
    val longLegs = (0 until base.state.pts.size - 1).filter { Geo.length(legLine(base.exp, base.route, it)) > PlanAnchors.MIN_LEG_M }
    log("base routed in ${s()} s, %.0f km, legs over 60 km of line: %s".format(base.route.distanceM / 1000,
        longLegs.joinToString { "$it (%.0f km)".format(Geo.length(legLine(base.exp, base.route, it)) / 1000) }))
    if (longLegs.isEmpty()) {
        out += Case(t.name, "base", 0, "", "", "", 0.0, 0.0, base.routed, 0, listOf("no leg over 60 km of line"))
        return out
    }
    fun record(c: Case) {
        out += c
        log("[${s()} s] ${c.name}: anchors ${c.anchors}, routed on warm ${c.routedWarm} (rerun ${c.rerunRouted}), ${if (c.green) "GREEN" else "RED"}; " +
            "%.1f km warm / %.1f km cold%s".format(c.warmM / 1000, c.coldM / 1000, c.problems.joinToString("") { "\n    - $it" }))
    }

    // 0. control: the base plan itself (no edit), warm after the snap against cold
    record(cold(engine, coldCache, base, t, "base (control, no edit)"))

    // diagnostic (not a case): the base plan's snapped points routed cold as they are, without the plan code (exact points)
    run {
        val exact = PlanAnchors.expand(base.state.pts, emptyMap(), emptySet())
        val r = Planner(engine, legs = LegCache(maxLinePoints = 600_000)).plan(exact, t.act, t.sac)
        val wl = base.route.points; val cl = r.points
        log("diagnostic (base, exact snapped points, no plan code): " + if (sha(wl) == sha(cl)) "line equal" else describeDiff(wl, cl, base.exp, base.route, 0.0))
    }

    // 1. independent adds, each from the base plan on its own copy of the base legs
    for (k in 0 until nAdds) {
        val v = longLegs[k % longLegs.size]
        val line = legLine(base.exp, base.route, v)
        var done = false
        for (attempt in 0..3) {
            val f = 0.12 + 0.76 * (((k + 1) * 0.6180339887 + attempt * 0.137) % 1.0)
            val d = (50 + (k * 743 + attempt * 331) % 1951).toDouble()
            val p = offsetPoint(line, f, d, if ((k + attempt) % 2 == 0) 1 else -1) ?: continue
            val cache = fork(baseCache, base.state, t)
            val st = insertAt(base.state, v + 1, p, line)
            val step = warm(engine, cache, st, t)
            if (!step.ok && step.note.startsWith("first run")) { log("add%02d: attempt $attempt: point not routable (${step.note}), next candidate".format(k + 1)); continue }
            record(cold(engine, coldCache, step, t, "add%02d".format(k + 1) + " (leg $v, %.2f of line, %.0f m off)".format(f, d)))
            done = true; break
        }
        if (!done) out += Case(t.name, "add%02d".format(k + 1), 0, "", "", "", 0.0, 0.0, 0, 0, listOf("no routable candidate point in 4 attempts"))
    }

    // 2. a chain of 3 adds on the longest leg, then night / delete / undo
    val chainLeg = longLegs.maxByOrNull { Geo.length(legLine(base.exp, base.route, it)) }!!
    val cache = fork(baseCache, base.state, t)
    var step = base
    var added = 0
    var p1Index = -1
    for (m in 1..3) {
        val range = chainLeg..chainLeg + added
        val v = range.maxByOrNull { Geo.length(legLine(step.exp, step.route, it)) }!!
        val line = legLine(step.exp, step.route, v)
        var next: Step? = null
        for (attempt in 0..3) {
            val f = 0.2 + 0.6 * (((m + 3) * 0.6180339887 + attempt * 0.137) % 1.0)
            val d = (300 + m * 517 + attempt * 211).toDouble().coerceAtMost(1900.0)
            val p = offsetPoint(line, f, d, if (m % 2 == 1) 1 else -1) ?: continue
            val cand = warm(engine, cache, insertAt(step.state, v + 1, p, line), t)
            if (!cand.ok && cand.note.startsWith("first run")) { log("chain$m: attempt $attempt: point not routable, next candidate"); continue }
            next = cand
            if (m == 1) p1Index = v + 1 else if (v + 1 <= p1Index) p1Index++
            break
        }
        if (next == null) { out += Case(t.name, "chain$m", 0, "", "", "", 0.0, 0.0, 0, 0, listOf("no routable candidate point in 4 attempts")); break }
        step = next; added++
        record(cold(engine, coldCache, step, t, "chain$m (leg $v)"))
    }
    if (added == 3) {
        // night: a line point inside an anchor span, followInsert on it (N on the line)
        var night: Step? = null
        loop@ for (i in 1 until step.state.pts.size) {
            val a = step.state.anchors[i] ?: continue
            if (a.before.isNotEmpty()) { // the span from the last `before` anchor to point i, on visible leg i−1 → i
                val line = legLine(step.exp, step.route, i - 1)
                val at = LegSplit.positionsOn(line, listOf(a.before.last()))[0]
                if (at < 0 || at >= line.size - 3) continue
                val n = line[(at + line.size - 1) / 2]
                val st = insertAt(step.state, i, GeoPoint(n.lat, n.lon), line)
                night = warm(engine, cache, st, t); if (i <= p1Index) p1Index++
                break@loop
            }
            if (a.after.isNotEmpty()) { // the span from point i to its first `after` anchor, on visible leg i → i+1
                val line = legLine(step.exp, step.route, i)
                val at = LegSplit.positionsOn(line, listOf(a.after.first()))[0]
                if (at < 3) continue
                val n = line[at / 2]
                val st = insertAt(step.state, i + 1, GeoPoint(n.lat, n.lon), line)
                night = warm(engine, cache, st, t); if (i + 1 <= p1Index) p1Index++
                break@loop
            }
        }
        if (night == null) out += Case(t.name, "night", 0, "", "", "", 0.0, 0.0, 0, 0, listOf("no anchor span with a usable line point for the night"))
        else {
            step = night
            record(cold(engine, coldCache, step, t, "night (on the line in an anchor span)"))
            // delete of the first stitched point, then undo to the snapshot
            val snapshot = step
            val after = step.state.pts.toMutableList().also { it.removeAt(p1Index) }
            val del = warm(engine, cache, State(after, PlanAnchors.follow(step.state.pts, after, step.state.anchors)), t)
            record(cold(engine, coldCache, del, t, "delete (stitched point $p1Index)"))
            val undo = warm(engine, cache, snapshot.state, t)
            record(cold(engine, coldCache, undo, t, "undo (snapshot of the night)"))
        }
    }
    // 3. chainQuick: 3 adds in a row on the longest leg WITHOUT settling between them, one settle at the end
    run {
        val qCache = fork(baseCache, base.state, t)
        var q = base
        var qAdded = 0
        for (m in 1..3) {
            val range = chainLeg..chainLeg + qAdded
            val v = range.maxByOrNull { Geo.length(legLine(q.exp, q.route, it)) }!!
            val line = legLine(q.exp, q.route, v)
            var next: Step? = null
            for (attempt in 0..3) {
                val f = 0.2 + 0.6 * (((m + 3) * 0.6180339887 + attempt * 0.137) % 1.0)
                val d = (300 + m * 517 + attempt * 211).toDouble().coerceAtMost(1900.0)
                val p = offsetPoint(line, f, d, if (m % 2 == 1) 1 else -1) ?: continue
                val cand = warm(engine, qCache, insertAt(q.state, v + 1, p, line), t, settle = false)
                if (!cand.ok && cand.note.startsWith("first run")) { log("chainQuick$m: attempt $attempt: point not routable, next candidate"); continue }
                next = cand; break
            }
            if (next == null) { out += Case(t.name, "chainQuick", 0, "", "", "", 0.0, 0.0, 0, 0, listOf("no routable candidate point in 4 attempts")); return@run }
            q = next; qAdded++
        }
        record(cold(engine, coldCache, settled(engine, qCache, q, t), t, "chainQuick (3 adds, one settle at the end)"))
    }
    val ctl = out.filter { it.control }; val cs = out.filter { !it.control }
    log("== ${t.name}: ${cs.count { it.green }} green / ${cs.count { !it.green }} red in ${s()} s; control ${ctl.joinToString { if (it.green) "green" else "red" }}; " +
        "settle: ${cs.sumOf { it.settleReq }} requests, %.1f s".format(cs.sumOf { it.settleS }))
    return out
}

fun shaDir() = System.getenv("SHA_DIR")?.let { File(it) } ?: File(HERE_DIR, "../../../core/src/test/resources/same-route").canonicalFile

fun shaLine(c: Case) = "${c.name.substringBefore(' ')}\t${c.code}\t${c.coldHash}\t${Math.round(c.coldM)}\n"

fun verify(engine: BRouterEngine, names: Set<String>?): Boolean {
    val dir = shaDir()
    var ok = true; var n = 0
    val coldCache = LegCache(maxLinePoints = 600_000)
    for (f in (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".sha") }.sortedBy { it.name }) {
        val tour = f.name.removeSuffix(".sha")
        if (names != null && tour !in names) continue
        for (line in f.readLines().filter { it.isNotBlank() }) {
            val (case, code, hash, dist) = line.split('\t').let { it + List(4 - it.size) { "" } }
            val plan = PlanCode.decode(code)
            if (plan == null) { log("RED $tour $case: code does not decode"); ok = false; continue }
            val exp = PlanAnchors.expand(plan.points, plan.anchors, plan.pinned)
            val r = Planner(engine, legs = coldCache).plan(exp, plan.activity, plan.sac)
            val h = sha(r.points)
            val d = dist.toDoubleOrNull() ?: -1.0
            val dOk = d > 0 && Math.abs(r.distanceM - d) / d <= 0.005
            val good = clean(r) && h == hash && dOk
            if (!good) ok = false
            n++
            log("${if (good) "GREEN" else "RED"} $tour $case: hash ${if (h == hash) "equal" else "DIFFERENT"}, distance %.0f m (file %.0f m)".format(r.distanceM, d))
        }
    }
    log("verify: $n cases, ${if (ok && n > 0) "ALL GREEN" else "FAILED"}")
    return ok && n > 0
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val engine = BRouterEngine(FakeCtx(files), SEG_DIR)
    val names = System.getenv("TOURS")?.split(',')?.map { it.trim() }?.toSet()
    if (System.getenv("MODE") == "verify") { if (!verify(engine, names)) System.exit(1); return }
    val nAdds = System.getenv("ADDS")?.toInt() ?: 20
    val t0 = System.nanoTime()
    val all = ArrayList<Case>()
    val perTour = LinkedHashMap<String, List<Case>>()
    for (t in TOURS) {
        if (names != null && t.name !in names) continue
        val cases = try { runTour(engine, t, nAdds) } catch (e: Exception) {
            // expected: a tour that throws is reported as a red case, the other tours still run
            log("TOUR ${t.name} threw: $e"); e.printStackTrace(System.out)
            listOf(Case(t.name, "tour", 0, "", "", "", 0.0, 0.0, 0, 0, listOf("threw $e")))
        }
        perTour[t.name] = cases; all += cases
    }
    log("\n==== summary (%.0f min) ====".format((System.nanoTime() - t0) / 6e10))
    for ((tour, cs) in perTour) {
        val real = cs.filter { !it.control }
        log("$tour: ${real.count { it.green }} green / ${real.count { !it.green }} red; control (no anchors, not counted) ${cs.filter { it.control }.joinToString { if (it.green) "green" else "red" }}; " +
            "settle ${real.sumOf { it.settleReq }} requests, %.1f s in all".format(real.sumOf { it.settleS }))
    }
    log("routed requests on the warm side (first run): " + all.filter { it.code.isNotEmpty() }.groupingBy { it.routedWarm }.eachCount().toSortedMap())
    val red = all.filter { !it.green && !it.control }
    if (red.isNotEmpty()) {
        log("\nMISMATCHES")
        for (c in red) log("${c.tour} | ${c.name} | anchors ${c.anchors}\n    " + c.problems.joinToString("\n    "))
    }
    if (CANON) {
        log("\n==== CANON summary (stitched cases only, the control 'base' excluded; the control is reported on its own) ====")
        for ((tour, cs) in perTour) {
            val st = cs.filter { !it.name.startsWith("base") }; val ctl = cs.filter { it.name.startsWith("base") }
            val adds = st.filter { it.name.startsWith("add") }
            val vis = st.sumOf { it.visiblePts }
            log("$tour: stitched ${st.count { it.green }} green / ${st.count { !it.green }} red; control ${ctl.joinToString { if (it.green) "green" else "red" }}")
            log("   re-routed legs per case (adds): %.1f requests, %.1f s on average, max %d requests / %.1f s; cut legs with a moved end kept: %d in all; all cases %d requests, %.1f s".format(
                adds.map { it.extraReq }.average(), adds.map { it.extraS }.average(), adds.maxOfOrNull { it.extraReq } ?: 0, adds.maxOfOrNull { it.extraS } ?: 0.0,
                st.sumOf { it.keptCut }, st.sumOf { it.extraReq }, st.sumOf { it.extraS }))
            log("   visible points the cold route moves again: %d of %d (in %d of %d cases)".format(st.sumOf { it.coldMoved }, vis, st.count { it.coldMoved > 0 }, st.size))
        }
    }
    val full = names == null && nAdds == 20 && perTour.size == TOURS.size
    if (CANON) log("CANON run: no .sha files are written")
    else if (red.isEmpty() && full && System.getenv("WRITE_SHA") != "0") {
        val dir = shaDir().apply { mkdirs() }
        for ((tour, cs) in perTour) File(dir, "$tour.sha").writeText(cs.joinToString("") { shaLine(it) })
        log("wrote ${perTour.size} .sha files to $dir")
    } else if (System.getenv("SHA_SELFTEST") != null) {
        // only to try the verify path while some cases are red: the green cases go to that dir (outside the repo), never a result
        val dir = File(System.getenv("SHA_SELFTEST")).apply { mkdirs() }
        for ((tour, cs) in perTour) File(dir, "$tour.sha").writeText(cs.filter { it.green }.joinToString("") { shaLine(it) })
        log("self-test: wrote the green cases to $dir")
    } else log(if (red.isEmpty()) "all green, but not the full corpus: no .sha files written" else "RED: no .sha files written")
    if (red.isNotEmpty()) System.exit(1)
}

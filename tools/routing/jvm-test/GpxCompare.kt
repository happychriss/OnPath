import com.onpath.app.routing.*
import java.io.File
import java.io.PrintWriter
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.cos
import kotlin.math.sqrt

/*
 * GpxCompare – OnPath's bike routing against a reference GPX line (a route planned in another app, or a recorded
 * ride), through the app's real path: Planner.plan → BRouterEngine with the shipped profiles and via correction.
 *
 *   RAW_TAP=1 KTTEST=tools/routing/jvm-test/GpxCompare.kt CMP_GPX=<ref.gpx> CMP_OUT=<dir> \
 *     [ASSETS_DIR=<variant assets>] [JAVA_XMX=512m] tools/routing/jvm-test/run.sh <segmentsDir> <workDir>
 * then  python3 tools/routing/jvm-test/gpx_compare_report.py <CMP_OUT> ...   (metrics, deviations, plots, report.md)
 *
 * What it does:
 * 1. Reads the reference line (trkpt / rtept; points closer than 5 m to the last kept one are dropped: GPS jitter
 *    at a standstill). Cuts it into pieces of ~CMP_PIECE_KM (equal length).
 * 2. For every piece, activity (CMP_ACTS) and via density (CMP_VIA_KM; 0 = start and end only = the router's own
 *    choice, 5 = a via point every ~5 km) plans the piece like the app does with a plan of those points. Via points are
 *    reference points, i.e. on a way.
 * 3. Traces the reference line once per piece (via points every CMP_TRACE_M along it, profile trace.brf: every way
 *    costs the same) to learn its BRouter way tags; cached in CMP_TRACE_DIR, reused by later runs (variants).
 *    A trace run that times out or returns an empty track counts as failed: its chunk is split, a leg that still fails
 *    is a gap (traceGaps).
 * 4. Samples every line every 10 m and writes, per sample: arc position, lat, lon, way tags (id into the file's tag
 *    table), distance to the other line and the arc position of the nearest point there. The report script derives
 *    overlap, way-type / surface mixes and deviations from these files.
 *
 * Env: CMP_GPX, CMP_OUT (required); CMP_NAME (default: file name), CMP_ACTS (BIKE_TOURING,BIKE_TREKKING),
 *   CMP_PIECE_KM (40), CMP_VIA_KM (0,5), CMP_PIECES (all; "3-9" or "1,4"), CMP_PART (file suffix for parallel runs),
 *   CMP_TRACE_M (150), CMP_TRACE_DIR (CMP_OUT/<name>/trace).
 * Output under CMP_OUT/<name>/: pieces<part>.tsv (piece, ref km, trace fidelity), runs<part>.tsv (one plan each),
 *   samples/ref_<piece>.tsv.gz, samples/ours_<piece>_<act>_<km>.tsv.gz, samples/refx_<piece>_<act>_<km>.tsv.gz.
 * Reference data can be private (another app's export): keep CMP_OUT outside the repo.
 */

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

const val SAMPLE_M = 10.0
const val NEAR_M = 20.0
const val FAR_M = 999.0

fun dist(a: GeoPoint, b: GeoPoint): Double {
    val x = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
    val y = Math.toRadians(b.lat - a.lat)
    return sqrt(x * x + y * y) * 6_371_000.0
}

fun cum(pts: List<GeoPoint>): DoubleArray {
    val c = DoubleArray(pts.size)
    for (i in 1 until pts.size) c[i] = c[i - 1] + dist(pts[i - 1], pts[i])
    return c
}

fun readGpx(f: File): List<GeoPoint> {
    val out = ArrayList<GeoPoint>()
    val tag = Regex("<(?:trkpt|rtept)\\b[^>]*>")
    val lat = Regex("\\blat=\"([-0-9.]+)\""); val lon = Regex("\\blon=\"([-0-9.]+)\"")
    for (m in tag.findAll(f.readText())) {
        val p = GeoPoint(lat.find(m.value)!!.groupValues[1].toDouble(), lon.find(m.value)!!.groupValues[1].toDouble())
        if (out.isEmpty() || dist(out.last(), p) >= 5.0) out += p
    }
    return out
}

/** A polyline in parts; [tags] per span (part p, point i → i+1), null = unknown. */
class Lines(val parts: List<List<GeoPoint>>, val tags: List<List<String?>>) {
    val cums = parts.map { cum(it) }
    val length = cums.sumOf { if (it.isEmpty()) 0.0 else it.last() }
}

/** Grid index of a polyline's segments: nearest distance and the arc position (over all parts) there. */
class SegIndex(private val lines: Lines) {
    private val cell = 0.002
    private val grid = HashMap<Long, MutableList<Int>>()
    private val segs = ArrayList<IntArray>() // part, index
    private val partStart = DoubleArray(lines.parts.size)

    private fun key(a: Int, b: Int) = a.toLong() * 1_000_000L + b

    init {
        var off = 0.0
        lines.parts.forEachIndexed { p, pts ->
            partStart[p] = off; off += lines.cums[p].lastOrNull() ?: 0.0
            for (i in 0 until pts.size - 1) {
                val s = segs.size; segs += intArrayOf(p, i)
                val a = pts[i]; val b = pts[i + 1]
                val la0 = fl(minOf(a.lat, b.lat)); val la1 = fl(maxOf(a.lat, b.lat))
                val lo0 = fl(minOf(a.lon, b.lon)); val lo1 = fl(maxOf(a.lon, b.lon))
                for (x in la0..la1) for (y in lo0..lo1) grid.getOrPut(key(x, y)) { ArrayList(2) }.add(s)
            }
        }
    }

    private fun fl(v: Double) = kotlin.math.floor(v / cell).toInt()

    /** (distance m, arc m, segment id) of the nearest segment within ~150 m; distance [FAR_M] when none. */
    fun nearest(q: GeoPoint): Triple<Double, Double, Int> {
        val cx = kotlin.math.floor(q.lat / cell).toInt(); val cy = kotlin.math.floor(q.lon / cell).toInt()
        var best = FAR_M; var arc = -1.0; var bestSeg = -1
        val kx = 6_371_000.0 * Math.toRadians(1.0) * cos(Math.toRadians(q.lat)); val ky = 6_371_000.0 * Math.toRadians(1.0)
        for (x in cx - 1..cx + 1) for (y in cy - 2..cy + 2) {
            val l = grid[key(x, y)] ?: continue
            for (s in l) {
                val (p, i) = segs[s].let { it[0] to it[1] }
                val a = lines.parts[p][i]; val b = lines.parts[p][i + 1]
                val ax = (a.lon - q.lon) * kx; val ay = (a.lat - q.lat) * ky
                val bx = (b.lon - q.lon) * kx; val by = (b.lat - q.lat) * ky
                val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
                val t = if (len2 <= 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val px = ax + t * dx; val py = ay + t * dy
                val d = sqrt(px * px + py * py)
                if (d < best) {
                    best = d; bestSeg = s
                    arc = partStart[p] + lines.cums[p][i] + t * (lines.cums[p][i + 1] - lines.cums[p][i])
                }
            }
        }
        return Triple(best, arc, bestSeg)
    }

    fun tagOf(seg: Int): String? = segs[seg].let { lines.tags[it[0]][it[1]] }
}

/** A sample every [SAMPLE_M] along all parts: point, arc position, tag of its span. */
data class Sample(val p: GeoPoint, val arc: Double, val tag: String?)

fun sample(lines: Lines): List<Sample> {
    val out = ArrayList<Sample>()
    var off = 0.0
    lines.parts.forEachIndexed { pi, pts ->
        val c = lines.cums[pi]
        if (pts.size < 2) return@forEachIndexed
        var m = 0.0; var i = 0
        val end = c.last()
        while (m <= end) {
            while (i < pts.size - 2 && c[i + 1] < m) i++
            val segLen = c[i + 1] - c[i]
            val t = if (segLen <= 0) 0.0 else ((m - c[i]) / segLen).coerceIn(0.0, 1.0)
            val a = pts[i]; val b = pts[i + 1]
            out += Sample(GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)), off + m, lines.tags[pi][i])
            m += SAMPLE_M
        }
        off += end
    }
    return out
}

/** Writes samples with an extra column block per row; tags as ids ("T id tags" lines first). */
fun writeSamples(f: File, s: List<Sample>, extra: (Int) -> String) {
    f.parentFile.mkdirs()
    val ids = LinkedHashMap<String, Int>()
    for (x in s) x.tag?.let { ids.getOrPut(it) { ids.size } }
    PrintWriter(GZIPOutputStream(f.outputStream()).bufferedWriter()).use { w ->
        for ((t, id) in ids) w.println("T\t$id\t$t")
        s.forEachIndexed { k, x ->
            w.println(String.format(Locale.ROOT, "%.1f\t%.6f\t%.6f\t%d\t%s", x.arc, x.p.lat, x.p.lon,
                x.tag?.let { ids[it] } ?: -1, extra(k)))
        }
    }
}

fun mkey(lon: Int, lat: Int) = (lon.toLong() shl 32) or (lat.toLong() and 0xffffffffL)
fun mkey(p: GeoPoint) = mkey(Math.round(p.lon * 1e6).toInt(), Math.round(p.lat * 1e6).toInt())

/** Raw way tags per span from BRouter's aggregated messages (as BRouterWayStats.perNode, but the tag text). */
fun spanTags(lon: IntArray, lat: IntArray, messages: List<String?>, into: MutableMap<Pair<Long, Long>, String>) {
    val n = lon.size
    var k = 0
    for (line in messages) {
        if (line.isNullOrEmpty()) continue
        val cols = line.split('\t')
        if (cols.size < 10) continue
        val mlon = cols[0].trim().toIntOrNull() ?: continue
        val mlat = cols[1].trim().toIntOrNull() ?: continue
        // BRouter can report a zero-length run at the current node (seen at the start of a track): nothing to assign
        if (lon[k] == mlon && lat[k] == mlat) continue
        var j = k + 1
        while (j < n && (lon[j] != mlon || lat[j] != mlat)) j++
        if (j >= n) continue // not on the track (should not happen): skip this run, keep the rest
        val t = cols[9].trim().intern()
        for (i in k until j) into[mkey(lon[i], lat[i]) to mkey(lon[i + 1], lat[i + 1])] = t
        k = j
    }
}

// ---- trace: BRouter directly with trace.brf (a measuring tool, not the app path) -----------------------------

class Tracer(private val segs: File, private val profile: File) {
    fun route(pts: List<GeoPoint>): Pair<List<GeoPoint>, List<String?>> {
        val rc = btools.router.RoutingContext()
        rc.localFunction = profile.absolutePath
        rc.keyValues = hashMapOf("processUnusedTags" to "1", "correctMisplacedViaPoints" to "1",
            "correctMisplacedViaPointsDistance" to "100", "use_dynamic_range" to "0", "waypointCatchingRange" to "100",
            "turnInstructionMode" to "0")
        val nodes = pts.mapIndexed { i, p ->
            btools.router.OsmNodeNamed().apply {
                name = if (i == 0) "from" else if (i == pts.size - 1) "to" else "via$i"
                ilon = ((p.lon + 180.0) * 1e6 + 0.5).toInt(); ilat = ((p.lat + 90.0) * 1e6 + 0.5).toInt()
            }
        }
        val e = btools.router.RoutingEngine(null, null, segs, nodes, rc)
        e.quite = true
        e.doRun(120_000)
        // a timed-out run can leave an error and a degenerate (empty) track: count it as a failure, so the chunk is
        // split and a leg that still fails is counted as a gap – not as a traced piece
        if (e.errorMessage != null) error(e.errorMessage!!)
        val track = e.foundTrack ?: error("no track")
        if (track.nodes.size < 2) error("empty track")
        val els = track.nodes
        val lon = IntArray(els.size) { els[it].getILon() - 180_000_000 }; val lat = IntArray(els.size) { els[it].getILat() - 90_000_000 }
        val m = HashMap<Pair<Long, Long>, String>()
        spanTags(lon, lat, track.aggregateMessages(), m)
        val p = (0 until els.size).map { GeoPoint(lat[it] / 1e6, lon[it] / 1e6) }
        return p to (0 until p.size - 1).map { m[mkey(lon[it], lat[it]) to mkey(lon[it + 1], lat[it + 1])] }
    }

    /** Routes [pts] in chunks; a failing chunk is halved down to single legs, a failing leg is left out (gap). */
    fun trace(pts: List<GeoPoint>, out: MutableList<Pair<List<GeoPoint>, List<String?>>>, failed: IntArray) {
        if (pts.size < 2) return
        if (pts.size > 41) {
            var i = 0
            while (i < pts.size - 1) { val j = minOf(i + 40, pts.size - 1); trace(pts.subList(i, j + 1), out, failed); i = j }
            return
        }
        try { out += route(pts) } catch (e: Exception) {
            // expected: a via point the trace profile cannot reach (data gap) – split and keep the rest
            if (pts.size == 2) { failed[0]++; return }
            val mid = pts.size / 2
            trace(pts.subList(0, mid + 1), out, failed); trace(pts.subList(mid, pts.size), out, failed)
        }
    }
}

/** Metres of [pts] ridden again in the opposite direction (out-and-back spikes; as ViaTest.backtrackM). */
fun backtrackM(pts: List<GeoPoint>): Double {
    val seen = HashSet<Pair<Long, Long>>()
    for (i in 1 until pts.size) seen.add(mkey(pts[i - 1]) to mkey(pts[i]))
    var m = 0.0
    for (i in 1 until pts.size) if (seen.contains(mkey(pts[i]) to mkey(pts[i - 1]))) m += dist(pts[i - 1], pts[i])
    return m
}

/** Reference points every ~[stepM] along [pts] (first and last always), the interior ones shifted by [shiftM]. */
fun pick(pts: List<GeoPoint>, c: DoubleArray, stepM: Double, shiftM: Double = 0.0): List<GeoPoint> {
    if (stepM <= 0) return listOf(pts.first(), pts.last())
    val out = arrayListOf(pts.first())
    var next = stepM + shiftM; val end = c.last()
    for (i in 1 until pts.size - 1) if (c[i] >= next && end - c[i] > stepM * 0.5) { out += pts[i]; next = c[i] + stepM }
    out += pts.last()
    return out
}

fun parseSet(s: String?, n: Int): List<Int> {
    if (s.isNullOrBlank() || s == "all") return (0 until n).toList()
    return s.split(',').flatMap { r ->
        if ('-' in r) { val (a, b) = r.split('-').map { it.trim().toInt() }; (a..minOf(b, n - 1)).toList() } else listOf(r.trim().toInt())
    }.filter { it in 0 until n }
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val segs = File("/home/user/build/routing-test/segments")
    val eng = BRouterEngine(FakeCtx(files), segs)
    val gpx = File(System.getenv("CMP_GPX") ?: error("set CMP_GPX"))
    val name = System.getenv("CMP_NAME") ?: gpx.nameWithoutExtension.replace(Regex("[^A-Za-z0-9]+"), "_").take(40)
    val out = File(System.getenv("CMP_OUT") ?: error("set CMP_OUT"), name).apply { mkdirs() }
    val acts = (System.getenv("CMP_ACTS") ?: "BIKE_TOURING,BIKE_TREKKING").split(',').map { Activity.valueOf(it.trim()) }
    val pieceKm = (System.getenv("CMP_PIECE_KM") ?: "40").toDouble()
    val viaKms = (System.getenv("CMP_VIA_KM") ?: "0,5").split(',').map { it.trim().toDouble() }
    val traceM = (System.getenv("CMP_TRACE_M") ?: "150").toDouble()
    val traceDir = File(System.getenv("CMP_TRACE_DIR") ?: File(out, "trace").path).apply { mkdirs() }
    val part = System.getenv("CMP_PART") ?: ""
    // trace profile next to a copy of the lookups it needs (BRouter reads lookups.dat from the profile's folder)
    val tdir = File(files, "trace").apply { mkdirs() }
    File("/home/user/onpath/app/src/main/assets/brouter/lookups.dat").copyTo(File(tdir, "lookups.dat"), true)
    File("@JVMTEST_DIR@/trace.brf").copyTo(File(tdir, "trace.brf"), true)
    val tracer = Tracer(segs, File(tdir, "trace.brf"))

    val ref = readGpx(gpx)
    val rc = cum(ref)
    val n = maxOf(1, Math.round(rc.last() / 1000.0 / pieceKm).toInt())
    val bounds = (0..n).map { k -> val target = rc.last() * k / n; rc.indices.minByOrNull { kotlin.math.abs(rc[it] - target) }!! }
    System.err.println(String.format(Locale.ROOT, "%s: %.1f km, %d points, %d pieces", name, rc.last() / 1000, ref.size, n))

    val piecesTsv = PrintWriter(File(out, "pieces$part.tsv").bufferedWriter())
    piecesTsv.println("name\tpiece\trefKm\ttraceKm\ttraceFid\ttraceBack\ttraceGaps\ttraceMs")
    val runsTsv = PrintWriter(File(out, "runs$part.tsv").bufferedWriter())
    runsTsv.println("name\tpiece\tact\tviaKm\tnVias\trefKm\tourKm\tascentM\tdurS\tms\tstraightLegs\ttagged\tappAttrs\tbackM\tnudge\terror")
    val tap = HashMap<Pair<Long, Long>, String>()
    RawTap.sink = { lon, lat, msg -> spanTags(lon, lat, msg, tap) }

    for (k in parseSet(System.getenv("CMP_PIECES"), n)) {
        val pts = ref.subList(bounds[k], bounds[k + 1] + 1)
        val pc = cum(pts)
        val refLines = Lines(listOf(pts), listOf(List(pts.size - 1) { null }))
        // trace (cached): "lat lon tag" per point, parts separated by blank lines
        val tf = File(traceDir, "trace_$k.txt")
        val t0 = System.currentTimeMillis()
        val failed = IntArray(1)
        if (!tf.isFile || tf.length() == 0L) {
            val parts = ArrayList<Pair<List<GeoPoint>, List<String?>>>()
            if (traceM > 0) tracer.trace(pick(pts, pc, traceM), parts, failed)
            tf.printWriter().use { w ->
                w.println("#gaps\t${failed[0]}")
                for ((pp, tt) in parts) {
                    pp.forEachIndexed { i, p -> w.println(String.format(Locale.ROOT, "%.6f\t%.6f\t%s", p.lat, p.lon, tt.getOrNull(i) ?: "")) }
                    w.println()
                }
            }
        }
        val tparts = ArrayList<List<GeoPoint>>(); val ttags = ArrayList<List<String?>>()
        run {
            var cp = ArrayList<GeoPoint>(); var ct = ArrayList<String?>()
            for (l in tf.readLines()) {
                if (l.startsWith("#gaps")) { failed[0] = l.split('\t')[1].toInt(); continue }
                if (l.isBlank()) { if (cp.size >= 2) { tparts += cp; ttags += ct.subList(0, cp.size - 1) }; cp = ArrayList(); ct = ArrayList(); continue }
                val c = l.split('\t')
                cp += GeoPoint(c[0].toDouble(), c[1].toDouble()); ct += c.getOrNull(2)?.ifEmpty { null }
            }
            if (cp.size >= 2) { tparts += cp; ttags += ct.subList(0, cp.size - 1) }
        }
        val traced = Lines(tparts, ttags)
        val tIdx = SegIndex(traced)
        val refIdx = SegIndex(refLines)
        val rs = sample(refLines)
        // reference tags = tags of the traced way it lies on (within NEAR_M), else unknown
        val rsTagged = rs.map { s -> val (d, _, seg) = tIdx.nearest(s.p); s.copy(tag = if (d <= NEAR_M && seg >= 0) tIdx.tagOf(seg) else null) }
        val dTrace = rs.map { tIdx.nearest(it.p).first }
        val fid = dTrace.count { it <= NEAR_M }.toDouble() / maxOf(1, rs.size)
        val ts = sample(traced)
        val back = ts.count { refIdx.nearest(it.p).first <= NEAR_M }.toDouble() / maxOf(1, ts.size)
        writeSamples(File(out, "samples/ref_$k.tsv.gz"), rsTagged) { String.format(Locale.ROOT, "%.1f", dTrace[it]) }
        piecesTsv.println(String.format(Locale.ROOT, "%s\t%d\t%.3f\t%.3f\t%.4f\t%.4f\t%d\t%d", name, k, pc.last() / 1000,
            traced.length / 1000, fid, back, failed[0], System.currentTimeMillis() - t0))
        piecesTsv.flush()

        for (act in acts) for (vk in viaKms) {
            // A via point exactly where a bridge crosses another way can snap onto the wrong one and force a long
            // out-and-back: a snapping artefact, not a routing choice. Then the interior vias move 150 m / 300 m along
            // the reference and the plan with the least backtracking is kept (column "nudge").
            var vias = pick(pts, pc, vk * 1000)
            var plan: PlannedRoute? = null; var back = 0.0; var nudge = 0; var ms = 0L
            var keptTap: Map<Pair<Long, Long>, String> = emptyMap()
            for (n in 0..(if (vias.size > 2) 2 else 0)) {
                val v = if (n == 0) vias else pick(pts, pc, vk * 1000, 150.0 * n)
                tap.clear()
                val t1 = System.currentTimeMillis()
                val p = try { Planner(eng).plan(v, act, 3) } catch (e: Exception) {
                    System.err.println("piece $k $act $vk: ${e.message}"); null
                }
                val b = p?.let { backtrackM(it.points) } ?: Double.MAX_VALUE
                if (n == 0 || b < back) {
                    plan = p; back = b; nudge = n; vias = v; ms = System.currentTimeMillis() - t1; keptTap = HashMap(tap)
                }
                if (back <= 300.0) break
            }
            tap.clear(); tap.putAll(keptTap)
            val ourPts = plan?.points ?: emptyList()
            val ourTags = (0 until maxOf(0, ourPts.size - 1)).map { tap[mkey(ourPts[it]) to mkey(ourPts[it + 1])] }
            val ours = Lines(listOf(ourPts), listOf(ourTags))
            val os = if (ourPts.size >= 2) sample(ours) else emptyList()
            val oIdx = SegIndex(ours)
            val o2r = os.map { refIdx.nearest(it.p) }
            val r2o = rs.map { oIdx.nearest(it.p) }
            val tag = "${k}_${act}_${vk.toInt()}"
            writeSamples(File(out, "samples/ours_$tag.tsv.gz"), os) { String.format(Locale.ROOT, "%.1f\t%.1f", o2r[it].first, o2r[it].second) }
            File(out, "samples/refx_$tag.tsv.gz").let { f ->
                PrintWriter(GZIPOutputStream(f.outputStream()).bufferedWriter()).use { w ->
                    r2o.forEach { w.println(String.format(Locale.ROOT, "%.1f\t%.1f", it.first, it.second)) }
                }
            }
            val tagged = if (ourTags.isEmpty()) 0.0 else ourTags.count { it != null }.toDouble() / ourTags.size
            val appAttrs = if (ourPts.size < 2) 0.0 else ourPts.dropLast(1).count { it.way != null }.toDouble() / (ourPts.size - 1)
            runsTsv.println(String.format(Locale.ROOT, "%s\t%d\t%s\t%.0f\t%d\t%.3f\t%.3f\t%.0f\t%.0f\t%d\t%d\t%.3f\t%.3f\t%.0f\t%d\t%s", name, k, act, vk,
                vias.size, pc.last() / 1000, (plan?.distanceM ?: 0.0) / 1000, plan?.ascentM ?: 0.0, plan?.durationS ?: 0.0, ms,
                plan?.legs?.count { it.straight } ?: -1, tagged, appAttrs, if (back == Double.MAX_VALUE) -1.0 else back, nudge,
                (plan?.error ?: "").replace('\t', ' ')))
            runsTsv.flush()
            System.err.println(String.format(Locale.ROOT, "piece %d %s via %.0f: %.1f km (ref %.1f) %d ms %s", k, act, vk,
                (plan?.distanceM ?: 0.0) / 1000, pc.last() / 1000, ms, plan?.error ?: ""))
        }
    }
    piecesTsv.close(); runsTsv.close()
}

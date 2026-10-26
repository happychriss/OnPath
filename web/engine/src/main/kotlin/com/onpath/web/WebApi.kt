package com.onpath.web

import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.BRouterEngine
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.Planner
import com.onpath.app.routing.SharedPlan
import com.onpath.app.routing.RouteSummary
import com.onpath.app.routing.WaypointInsertion
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RoutingException
import com.onpath.app.routing.Share
import com.onpath.app.time.RouteTimes
import org.teavm.jso.JSExport
import org.teavm.jso.typedarrays.Int8Array
import java.io.File
import java.io.FileOutputStream

/**
 * The web engine's whole API (docs/design/web-planner.md §6). The page (in a Web Worker) first hands over the files
 * BRouter reads – profiles under `<root>/assets/brouter/`, rd5 data under `<root>/segments/` – then asks for routes.
 * Everything behind it is the app's own code ([BRouterEngine], [RouteTimes]), unchanged. The same functions run on
 * the JVM for the comparison test (web/test), so both sides produce the same JSON from the same code.
 */
object Engine {
    private var root = File("/onpath")
    private var engine: BRouterEngine? = null

    fun init(rootDir: String) {
        root = File(rootDir)
        android.content.res.AssetManager.root = File(root, "assets")
        engine = null
    }

    fun putFile(relPath: String, bytes: ByteArray) {
        val f = File(root, relPath)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { it.write(bytes) }
    }

    private fun engine(): BRouterEngine = engine ?: BRouterEngine(WebContext(File(root, "files")), File(root, "segments"))
        .also { engine = it }

    private fun parsePoints(points: String): List<GeoPoint> = points.split(';').filter { it.isNotBlank() }.map { p ->
        val (la, lo) = p.split(',').map { it.trim().toDouble() }
        GeoPoint(la, lo)
    }

    /**
     * The 1° parts a plan needs – the phone's rule ([Rd5Parts.forCorridor]): JSON `[["E10_N45.rd5", 6], …]`.
     * Parts already in place are left out.
     */
    fun partsNeeded(points: String): String =
        Rd5Parts.forCorridor(parsePoints(points)).filter { it !in have }
            .joinToString(",", "[", "]") { "[" + str(it.tile) + "," + it.index + "]" }

    private val have = HashSet<Rd5Parts.Part>()
    private val layouts = HashMap<String, Pair<Rd5Parts.Index, ByteArray>>()
    private val partBytes = HashMap<String, MutableMap<Int, ByteArray>>()

    /** Remote layout of [tile]: its first 200 bytes and its tail (bytes after the last part). */
    fun putLayout(tile: String, index: ByteArray, tail: ByteArray) {
        val ix = Rd5Parts.Index(index)
        ix.check()
        layouts[tile] = ix to tail
    }

    /**
     * One downloaded part; then the local rd5 for [tile] is rewritten with every part we have (the phone does the
     * same with [Rd5Parts.buildIndex] / [Rd5Parts.buildTail]). Returns false when the bytes do not fit the layout.
     */
    fun putPart(tile: String, index: Int, bytes: ByteArray): Boolean {
        val (ix, tail) = layouts[tile] ?: return false
        if (bytes.size.toLong() != ix.size(index)) return false
        val parts = partBytes.getOrPut(tile) { HashMap() }
        parts[index] = bytes
        val present = parts.keys.filter { ix.size(it) > 0 }.toSortedSet()
        val newIndex = Rd5Parts.buildIndex(ix, present)
        val newTail = Rd5Parts.buildTail(ix, tail, newIndex)
        val f = File(root, "segments/$tile")
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { out ->
            out.write(newIndex)
            for (i in present) out.write(parts.getValue(i))
            out.write(newTail)
        }
        have += Rd5Parts.Part.of(tile, index)
        engine = null // BRouter caches open files; a fresh engine reads the new file
        return true
    }

    /** [points] = "lat,lon;lat,lon;…", [activity] = Activity name. Returns JSON (route or {"error": …}). */
    fun route(points: String, activity: String, sac: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            val wps = parsePoints(points)
            val act = Activity.valueOf(activity)
            val r = engine().route(wps, act, sac)
            val ms = System.currentTimeMillis() - t0
            val times = RouteTimes.of(r.points, act)
            buildString {
                append("{\"distanceM\":").append(num(r.distanceM))
                append(",\"ascentM\":").append(num(r.ascentM))
                append(",\"descentM\":").append(num(r.descentM))
                append(",\"durationS\":").append(num(r.durationS))
                append(",\"timeS\":").append(num(times.total()))
                append(",\"ms\":").append(ms)
                append(",\"wayTypes\":").append(shares(r.wayTypes))
                append(",\"surfaces\":").append(shares(r.surfaces))
                append(",\"points\":[")
                r.points.forEachIndexed { i, p ->
                    if (i > 0) append(',')
                    append('[').append(num(p.lat, 6)).append(',').append(num(p.lon, 6)).append(',')
                        .append(p.ele?.let { num(it, 1) } ?: "null").append(']')
                }
                append("]}")
            }
        } catch (e: RoutingException) {
            "{\"error\":" + str(e.message ?: "") + ",\"ms\":" + (System.currentTimeMillis() - t0) + "}"
        } catch (e: Exception) {
            Problems.error("web", "Route request failed", e)
            "{\"error\":" + str("Unexpected: $e") + "}"
        }
    }

    private var lastPlan: Pair<List<GeoPoint>, PlannedRoute>? = null

    /**
     * Plans like the phone's plan sheet: the app's [Planner] (leg by leg, a failed leg becomes a straight dashed
     * line with a plain message), times from [RouteTimes], difficulty from [RouteSummary].
     */
    fun plan(points: String, activity: String, sac: Int, pinned: String = ""): String {
        val t0 = System.currentTimeMillis()
        return try {
            val wps = parsePoints(points)
            val act = Activity.valueOf(activity)
            val p = Planner(engine()).plan(wps, act, sac, parseIndices(pinned))
            lastPlan = wps to p
            val times = if (p.points.size >= 2) RouteTimes.of(p.points, act).total() else 0.0
            buildString {
                append("{\"distanceM\":").append(num(p.distanceM))
                append(",\"ascentM\":").append(num(p.ascentM))
                append(",\"descentM\":").append(num(p.descentM))
                append(",\"timeS\":").append(num(times))
                append(",\"difficulty\":").append(str(RouteSummary.difficulty(act, p.distanceM, p.ascentM).label))
                append(",\"error\":").append(p.error?.let { str(it) } ?: "null")
                append(",\"ms\":").append(System.currentTimeMillis() - t0)
                append(",\"wayTypes\":").append(shares(p.wayTypes))
                append(",\"surfaces\":").append(shares(p.surfaces))
                profileJson(p.points)
                append(",\"legs\":[")
                p.legs.forEachIndexed { li, leg ->
                    if (li > 0) append(',')
                    append("{\"straight\":").append(leg.straight).append(",\"points\":")
                    pointsJson(leg.points)
                    append('}')
                }
                append("]}")
            }
        } catch (e: Exception) {
            Problems.error("web", "Plan request failed", e)
            "{\"error\":" + str("Unexpected: $e") + ",\"legs\":[]}"
        }
    }

    /**
     * Where a clicked point goes – the phone's rule ([WaypointInsertion.decide]): on the line → via in that leg,
     * else cheapest insertion or new destination. Returns the index in the point list.
     */
    fun insertIndex(points: String, lat: Double, lon: Double, lineSnapM: Double): Int {
        val wps = parsePoints(points)
        val route = lastPlan?.takeIf { it.first == wps }?.second?.points
        return WaypointInsertion.decide(wps, GeoPoint(lat, lon), route, lineSnapM).index
    }

    /**
     * Elevation profile like the app's (ui/ElevationProfile `profileData`: points with elevation, distance along
     * the whole line via [Geo.cumulative]) with the app's gradient classes ([RouteSummary.steepness], 100 m window).
     */
    private fun StringBuilder.profileJson(points: List<GeoPoint>) {
        val cum = if (points.size >= 2) Geo.cumulative(points) else DoubleArray(0)
        val keep = points.indices.filter { points[it].ele != null }
        val d = DoubleArray(keep.size) { cum[keep[it]] }
        val e = DoubleArray(keep.size) { points[keep[it]].ele!! }
        val steep = if (keep.size >= 2) RouteSummary.steepness(d, e) else emptyList()
        append(",\"profile\":{\"dist\":").append(d.joinToString(",", "[", "]") { num(it, 1) })
        append(",\"ele\":").append(e.joinToString(",", "[", "]") { num(it, 1) })
        append(",\"steep\":").append(steep.joinToString(",", "[", "]") { it.ordinal.toString() }).append('}')
    }

    private fun StringBuilder.pointsJson(pts: List<GeoPoint>) {
        append('[')
        pts.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append('[').append(num(p.lat, 6)).append(',').append(num(p.lon, 6)).append(',')
                .append(p.ele?.let { num(it, 1) } ?: "null").append(']')
        }
        append(']')
    }

    private fun parseIndices(s: String): Set<Int> = s.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    /**
     * The plan code ([PlanCode], the app's own) for the page's plan. [names] = lines "index<TAB>name" (names never
     * hold control characters, PlanCode drops them).
     */
    fun planCode(points: String, activity: String, sac: Int, pinned: String, names: String, title: String): String =
        PlanCode.encode(SharedPlan(
            parsePoints(points), Activity.valueOf(activity), sac, parseIndices(pinned),
            names.split('\n').mapNotNull { l ->
                val i = l.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
                i to l.substringAfter('\t')
            }.toMap(),
            title.ifBlank { null },
        ))

    /** The plan in a link or code as JSON (points, activity, sac, pinned, names, title), or "null". */
    fun planParse(text: String): String {
        val p = PlanCode.parse(text) ?: return "null"
        return buildString {
            append("{\"points\":[")
            p.points.forEachIndexed { i, q -> if (i > 0) append(','); append('[').append(num(q.lat, 5)).append(',').append(num(q.lon, 5)).append(']') }
            append("],\"activity\":").append(str(p.activity.name))
            append(",\"sac\":").append(p.sac)
            append(",\"pinned\":").append(p.pinned.sorted().joinToString(",", "[", "]"))
            append(",\"names\":").append(p.names.toSortedMap().entries.joinToString(",", "{", "}") { str(it.key.toString()) + ":" + str(it.value) })
            append(",\"title\":").append(p.title?.let { str(it) } ?: "null")
            append('}')
        }
    }

    fun problems(): String = Problems.drain().joinToString(",", "[", "]") { str(it) }

    private fun shares(s: List<Share>) = s.joinToString(",", "[", "]") { "[" + str(it.key) + "," + num(it.meters, 1) + "]" }

    /** Fixed-decimals number without String.format (same text on the JVM and in the browser). */
    private fun num(v: Double, decimals: Int = 2): String {
        if (v.isNaN() || v.isInfinite()) return "null"
        var f = 1L
        repeat(decimals) { f *= 10 }
        val n = Math.round(v * f)
        val sign = if (n < 0) "-" else ""
        val a = Math.abs(n)
        val whole = a / f
        val frac = (a % f).toString().padStart(decimals, '0')
        return if (decimals == 0) "$sign$whole" else "$sign$whole.$frac"
    }

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            else -> if (c < ' ') append(' ') else append(c)
        }
        append('"')
    }
}

/** Context stand-in: BRouterEngine only needs filesDir and assets (stub classes from tools/routing/jvm-test/stubs). */
class WebContext(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

// ---- Exports for the browser (TeaVM) ----

@JSExport fun init(rootDir: String) = Engine.init(rootDir)

@JSExport fun putFile(relPath: String, data: Int8Array) = Engine.putFile(relPath, data.copyToJavaArray())

@JSExport fun partsNeeded(points: String): String = Engine.partsNeeded(points)

@JSExport fun putLayout(tile: String, index: Int8Array, tail: Int8Array) =
    Engine.putLayout(tile, index.copyToJavaArray(), tail.copyToJavaArray())

@JSExport fun putPart(tile: String, index: Int, data: Int8Array): Boolean = Engine.putPart(tile, index, data.copyToJavaArray())

@JSExport fun route(points: String, activity: String, sac: Int): String = Engine.route(points, activity, sac)

@JSExport fun plan(points: String, activity: String, sac: Int, pinned: String): String =
    Engine.plan(points, activity, sac, pinned)

@JSExport fun planCode(points: String, activity: String, sac: Int, pinned: String, names: String, title: String): String =
    Engine.planCode(points, activity, sac, pinned, names, title)

@JSExport fun planParse(text: String): String = Engine.planParse(text)

@JSExport fun insertIndex(points: String, lat: Double, lon: Double, lineSnapM: Double): Int =
    Engine.insertIndex(points, lat, lon, lineSnapM)

@JSExport fun problems(): String = Engine.problems()

fun main() {}

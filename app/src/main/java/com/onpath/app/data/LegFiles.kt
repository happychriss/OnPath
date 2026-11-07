package com.onpath.app.data

import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.JunctionShape
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.LegStore
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.Share
import com.onpath.app.routing.WayAttrs
import java.io.File

/**
 * The phone's leg store (owner 2026-11-07, "saved routes load without routing again"; docs/design/long-bike-routes.md
 * "Saved routes"): every leg the Planner routes is kept as one small file in [dir] (`files/legs/`), with everything the
 * plan uses – the line with its way attributes, junction cues and junction shapes, the router's numbers, the breakdown
 * and the matched ends. A saved route, a Zuletzt draft, a done tour's plan copy or a browser plan loaded after an app
 * restart takes its legs from here: the same line at once, no router run (the S10 took 67 s for a 723 km plan).
 *
 * **When a stored leg is used** ([get] → [LegStore.Stored.fresh]): only for exactly its two points (to 1e-6°), its
 * activity and – for Hike – its T level (the file name is the [LegCache.Key]); only with the same routing fingerprint
 * ([fingerprint]: BRouter version, profiles, PLANNER_VERSION) and only while the routing data under it is the same: the
 * leg records the version of every 1° routing part within [MARGIN_M] of its line and of its straight line ([partVersion]:
 * the rd5's creation time, "-" = not on the phone); a part now newer, newly loaded or gone makes it stale. A stale leg
 * is still given (fresh = false): the app shows it while the leg is routed again, and replaces it then.
 *
 * Kept by use: a read touches the file; beyond [maxFiles] / [maxBytes] the least used go (a saved route whose legs went
 * is routed again on load – its stored line is shown meanwhile). Never throws: an unreadable file is deleted and
 * reported, a failed write is reported (the leg is simply routed next time).
 */
class LegFiles(
    private val dir: File,
    /** This phone's routing fingerprint (RoutingFingerprint); null = unknown – nothing stored counts as fresh. */
    private val fingerprint: () -> String?,
    /** The version of the routing data the phone has for a part ("-" = none; "?" = unreadable – never fresh). */
    private val partVersion: (Rd5Parts.Part) -> String,
    private val maxFiles: Int = MAX_FILES,
    private val maxBytes: Long = MAX_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) : LegStore {
    private val lock = Any()
    /** Bytes and files in [dir] – counted once, then kept up to date by [put] / [prune]. */
    private var bytes = -1L
    private var files = 0

    override fun get(key: LegCache.Key): LegStore.Stored? {
        val f = file(key)
        val text = synchronized(lock) {
            if (!f.isFile) return null
            try { f.readText() } catch (e: Exception) {
                Problems.warn("routing", "A stored leg could not be read – it is routed again", e); return null
            }
        }
        val e = try { decode(text) } catch (e: Exception) {
            Problems.warn("routing", "A stored leg is unreadable – deleted, routed again", e)
            synchronized(lock) { if (f.delete()) forget(text.length.toLong()) }
            return null
        }
        if (e.key != key) {
            Problems.warn("routing", "A stored leg's file belongs to other points – deleted, routed again")
            synchronized(lock) { if (f.delete()) forget(text.length.toLong()) }
            return null
        }
        // expected: a touch that fails only makes the leg look older to the pruning
        try { f.setLastModified(now()) } catch (_: Exception) {}
        return LegStore.Stored(e.leg, isFresh(e))
    }

    override fun put(key: LegCache.Key, from: GeoPoint, to: GeoPoint, leg: RouteResult) {
        val fp = fingerprint() ?: return
        try {
            val parts = Rd5Parts.forCorridor(listOf(from, to), MARGIN_M) + Rd5Parts.forCorridor(leg.points, MARGIN_M)
            val data = parts.sorted().joinToString(";") { "${it.key}=${partVersion(it)}" }
            val text = encode(Entry(key, fp, data, now(), leg))
            synchronized(lock) {
                count()
                val f = file(key)
                val before = if (f.isFile) f.length() else -1L
                AtomicFiles.writeText(f, text, sync = false)
                // expected: a time that is not set only makes the leg look older to the pruning
                try { f.setLastModified(now()) } catch (_: Exception) {}
                if (before >= 0) bytes -= before else files++
                bytes += f.length()
                prune()
            }
        } catch (e: Exception) {
            Problems.warn("routing", "A routed leg could not be stored – it is routed again next time", e)
        }
    }

    /** The routing code and the data under the leg are as when it was routed. */
    private fun isFresh(e: Entry): Boolean {
        val fp = fingerprint() ?: return false
        if (e.fingerprint != fp || fp.isBlank()) return false
        if (e.data.isEmpty()) return false
        for (item in e.data.split(';')) {
            val k = item.substringBefore('=')
            val v = item.substringAfter('=', "?")
            val part = Rd5Parts.Part.parse(k) ?: return false
            if (v == "?" || partVersion(part) != v) return false
        }
        return true
    }

    private fun file(key: LegCache.Key) = File(dir, name(key))

    private fun count() {
        if (bytes >= 0) return
        val all = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
        bytes = all.sumOf { it.length() }; files = all.size
        AtomicFiles.cleanStaleTemps(dir)
    }

    private fun forget(len: Long) { if (bytes >= 0) { bytes -= len; files-- } }

    /** Beyond the limits: the least used legs go, down to 80 %. Under [lock]. */
    private fun prune() {
        if (files <= maxFiles && bytes <= maxBytes) return
        val all = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty().sortedBy { it.lastModified() }
        var n = all.size; var b = all.sumOf { it.length() }
        var gone = 0
        for (f in all) {
            if (n <= maxFiles * 8 / 10 && b <= maxBytes * 8 / 10) break
            val len = f.length()
            if (f.delete()) { n--; b -= len; gone++ }
        }
        files = n; bytes = b
        Problems.trace("routing", "Leg store full: $gone least used legs removed ($n legs, ${b / 1024} KB left)")
    }

    /** One stored leg. [data] = "part=version;…" ([partVersion]), [timeMs] = when it was routed. */
    internal data class Entry(val key: LegCache.Key, val fingerprint: String, val data: String, val timeMs: Long, val leg: RouteResult)

    companion object {
        /** Legs kept: a 35 km bike leg is ~50–100 KB – 40 MB hold dozens of long plans. */
        const val MAX_FILES = 3_000
        const val MAX_BYTES = 40L * 1024 * 1024
        /** Routing parts within this of a leg's line or straight line are its routing data (BRouter's search stays near). */
        const val MARGIN_M = 2_000.0
        const val SUFFIX = ".leg"
        private const val MAGIC = "onpath-leg 1"

        fun name(k: LegCache.Key) = "${k.fromLat}_${k.fromLon}_${k.toLat}_${k.toLon}_${k.activity.name}_${k.sac}$SUFFIX"

        /**
         * Text form (one file): header lines, then one line per point "lat⇥lon⇥ele⇥way⇥turn⇥shape" – way = the
         * [WayAttrs.code] ("=" the previous point's, empty none), turn = "code:exit:angle", shape exact. Doubles and
         * floats as Kotlin prints them (read back bit for bit: the same line).
         */
        internal fun encode(e: Entry): String = buildString {
            val r = e.leg
            append(MAGIC).append('\n')
            append("key\t").append(e.key.fromLat).append('\t').append(e.key.fromLon).append('\t').append(e.key.toLat).append('\t')
                .append(e.key.toLon).append('\t').append(e.key.activity.name).append('\t').append(e.key.sac).append('\n')
            append("fp\t").append(e.fingerprint).append('\n')
            append("data\t").append(e.data).append('\n')
            append("time\t").append(e.timeMs).append('\n')
            append("stats\t").append(r.distanceM).append('\t').append(r.ascentM).append('\t').append(r.descentM).append('\t')
                .append(r.durationS).append('\n')
            append("ways\t").append(shares(r.wayTypes)).append('\n')
            append("surf\t").append(shares(r.surfaces)).append('\n')
            append("matched\t").append(r.matched.joinToString("|") { p -> p?.let { "${it.lat},${it.lon},${it.ele ?: ""}" } ?: "-" }).append('\n')
            append("points\t").append(r.points.size).append('\n')
            var last: String? = null
            for (p in r.points) {
                append(p.lat).append('\t').append(p.lon).append('\t').append(p.ele ?: "").append('\t')
                val w = p.way?.code()
                append(if (w != null && w == last) "=" else w.orEmpty()).append('\t')
                last = w
                p.turn?.let { append(it.code).append(':').append(p.exit).append(':').append(p.turnAngle) }
                append('\t')
                p.shape?.let { s ->
                    append(s.angle).append(',').append(s.inPrio).append(',').append(s.outPrio)
                    s.others.forEach { b -> append(';').append(b.angle).append(',').append(b.prio) }
                }
                append('\n')
            }
        }

        private fun shares(l: List<Share>) = l.joinToString(",") { "${it.key}=${it.meters}" }

        /** Reads [encode]; throws on anything odd (the caller deletes the file). */
        internal fun decode(text: String): Entry {
            val lines = text.split('\n')
            require(lines.firstOrNull() == MAGIC) { "not a leg file" }
            val head = HashMap<String, List<String>>()
            var i = 1
            while (i < lines.size) {
                val f = lines[i].split('\t')
                head[f[0]] = f.drop(1)
                i++
                if (f[0] == "points") break
            }
            val k = head.getValue("key")
            val key = LegCache.Key(k[0].toLong(), k[1].toLong(), k[2].toLong(), k[3].toLong(), Activity.valueOf(k[4]), k[5].toInt())
            val st = head.getValue("stats").map { it.toDouble() }
            val n = head.getValue("points")[0].toInt()
            require(lines.size >= i + n) { "leg file cut off" }
            val pts = ArrayList<GeoPoint>(n)
            var last: WayAttrs? = null
            for (j in 0 until n) {
                val f = lines[i + j].split('\t')
                require(f.size >= 6) { "leg point unreadable" }
                val way = when (f[3]) { "" -> null; "=" -> last; else -> requireNotNull(WayAttrs.parse(f[3])) { "way unreadable" } }
                last = way
                var turn: JunctionCue? = null; var exit = 0; var angle = 0f
                if (f[4].isNotEmpty()) {
                    val t = f[4].split(':')
                    turn = requireNotNull(JunctionCue.ofCode(t[0])) { "turn unreadable" }
                    exit = t[1].toInt(); angle = t[2].toFloat()
                }
                val shape = if (f[5].isEmpty()) null else f[5].split(';').let { s ->
                    val h = s[0].split(',')
                    JunctionShape(h[0].toFloat(), h[1].toInt(), h[2].toInt(), s.drop(1).map { b ->
                        val v = b.split(','); JunctionShape.Branch(v[0].toFloat(), v[1].toInt())
                    })
                }
                pts += GeoPoint(f[0].toDouble(), f[1].toDouble(), f[2].ifEmpty { null }?.toDouble(), way, turn, exit, angle, shape)
            }
            fun readShares(name: String) = head[name]?.firstOrNull().orEmpty().split(',').filter { it.isNotEmpty() }
                .map { Share(it.substringBefore('='), it.substringAfter('=').toDouble()) }
            val matched = head[("matched")]?.firstOrNull().orEmpty().split('|').filter { it.isNotEmpty() }.map { m ->
                if (m == "-") null else m.split(',').let { GeoPoint(it[0].toDouble(), it[1].toDouble(), it.getOrNull(2)?.ifEmpty { null }?.toDouble()) }
            }
            val leg = RouteResult(pts, st[0], st[1], st[2], st[3], readShares("ways"), readShares("surf"), matched)
            return Entry(key, head.getValue("fp")[0], head["data"]?.firstOrNull().orEmpty(), head["time"]?.firstOrNull()?.toLong() ?: 0L, leg)
        }
    }
}

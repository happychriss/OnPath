package com.onpath.web

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

/**
 * The planner's leg store (owner 2026-11-18, „a long saved route opens again without routing every leg“): the web's
 * [LegStore] behind the engine's [LegCache] – the phone's data/LegFiles, with the browser's IndexedDB in place of
 * `files/legs/`. The engine cannot wait for IndexedDB (the worker's calls into it are synchronous), so the worker carries
 * the legs in and out around a plan (worker.ts `legs*`):
 *  - before a plan it asks for the [wanted] legs (the plan's legs not in memory), reads them from IndexedDB and hands
 *    each over ([give]);
 *  - the Planner reads them through [get] – used only while fresh, the phone's rule exactly (below);
 *  - every leg the Planner routes (and every leg kept under its snapped points, [LegCache.alias]) is [put] here, and the
 *    worker takes the new ones ([taken] / [text]) into IndexedDB after the answer.
 *
 * **When a stored leg is used** (the phone's LegFiles rule, the app leads): only for exactly its two points (to 1e-6°),
 * its activity and – for Hike – its T level (the name is the [LegCache.Key]); only with this engine's routing fingerprint
 * ([fingerprint]: BRouter, profiles, PLANNER_VERSION) and only while the routing data under it is the same – each 1° part
 * within [MARGIN_M] of its line and of its straight line has the version it had ([partVersion]: the rd5 tile's creation
 * time, "-" = no such tile / not known). New map data never reuses a stale leg. A leg routed while a part under it was
 * still missing (a download failed: the router went round the hole) is not stored ([missing]).
 *
 * Text form: the phone's leg file, with every number as its exact bits (base 36) – the same leg bit for bit, also where
 * the browser's number printing could differ. Never throws: an unreadable leg is no leg (reported).
 */
class WebLegStore(
    private val fingerprint: () -> String,
    private val partVersion: (Rd5Parts.Part) -> String,
    private val missing: (Rd5Parts.Part) -> Boolean = { false },
) : LegStore {
    /** Legs handed over for the coming plan (name → text); replaced at the next [wanted]. */
    private val given = HashMap<String, String>()
    /** Legs stored since the worker last took them (name → text), oldest first. */
    private val fresh = LinkedHashMap<String, String>()

    override fun get(key: LegCache.Key): LegStore.Stored? {
        val n = name(key)
        val text = fresh[n] ?: given[n] ?: return null
        val e = try { decode(text) } catch (e: Exception) {
            Problems.warn("routing", "A stored leg is unreadable – routed again", e)
            given.remove(n); return null
        }
        if (e.key != key) {
            Problems.warn("routing", "A stored leg belongs to other points – routed again")
            given.remove(n); return null
        }
        val ok = isFresh(e)
        if (ok && n in given) hits++
        return LegStore.Stored(e.leg, ok)
    }

    /** Fresh legs read from what the worker handed over since the last [wanted] (the smokes' count). */
    var hits = 0
        private set

    override fun put(key: LegCache.Key, from: GeoPoint, to: GeoPoint, leg: RouteResult) {
        try {
            val fp = fingerprint().ifEmpty { return }
            val parts = (Rd5Parts.forCorridor(listOf(from, to), MARGIN_M) + Rd5Parts.forCorridor(leg.points, MARGIN_M)).sorted()
            // routed round a hole in the data: the next plan with the part loaded routes it again
            if (parts.any(missing)) return
            val data = parts.joinToString(";") { "${it.key}=${partVersion(it)}" }
            val n = name(key)
            fresh.remove(n)
            fresh[n] = encode(Entry(key, fp, data, leg))
            // the worker takes them after each answer; a page that never does (JVM run) keeps only the newest
            while (fresh.size > MAX_PENDING) fresh.remove(fresh.keys.first())
        } catch (e: Exception) {
            Problems.warn("routing", "A routed leg could not be stored – it is routed again next time", e)
        }
    }

    private fun isFresh(e: Entry): Boolean {
        val fp = fingerprint()
        if (fp.isBlank() || e.fingerprint != fp || e.data.isEmpty()) return false
        for (item in e.data.split(';')) {
            val part = Rd5Parts.Part.parse(item.substringBefore('=')) ?: return false
            val v = item.substringAfter('=', "?")
            if (v == "?" || partVersion(part) != v) return false
        }
        return true
    }

    /** The names of [keys] (a plan's legs) not handed over yet; what was handed over before is dropped. */
    fun wanted(keys: List<LegCache.Key>): List<String> {
        given.clear(); hits = 0
        return keys.map(::name).distinct().filter { it !in fresh }
    }

    /** A stored leg for the coming plan (its text as [text] gave it). */
    fun give(name: String, text: String) { given[name] = text }

    /** The legs stored since the last call (names; their texts by [text] until the next [taken]). */
    fun taken(): List<String> {
        val out = fresh.keys.toList()
        taking.clear(); taking.putAll(fresh); fresh.clear()
        return out
    }
    private val taking = HashMap<String, String>()

    fun text(name: String): String = taking[name] ?: ""

    /** One stored leg. [data] = "part=version;…" ([partVersion]). */
    internal data class Entry(val key: LegCache.Key, val fingerprint: String, val data: String, val leg: RouteResult)

    companion object {
        /** Routing parts within this of a leg's line or straight line are its routing data (LegFiles.MARGIN_M). */
        const val MARGIN_M = 2_000.0
        /** Legs kept for the worker at most (a plan has at most PlanCode.MAX_POINTS legs, each also under snapped points). */
        const val MAX_PENDING = 2 * com.onpath.app.routing.PlanCode.MAX_POINTS
        private const val MAGIC = "onpath-web-leg 1"

        /** The phone's file name without ".leg" (LegFiles.name): both points, the activity, the T level. */
        fun name(k: LegCache.Key) = "${k.fromLat}_${k.fromLon}_${k.toLat}_${k.toLon}_${k.activity.name}_${k.sac}"

        private fun d(v: Double) = v.toRawBits().toString(36)
        private fun f(v: Float) = v.toRawBits().toString(36)
        private fun d(s: String) = Double.fromBits(s.toLong(36))
        private fun f(s: String) = Float.fromBits(s.toInt(36))

        /** The phone's leg file (LegFiles.encode), numbers as bits: "lat⇥lon⇥ele⇥way⇥turn⇥shape" per point. */
        internal fun encode(e: Entry): String = buildString {
            val r = e.leg
            append(MAGIC).append('\n')
            append("key\t").append(e.key.fromLat).append('\t').append(e.key.fromLon).append('\t').append(e.key.toLat).append('\t')
                .append(e.key.toLon).append('\t').append(e.key.activity.name).append('\t').append(e.key.sac).append('\n')
            append("fp\t").append(e.fingerprint).append('\n')
            append("data\t").append(e.data).append('\n')
            append("stats\t").append(d(r.distanceM)).append('\t').append(d(r.ascentM)).append('\t').append(d(r.descentM)).append('\t')
                .append(d(r.durationS)).append('\n')
            append("ways\t").append(shares(r.wayTypes)).append('\n')
            append("surf\t").append(shares(r.surfaces)).append('\n')
            append("matched\t").append(r.matched.joinToString("|") { p -> p?.let { "${d(it.lat)},${d(it.lon)},${it.ele?.let(::d) ?: ""}" } ?: "-" }).append('\n')
            append("points\t").append(r.points.size).append('\n')
            var last: String? = null
            for (p in r.points) {
                append(d(p.lat)).append('\t').append(d(p.lon)).append('\t').append(p.ele?.let(::d) ?: "").append('\t')
                val w = p.way?.code()
                append(if (w != null && w == last) "=" else w.orEmpty()).append('\t')
                last = w
                p.turn?.let { append(it.code).append(':').append(p.exit).append(':').append(f(p.turnAngle)) }
                append('\t')
                p.shape?.let { s ->
                    append(f(s.angle)).append(',').append(s.inPrio).append(',').append(s.outPrio)
                    s.others.forEach { b -> append(';').append(f(b.angle)).append(',').append(b.prio) }
                }
                append('\n')
            }
        }

        private fun shares(l: List<Share>) = l.joinToString(",") { "${it.key}=${d(it.meters)}" }

        /** Reads [encode]; throws on anything odd. */
        internal fun decode(text: String): Entry {
            val lines = text.split('\n')
            require(lines.firstOrNull() == MAGIC) { "not a leg" }
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
            val st = head.getValue("stats").map(::d)
            val n = head.getValue("points")[0].toInt()
            require(lines.size >= i + n) { "leg cut off" }
            val pts = ArrayList<GeoPoint>(n)
            var last: WayAttrs? = null
            for (j in 0 until n) {
                val c = lines[i + j].split('\t')
                require(c.size >= 6) { "leg point unreadable" }
                val way = when (c[3]) { "" -> null; "=" -> last; else -> requireNotNull(WayAttrs.parse(c[3])) { "way unreadable" } }
                last = way
                var turn: JunctionCue? = null; var exit = 0; var angle = 0f
                if (c[4].isNotEmpty()) {
                    val t = c[4].split(':')
                    turn = requireNotNull(JunctionCue.ofCode(t[0])) { "turn unreadable" }
                    exit = t[1].toInt(); angle = f(t[2])
                }
                val shape = if (c[5].isEmpty()) null else c[5].split(';').let { s ->
                    val h = s[0].split(',')
                    JunctionShape(f(h[0]), h[1].toInt(), h[2].toInt(), s.drop(1).map { b ->
                        val v = b.split(','); JunctionShape.Branch(f(v[0]), v[1].toInt())
                    })
                }
                pts += GeoPoint(d(c[0]), d(c[1]), c[2].ifEmpty { null }?.let(::d), way, turn, exit, angle, shape)
            }
            fun readShares(name: String) = head[name]?.firstOrNull().orEmpty().split(',').filter { it.isNotEmpty() }
                .map { Share(it.substringBefore('='), d(it.substringAfter('='))) }
            val matched = head["matched"]?.firstOrNull().orEmpty().split('|').filter { it.isNotEmpty() }.map { m ->
                if (m == "-") null else m.split(',').let { GeoPoint(d(it[0]), d(it[1]), it.getOrNull(2)?.ifEmpty { null }?.let(::d)) }
            }
            val leg = RouteResult(pts, st[0], st[1], st[2], st[3], readShares("ways"), readShares("surf"), matched)
            return Entry(key, head.getValue("fp")[0], head["data"]?.firstOrNull().orEmpty(), leg)
        }
    }
}

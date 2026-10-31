package com.onpath.app.navigation

import com.onpath.app.data.AtomicFiles
import com.onpath.app.routing.Activity
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WayAttrs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What is navigated: a plan (stops known) or a followed track (no stops). */
enum class NavKind { PLAN, TRACK }

/**
 * One navigation session (docs/plan-auto-reroute.md §7): the **original** line as planned (never changed by re-routing),
 * its stops, and – after the user took a new route – the **current** line (way back + rest of the original).
 * Persisted as `nav-session.json` (atomic tmp + rename) so a restart of the service keeps both (R-07).
 */
data class NavSession(
    val kind: NavKind,
    val name: String,
    val activity: Activity,
    val sac: Int,
    val original: List<GeoPoint>,
    val stops: List<NavStop> = emptyList(),
    /** Line navigated now; null = the original. */
    val current: List<GeoPoint>? = null,
    /** On [current]: where the untouched rest of the original starts. */
    val spliceAtM: Double = 0.0,
    /** On [original]: where that rest starts. */
    val resumeAlongM: Double = 0.0,
    /** On [original]: progress when the user left it (re-routing targets lie ahead of it). */
    val leftAtM: Double = 0.0,
    /** Stops the user came within [VISITED_M] of during the tour. */
    val visited: Set<Int> = emptySet(),
    /** Bumped whenever the navigated line changes (UI re-reads it). */
    val version: Int = 0,
    /**
     * The user has been on the navigated line during this tour (owner 2026-10-31). Until then a position away from it
     * is "the route starts 31 km away", never "off route"; saved so a restart mid-tour does not forget it.
     */
    val reached: Boolean = false,
    /**
     * "Guide me" was chosen for this route while it was never reached (Bug 02, owner 2026-10-31): a plan edited before
     * the route is reached is led to again at once (here → the start → all points), never dropped for the bare plan.
     */
    val lead: Boolean = false,
) {
    val active: List<GeoPoint> get() = current ?: original
    val rerouted: Boolean get() = current != null

    companion object {
        const val VISITED_M = 40.0
        const val FILE = "nav-session.json"

        /** A new session for [line]; plan stops are placed along it in order. */
        fun start(kind: NavKind, name: String, activity: Activity, sac: Int, line: List<GeoPoint>,
                  stops: List<GeoPoint> = emptyList(), names: List<String?> = emptyList(), pinned: Set<Int> = emptySet()): NavSession {
            val nl = NavLine(line)
            val alongs = if (stops.isEmpty() || !nl.isUsable) emptyList() else nl.alongsInOrder(stops)
            val ns = alongs.mapIndexed { i, a -> NavStop(stops[i], a, names.getOrNull(i), i in pinned) }
            return NavSession(kind, name, activity, sac, line, ns)
        }

        /**
         * A fresh session for a plan edited mid-tour ([fresh]), after [old]: once on the route, off the edited line is off
         * route as before ([reached] carries over); never reached but "Guide me" chosen → [lead] carries over and the
         * service leads to the edited plan again ([needsLead]). A followed track starts afresh.
         */
        fun afterPlanEdit(old: NavSession?, fresh: NavSession): NavSession {
            if (old == null || old.kind != NavKind.PLAN || fresh.kind != NavKind.PLAN || fresh.rerouted) return fresh
            return when {
                old.reached -> if (fresh.reached) fresh else fresh.copy(reached = true)
                old.lead && !fresh.reached && !fresh.lead -> fresh.copy(lead = true)
                else -> fresh
            }
        }
    }

    /** "Guide me" holds for this plan but no way there is followed yet (plan edited before the route was reached). */
    val needsLead: Boolean get() = lead && !reached && !rerouted && kind == NavKind.PLAN
}

/** JSON file for [NavSession]; never throws (a broken file = no session). */
class NavSessionStore(private val file: File) {

    fun save(s: NavSession?) {
        try {
            if (s == null) { file.delete(); return }
            AtomicFiles.writeText(file, toJson(s).toString(), File(file.path + ".tmp"))
        } catch (e: Exception) {
            Problems.error("navigation", "Navigation session file could not be saved; a restart may lose the route", e)
        }
    }

    fun load(): NavSession? = try {
        if (!file.isFile) null else fromJson(JSONObject(file.readText()))
    } catch (e: Exception) {
        Problems.warn("navigation", "Navigation session file unreadable; no session restored", e)
        null
    }

    companion object {
        fun toJson(s: NavSession): JSONObject = JSONObject()
            .put("v", 1)
            .put("kind", s.kind.name).put("name", s.name).put("activity", s.activity.name).put("sac", s.sac)
            .put("original", pts(s.original))
            .put("originalWays", ways(s.original) ?: JSONObject.NULL)
            .put("originalTurns", turns(s.original) ?: JSONObject.NULL)
            .put("originalShapes", shapes(s.original) ?: JSONObject.NULL)
            .put("stops", JSONArray().apply {
                s.stops.forEach { st ->
                    put(JSONObject().put("lat", st.point.lat).put("lon", st.point.lon).put("along", st.alongM)
                        .put("name", st.name ?: JSONObject.NULL).put("pinned", st.pinned))
                }
            })
            .put("current", s.current?.let { pts(it) } ?: JSONObject.NULL)
            .put("currentWays", s.current?.let { ways(it) } ?: JSONObject.NULL)
            .put("currentTurns", s.current?.let { turns(it) } ?: JSONObject.NULL)
            .put("currentShapes", s.current?.let { shapes(it) } ?: JSONObject.NULL)
            .put("spliceAt", s.spliceAtM).put("resumeAlong", s.resumeAlongM).put("leftAt", s.leftAtM)
            .put("visited", JSONArray().apply { s.visited.sorted().forEach { put(it) } })
            .put("version", s.version)
            .put("reached", s.reached)
            .put("lead", s.lead)

        fun fromJson(o: JSONObject): NavSession? {
            val original = withShapes(withTurns(withWays(readPts(o.getJSONArray("original")), o.optJSONArray("originalWays")),
                o.optJSONArray("originalTurns")), o.optJSONArray("originalShapes"))
            if (original.size < 2) return null
            val stops = o.optJSONArray("stops")?.let { a ->
                (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    NavStop(GeoPoint(s.getDouble("lat"), s.getDouble("lon")), s.getDouble("along"),
                        if (s.isNull("name")) null else s.optString("name"), s.optBoolean("pinned"))
                }
            }.orEmpty()
            val current = if (o.isNull("current")) null else o.optJSONArray("current")?.let {
                withShapes(withTurns(withWays(readPts(it), o.optJSONArray("currentWays")), o.optJSONArray("currentTurns")),
                    o.optJSONArray("currentShapes"))
            }?.takeIf { it.size >= 2 }
            val visited = o.optJSONArray("visited")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty()
            return NavSession(
                // expected: missing / unknown enum names (older files) fall back to defaults
                kind = runCatching { NavKind.valueOf(o.getString("kind")) }.getOrDefault(NavKind.TRACK),
                name = o.optString("name", "Route"),
                activity = runCatching { Activity.valueOf(o.getString("activity")) }.getOrDefault(Activity.HIKE),
                sac = o.optInt("sac", 3),
                original = original, stops = stops, current = current,
                spliceAtM = o.optDouble("spliceAt", 0.0), resumeAlongM = o.optDouble("resumeAlong", 0.0),
                leftAtM = o.optDouble("leftAt", 0.0), visited = visited, version = o.optInt("version", 0),
                // a file without the key was written by an older app during a running tour: assume it was reached
                reached = o.optBoolean("reached", true),
                lead = o.optBoolean("lead", false),
            )
        }

        private fun pts(l: List<GeoPoint>) = JSONArray().apply {
            l.forEach { put(it.lat); put(it.lon); put(it.ele ?: JSONObject.NULL) }
        }

        /** Way attributes of a line (time model, [GeoPoint.way]) as runs [startIndex, code, ...]; null = none known. */
        private fun ways(l: List<GeoPoint>): JSONArray? {
            if (l.none { it.way != null }) return null
            val a = JSONArray()
            var last: String? = "\u0000"
            l.forEachIndexed { i, p ->
                val c = p.way?.code()
                if (c != last) { a.put(i); a.put(c ?: JSONObject.NULL); last = c }
            }
            return a
        }

        /** Puts the runs written by [ways] back on the points; anything odd = no attributes (times as without). */
        private fun withWays(l: List<GeoPoint>, a: JSONArray?): List<GeoPoint> = try {
            if (a == null || a.length() < 2) l else {
                val out = l.toMutableList()
                var r = 0
                while (r + 1 < a.length()) {
                    val from = a.getInt(r)
                    val to = if (r + 2 < a.length()) a.getInt(r + 2) else l.size
                    val w = if (a.isNull(r + 1)) null else WayAttrs.parse(a.getString(r + 1))
                    for (i in from.coerceAtLeast(0) until to.coerceAtMost(l.size)) out[i] = out[i].copy(way = w)
                    r += 2
                }
                out
            }
        } catch (e: Exception) {
            Problems.warn("navigation", "Way attributes in navigation session unreadable; times computed without them", e)
            l
        }

        /** Junction instructions of a line ([GeoPoint.turn]) as pairs [index, code, ...]; null = none. */
        private fun turns(l: List<GeoPoint>): JSONArray? {
            if (l.none { it.turn != null }) return null
            val a = JSONArray()
            // "RNDB:2:-85" = roundabout, 2nd exit, angle (older sessions: "RNDB:2" or the code alone)
            l.forEachIndexed { i, p -> p.turn?.let { a.put(i); a.put(if (p.exit > 0) "${it.code}:${p.exit}:${p.turnAngle.toInt()}" else it.code) } }
            return a
        }

        /** Puts the pairs written by [turns] back; anything odd = no junction cues (navigation works without). */
        private fun withTurns(l: List<GeoPoint>, a: JSONArray?): List<GeoPoint> = try {
            if (a == null || a.length() < 2) l else {
                val out = l.toMutableList()
                var r = 0
                while (r + 1 < a.length()) {
                    val i = a.getInt(r)
                    val parts = a.optString(r + 1).split(':')
                    val cue = com.onpath.app.routing.JunctionCue.ofCode(parts[0])
                    val exit = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val angle = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                    if (cue != null && i in out.indices) out[i] = out[i].copy(turn = cue, exit = exit, turnAngle = angle)
                    r += 2
                }
                out
            }
        } catch (e: Exception) {
            Problems.warn("navigation", "Junction instructions in navigation session unreadable; no junction vibration", e)
            l
        }

        /**
         * The ways at the junctions ([GeoPoint.shape], the bike's "could one go wrong" check) as pairs [index, text];
         * null = none. Own key, so an older app reading the file keeps its junctions.
         */
        private fun shapes(l: List<GeoPoint>): JSONArray? {
            if (l.none { it.shape != null }) return null
            val a = JSONArray()
            l.forEachIndexed { i, p -> p.shape?.let { a.put(i); a.put(it.encode()) } }
            return a
        }

        /** Puts the pairs written by [shapes] back on junction points; anything odd = no shape (the cue falls back). */
        private fun withShapes(l: List<GeoPoint>, a: JSONArray?): List<GeoPoint> = try {
            if (a == null || a.length() < 2) l else {
                val out = l.toMutableList()
                var r = 0
                while (r + 1 < a.length()) {
                    val i = a.getInt(r)
                    val shape = com.onpath.app.routing.JunctionShape.decode(a.optString(r + 1))
                    if (shape != null && i in out.indices && out[i].turn != null) out[i] = out[i].copy(shape = shape)
                    r += 2
                }
                out
            }
        } catch (e: Exception) {
            Problems.warn("navigation", "Junction shapes in navigation session unreadable; bike cues at every junction but slight bends", e)
            l
        }

        private fun readPts(a: JSONArray): List<GeoPoint> = (0 until a.length() / 3).map {
            val e = if (a.isNull(it * 3 + 2)) null else a.optDouble(it * 3 + 2).takeIf { v -> !v.isNaN() }
            GeoPoint(a.getDouble(it * 3), a.getDouble(it * 3 + 1), e)
        }
    }
}

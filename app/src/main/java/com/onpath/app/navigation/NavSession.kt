package com.onpath.app.navigation

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
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
    }
}

/** JSON file for [NavSession]; never throws (a broken file = no session). */
class NavSessionStore(private val file: File) {

    fun save(s: NavSession?) {
        try {
            if (s == null) { file.delete(); return }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(toJson(s).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) { }
    }

    fun load(): NavSession? = try {
        if (!file.isFile) null else fromJson(JSONObject(file.readText()))
    } catch (_: Exception) { null }

    companion object {
        fun toJson(s: NavSession): JSONObject = JSONObject()
            .put("v", 1)
            .put("kind", s.kind.name).put("name", s.name).put("activity", s.activity.name).put("sac", s.sac)
            .put("original", pts(s.original))
            .put("stops", JSONArray().apply {
                s.stops.forEach { st ->
                    put(JSONObject().put("lat", st.point.lat).put("lon", st.point.lon).put("along", st.alongM)
                        .put("name", st.name ?: JSONObject.NULL).put("pinned", st.pinned))
                }
            })
            .put("current", s.current?.let { pts(it) } ?: JSONObject.NULL)
            .put("spliceAt", s.spliceAtM).put("resumeAlong", s.resumeAlongM).put("leftAt", s.leftAtM)
            .put("visited", JSONArray().apply { s.visited.sorted().forEach { put(it) } })
            .put("version", s.version)

        fun fromJson(o: JSONObject): NavSession? {
            val original = readPts(o.getJSONArray("original"))
            if (original.size < 2) return null
            val stops = o.optJSONArray("stops")?.let { a ->
                (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    NavStop(GeoPoint(s.getDouble("lat"), s.getDouble("lon")), s.getDouble("along"),
                        if (s.isNull("name")) null else s.optString("name"), s.optBoolean("pinned"))
                }
            }.orEmpty()
            val current = if (o.isNull("current")) null else o.optJSONArray("current")?.let { readPts(it) }?.takeIf { it.size >= 2 }
            val visited = o.optJSONArray("visited")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty()
            return NavSession(
                kind = runCatching { NavKind.valueOf(o.getString("kind")) }.getOrDefault(NavKind.TRACK),
                name = o.optString("name", "Route"),
                activity = runCatching { Activity.valueOf(o.getString("activity")) }.getOrDefault(Activity.HIKE),
                sac = o.optInt("sac", 3),
                original = original, stops = stops, current = current,
                spliceAtM = o.optDouble("spliceAt", 0.0), resumeAlongM = o.optDouble("resumeAlong", 0.0),
                leftAtM = o.optDouble("leftAt", 0.0), visited = visited, version = o.optInt("version", 0),
            )
        }

        private fun pts(l: List<GeoPoint>) = JSONArray().apply {
            l.forEach { put(it.lat); put(it.lon); put(it.ele ?: JSONObject.NULL) }
        }

        private fun readPts(a: JSONArray): List<GeoPoint> = (0 until a.length() / 3).map {
            val e = if (a.isNull(it * 3 + 2)) null else a.optDouble(it * 3 + 2).takeIf { v -> !v.isNaN() }
            GeoPoint(a.getDouble(it * 3), a.getDouble(it * 3 + 1), e)
        }
    }
}

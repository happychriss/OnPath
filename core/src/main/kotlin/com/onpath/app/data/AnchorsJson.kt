package com.onpath.app.data

import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.AnchorProfile
import com.onpath.app.routing.AnchorSet
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * A plan's hidden via points as JSON (docs/design/detour-stitch.md §3): the saved route's column, the plan's copy in a
 * recorded tour, the backup record, the plan kept between two runs of the app. Each entry names the point it belongs to:
 * `[{"i":3,"p":[lat,lon],"b":[[lat,lon],…],"a":[[lat,lon],…]}]`, so anchors written for other points (a kill between two
 * writes) are dropped instead of pulling the route through a wrong place. The limits are the plan code's. Losing anchors
 * is safe (the leg is routed in full), so nothing here throws.
 */
object AnchorsJson {
    /** The JSON of [anchors] on [points]; null when there is nothing to keep. */
    fun write(points: List<GeoPoint>, anchors: Map<Int, Anchors>): String? {
        if (anchors.isEmpty()) return null
        var total = 0
        val entries = ArrayList<String>()
        for ((i, a) in anchors.toSortedMap()) {
            val p = points.getOrNull(i) ?: continue
            if (!fits(p, a)) continue
            if (total + a.size > PlanCode.MAX_ANCHORS) break
            total += a.size
            entries += "{\"i\":$i,\"p\":${pair(p)},\"b\":${list(a.before)},\"a\":${list(a.after)}}"
        }
        return if (entries.isEmpty()) null else entries.joinToString(",", "[", "]")
    }

    /** The anchors in [json] that belong to [points]; none when it is missing, unreadable or over the plan's limit (reported). */
    fun read(json: String?, points: List<GeoPoint>): Map<Int, Anchors> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val entries = JSONArray(json)
            val out = HashMap<Int, Anchors>()
            var total = 0
            for (n in 0 until entries.length()) {
                val o = entries.getJSONObject(n)
                val i = o.getInt("i")
                val p = points.getOrNull(i) ?: continue
                val at = point(o.getJSONArray("p"))
                if (micro(at.lat) != micro(p.lat) || micro(at.lon) != micro(p.lon)) continue
                val a = Anchors(points(o.optJSONArray("b")), points(o.optJSONArray("a")))
                if (!fits(p, a)) continue
                total += a.size
                if (total > PlanCode.MAX_ANCHORS) {
                    Problems.warn("db", "Stored detour points over the limit – the route is calculated without them")
                    return emptyMap()
                }
                out[i] = a
            }
            if (out.size < entries.length()) Problems.warn("db", "${entries.length() - out.size} stored detour entries did not fit their points – dropped")
            out
        } catch (e: Exception) {
            Problems.warn("db", "Stored detour points unreadable – the route is calculated without them", e); emptyMap()
        }
    }

    /**
     * The JSON of a DB row's anchors (docs/design/detour-stitch.md §3): [write]'s entries with the profile they lie on and a
     * hash of ALL the row's [points]. An older build (no anchors) can change the row's activity or T level, or delete / move
     * a NEIGHBOUR of an anchored point, and leave this column as it is; the entry's own point would still match. The profile
     * and the hash tell [readRow] that the row was edited since. Null when there is nothing to keep (or no activity to tie to).
     */
    fun writeRow(points: List<GeoPoint>, anchors: Map<Int, Anchors>, activity: String?, sac: Int): String? {
        if (activity == null) return null
        val entries = write(points, anchors) ?: return null
        val known = activityOf(activity)
        val name = known?.name ?: activity
        val s = if (known != null) AnchorProfile.of(known, sac).sac else sac
        return "{\"activity\":${JSONObject.quote(name)},\"sac\":$s,\"h\":\"${hash(points)}\",\"entries\":$entries}"
    }

    /**
     * The anchors of a row written by [writeRow]: none (reported) when its profile or its points are not the row's now.
     * The older array format (the reader stage, no profile) is read as [read] does. Unreadable = none, never throws.
     */
    fun readRow(json: String?, points: List<GeoPoint>, activity: String?, sac: Int): Map<Int, Anchors> {
        if (json.isNullOrBlank()) return emptyMap()
        if (!json.trimStart().startsWith("{")) return read(json, points)
        return try {
            val o = JSONObject(json)
            val name = o.getString("activity")
            if (activity == null || !sameProfile(name, o.getInt("sac"), activity, sac)) {
                Problems.warn("db", "Stored detour points belong to another activity than the route's – the route is calculated without them")
                return emptyMap()
            }
            if (o.getString("h") != hash(points)) {
                Problems.warn("db", "The route's points changed since its detour points were stored – the route is calculated without them")
                return emptyMap()
            }
            read(o.getJSONArray("entries").toString(), points)
        } catch (e: Exception) {
            Problems.warn("db", "Stored detour points of the route unreadable – the route is calculated without them", e); emptyMap()
        }
    }

    private fun activityOf(name: String): Activity? = Activity.entries.firstOrNull { it.name == name }

    /** Known activities by [AnchorProfile] (a hike's T level counts, else not); an unknown name by its raw name and level. */
    private fun sameProfile(storedName: String, storedSac: Int, name: String, sac: Int): Boolean {
        val a = activityOf(storedName); val b = activityOf(name)
        return if (a != null && b != null) AnchorProfile.of(a, storedSac) == AnchorProfile.of(b, sac)
        else a == null && b == null && storedName == name && storedSac == sac
    }

    /**
     * FNV-1a 64 over every point's micro-degree lat and lon, in order, as 16 hex digits. Written by hand: no platform hash
     * (the code also runs in Wasm), and the same text in the planner and the app.
     */
    internal fun hash(points: List<GeoPoint>): String {
        var h = -3750763034362895579L // 0xcbf29ce484222325
        fun mix(v: Long) { for (k in 0 until 8) { h = (h xor ((v shr (8 * k)) and 0xff)) * 1099511628211L } }
        for (p in points) { mix(micro(p.lat)); mix(micro(p.lon)) }
        val digits = "0123456789abcdef"
        return buildString { for (k in 15 downTo 0) append(digits[((h shr (4 * k)) and 0xf).toInt()]) }
    }

    /** [write] with the profile the anchors lie on: the plan kept between two runs (the activity can change while the app is gone). */
    fun writeSet(points: List<GeoPoint>, set: AnchorSet): String? {
        val profile = set.profile ?: return null
        val entries = write(points, set.anchors) ?: return null
        return "{\"activity\":\"${profile.activity.name}\",\"sac\":${profile.sac},\"entries\":$entries}"
    }

    /** [writeSet]'s text for [points]; [AnchorSet.NONE] when missing or unreadable (reported). */
    fun readSet(json: String?, points: List<GeoPoint>): AnchorSet {
        if (json.isNullOrBlank()) return AnchorSet.NONE
        return try {
            val o = JSONObject(json)
            val name = o.getString("activity")
            AnchorSet.of(read(o.getJSONArray("entries").toString(), points), Activity.entries.firstOrNull { it.name == name }, o.getInt("sac"))
        } catch (e: Exception) {
            Problems.warn("db", "Stored detour points of the plan unreadable – the route is calculated without them", e); AnchorSet.NONE
        }
    }

    /** The plan code's rules for one entry ([PlanCode]): not empty, not too many per side, near its point, real numbers. */
    private fun fits(p: GeoPoint, a: Anchors): Boolean {
        if (a.isEmpty || a.before.size > PlanCode.MAX_ANCHORS_SIDE || a.after.size > PlanCode.MAX_ANCHORS_SIDE) return false
        if (!valid(p)) return false
        val la = micro(p.lat); val lo = micro(p.lon)
        return (a.before + a.after).all { valid(it) && abs(micro(it.lat) - la) <= PlanCode.MAX_ANCHOR_DELTA && abs(micro(it.lon) - lo) <= PlanCode.MAX_ANCHOR_DELTA }
    }

    private fun valid(p: GeoPoint) = p.lat.isFinite() && p.lon.isFinite() && abs(p.lat) <= 90.0 && abs(p.lon) <= 180.0
    private fun micro(v: Double): Long = Math.round(v * 1e6)
    private fun r6(v: Double): Double = micro(v) / 1e6
    private fun pair(p: GeoPoint) = "[${r6(p.lat)},${r6(p.lon)}]"
    private fun list(l: List<GeoPoint>) = l.joinToString(",", "[", "]") { pair(it) }
    private fun point(a: JSONArray) = GeoPoint(r6(a.getDouble(0)), r6(a.getDouble(1)))
    private fun points(a: JSONArray?): List<GeoPoint> =
        if (a == null) emptyList() else (0 until a.length()).map { point(a.getJSONArray(it)) }
}

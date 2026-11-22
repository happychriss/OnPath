package com.onpath.app.data

import com.onpath.app.debug.Problems
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import org.json.JSONArray
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

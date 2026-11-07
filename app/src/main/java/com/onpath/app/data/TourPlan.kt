package com.onpath.app.data

import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * The plan a recorded tour followed (owner 2026-11-07: "from a tour under Gemacht I must always get back to the plan I
 * followed, to ride it again"): a frozen COPY, not a reference – the planned route can be changed or deleted later. The
 * last version counts: replanned during the tour, the copy is the plan as it was at the end. Its via points with names,
 * the POI pins, the activity, the T level and the computed line; a round trip is a plan whose last point is its first
 * (PlanEdits.isRoundTrip), so it needs no flag. Stored in `tour_plan` (TourDb, schema 6), carried in the backup record
 * (BackupMeta.plan). A tour that followed no plan ("Nur aufzeichnen", a followed GPX track) has none.
 */
data class TourPlan(
    /** The followed plan's name (its saved route's name), null = an unsaved plan. */
    val name: String?,
    val activity: String,
    val sac: Int,
    /** The via points with their names (a round trip: the last is the first). */
    val waypoints: List<GpxWaypoint>,
    /** Indices of [waypoints] pinned on a POI. */
    val pinned: Set<Int> = emptySet(),
    /** The plan's computed line (the via points when it had none yet). */
    val line: List<TrackPoint> = emptyList(),
    val distanceM: Double = 0.0,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val durationS: Double = 0.0,
    /** uuid of the saved route the plan was, when it was one (loading prefers that route while it is unchanged). */
    val sourceUuid: String? = null,
    /** When this version was taken (ms). */
    val updatedMs: Long = 0L,
) {
    /** A plan worth keeping: two points at least. */
    val usable: Boolean get() = waypoints.size >= 2

    fun toJson(): JSONObject = JSONObject().apply {
        name?.let { put("name", it) }
        put("activity", activity); put("sac", sac)
        put("waypoints", JSONArray(TourDb.waypointsToJson(waypoints)))
        put("pinned", JSONArray(TourDb.pinnedToJson(pinned)))
        put("line", JSONArray(lineToJson(line)))
        put("distance", distanceM); put("ascent", ascentM); put("descent", descentM); put("duration", durationS)
        sourceUuid?.let { put("source", it) }
        put("updated", updatedMs)
    }

    companion object {
        /** A plan of a backup record / an older or damaged one: null when it has no two points (reported, never thrown). */
        fun fromJson(o: JSONObject?): TourPlan? {
            if (o == null) return null
            return try {
                TourPlan(
                    name = if (o.has("name")) o.getString("name") else null,
                    activity = o.optString("activity", "HIKE").ifBlank { "HIKE" }, sac = o.optInt("sac", 2),
                    waypoints = TourDb.jsonToWaypoints(o.optJSONArray("waypoints")?.toString()),
                    pinned = TourDb.jsonToPinned(o.optJSONArray("pinned")?.toString()),
                    line = lineFromJson(o.optJSONArray("line")?.toString()),
                    distanceM = o.optDouble("distance", 0.0), ascentM = o.optDouble("ascent", 0.0),
                    descentM = o.optDouble("descent", 0.0), durationS = o.optDouble("duration", 0.0),
                    sourceUuid = if (o.has("source")) o.getString("source") else null,
                    updatedMs = o.optLong("updated", 0L),
                ).takeIf { it.usable }
            } catch (e: Exception) {
                Problems.warn("db", "The followed plan of a tour is unreadable – the tour stays without it", e); null
            }
        }

        /** The line as `[[lat, lon, ele?], …]` (6 decimals ≈ 0.1 m; the height to 0.1 m). */
        fun lineToJson(line: List<TrackPoint>): String = buildString {
            append('[')
            line.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append('[').append(r6(p.lat)).append(',').append(r6(p.lon))
                p.ele?.takeIf { it.isFinite() }?.let { append(',').append(Math.round(it * 10) / 10.0) }
                append(']')
            }
            append(']')
        }

        fun lineFromJson(json: String?): List<TrackPoint> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val a = JSONArray(json)
                (0 until a.length()).map { i ->
                    val p = a.getJSONArray(i)
                    TrackPoint(p.getDouble(0), p.getDouble(1), if (p.length() > 2 && !p.isNull(2)) p.getDouble(2) else null)
                }
            } catch (e: Exception) { Problems.warn("db", "The line of a tour's followed plan is unreadable – kept without it", e); emptyList() }
        }

        private fun r6(v: Double) = Math.round(v * 1e6) / 1e6
    }
}

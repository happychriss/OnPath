package com.onpath.app.rides

import com.onpath.app.debug.Problems
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bus / car / lift during a recording (docs/design/vehicle-detection.md, owner 2026-10-22). Nothing happens during the
 * ride; at "End tour" the rides found ([RideDetector]) are shown with four choices, and the choice decides what counts.
 * The recorded points are never changed or removed – a [RideRecord] on the tour only says how to read them.
 */
enum class RideKind { VEHICLE, LIFT }

/**
 * One ride: [from] = the last point on foot / by bike before it (the waiting at the stop is cut off), [to] = the first
 * point after it. Both are indices into the tour's points in order (all segments flattened).
 */
data class Ride(val from: Int, val to: Int, val kind: RideKind)

/** What the user chose at "End tour" (or later with "Edit rides"). */
enum class RideChoice {
    /** "Save only until 16:42" – the last ride and everything after it is not part of the tour (still stored). */
    UNTIL,
    /** "Save all, rides not counted" – rides drawn grey, not in km / time / ascent. */
    NOT_COUNTED,
    /** "Split into two tours" – this tour ends before the first ride, the parts after rides become new tours. */
    SPLIT,
    /** "Save as recorded" – everything counts. */
    AS_RECORDED,
}

/** Distance / ascent / descent / time of a tour. */
data class TourStats(val distanceM: Double, val ascentM: Double, val descentM: Double, val durationS: Double)

/**
 * Stored with a recorded tour (`tours.rides`, JSON; also in its backup file): the rides found, the choice and the stats
 * as recorded – so the choice can be changed later ("Edit rides") without losing anything. [endMs] = when the tour
 * ended (the time after the last point counts for "Save only until").
 */
data class RideRecord(
    val rides: List<Ride>,
    val choice: RideChoice,
    val recorded: TourStats,
    val endMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("rides", JSONArray().apply {
            rides.forEach { r -> put(JSONObject().put("from", r.from).put("to", r.to).put("kind", r.kind.name)) }
        })
        put("choice", choice.name)
        put("recorded", JSONObject().put("distance", recorded.distanceM).put("ascent", recorded.ascentM)
            .put("descent", recorded.descentM).put("duration", recorded.durationS))
        endMs?.let { put("end", it) }
    }

    companion object {
        /** Null for no / an unreadable record (reported): the tour is then simply shown as recorded. */
        fun fromJson(json: String?): RideRecord? {
            if (json.isNullOrBlank()) return null
            return try { fromJson(JSONObject(json)) } catch (e: Exception) {
                Problems.warn("rides", "Stored rides of a tour unreadable – the tour is shown as recorded", e); null
            }
        }

        fun fromJson(o: JSONObject): RideRecord {
            val a = o.getJSONArray("rides")
            val rides = (0 until a.length()).map { i ->
                val r = a.getJSONObject(i)
                Ride(r.getInt("from"), r.getInt("to"), RideKind.valueOf(r.getString("kind")))
            }
            val s = o.getJSONObject("recorded")
            return RideRecord(
                rides, RideChoice.valueOf(o.getString("choice")),
                TourStats(s.getDouble("distance"), s.getDouble("ascent"), s.getDouble("descent"), s.getDouble("duration")),
                if (o.has("end")) o.getLong("end") else null,
            )
        }
    }
}

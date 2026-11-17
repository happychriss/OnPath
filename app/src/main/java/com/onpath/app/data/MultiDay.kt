package com.onpath.app.data

import com.onpath.app.debug.Problems
import com.onpath.app.routing.DayLimits
import com.onpath.app.routing.Hotel
import com.onpath.app.routing.NightMark
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.PlanDays
import com.onpath.app.routing.SharedPlan
import org.json.JSONArray
import org.json.JSONObject

/**
 * A planned route's multi-day part as the phone stores it (docs/design/multi-day-tours.md §5, stage 4): the number of
 * days N, the limits per day, the night marks by point index (Übernachtung / Hotel with name, address, link) and the
 * description – the fields [SharedPlan] carries beside the points. Null on a [Tour] = a normal route. Stored as one
 * JSON text in `tours.days` (schema 9); unreadable text reads as no days (reported), never throws.
 */
data class MultiDay(
    val days: Int,
    val limits: DayLimits = DayLimits.NONE,
    val nights: Map<Int, NightMark> = emptyMap(),
    val description: String? = null,
) {
    /** [plan] with these days (the points, pins and names are the plan's). */
    fun into(plan: SharedPlan): SharedPlan = plan.copy(days = days, limits = limits,
        nights = PlanDays.clean(plan.points, nights), description = description)

    fun toJson(): String = JSONObject().apply {
        put("days", days)
        limits.maxTimeMin?.let { put("maxTimeMin", it) }
        limits.maxAscentM?.let { put("maxAscentM", it) }
        put("nights", JSONArray().apply {
            nights.toSortedMap().forEach { (i, m) ->
                put(JSONObject().apply {
                    put("i", i)
                    m.hotel?.let { h ->
                        put("hotel", JSONObject().apply {
                            h.name?.let { put("name", it) }; h.address?.let { put("address", it) }; h.link?.let { put("link", it) }
                        })
                    }
                })
            }
        })
        description?.let { put("description", it) }
    }.toString()

    companion object {
        /** The days of [plan]; null for a normal plan (no N). */
        fun of(plan: SharedPlan): MultiDay? = plan.days?.let { MultiDay(it, plan.limits, plan.nights, plan.description) }

        /** The days stored in [json]; null for none or unreadable text (reported – the route stays, without its days). */
        fun fromJson(json: String?): MultiDay? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val days = o.getInt("days").takeIf { it in 1..PlanCode.MAX_DAYS } ?: return null
                val limits = DayLimits(o.optInt("maxTimeMin", 0).takeIf { it > 0 }, o.optInt("maxAscentM", 0).takeIf { it > 0 })
                val nights = HashMap<Int, NightMark>()
                o.optJSONArray("nights")?.let { a ->
                    for (k in 0 until a.length()) {
                        val n = a.getJSONObject(k)
                        val h = n.optJSONObject("hotel")
                        nights[n.getInt("i")] = if (h == null) NightMark.OVERNIGHT
                            else NightMark(Hotel(h.optString("name").ifEmpty { null }, h.optString("address").ifEmpty { null },
                                h.optString("link").ifEmpty { null }))
                    }
                }
                MultiDay(days, limits, nights, o.optString("description").ifEmpty { null })
            } catch (e: Exception) {
                Problems.warn("db", "Stored days of a multi-day route unreadable – shown as a normal route", e); null
            }
        }
    }
}

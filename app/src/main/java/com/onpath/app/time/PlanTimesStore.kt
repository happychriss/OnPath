package com.onpath.app.time

import com.onpath.app.debug.Problems
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Average-pace moving time per learning group (down / flat / up) of each saved planned route (`plan-times.json`, atomic
 * tmp + rename), written when a route is saved. Saved routes keep no way attributes ([com.onpath.app.routing.WayAttrs]),
 * so without this the route list would time them from the slope alone and disagree with the plan sheet (P4).
 * Entries are tied to the route's stored distance; a mismatch or a broken file = nothing known (time from the points).
 */
class PlanTimesStore(private val file: File) {

    /** Seconds per group for [tourId] if stored for a route of [distanceM] (±1 m), else null. */
    fun get(tourId: Long, distanceM: Double): DoubleArray? = try {
        read().optJSONObject(tourId.toString())?.takeIf { kotlin.math.abs(it.getDouble("d") - distanceM) <= 1.0 }?.let { o ->
            val a = o.getJSONArray("g")
            DoubleArray(PaceModel.GROUPS) { a.getDouble(it) }.takeIf { g -> g.all { it.isFinite() && it >= 0 } }
        }
    } catch (e: Exception) {
        Problems.warn("time", "Plan times file (plan-times.json) entry unreadable; planned times computed from the points", e)
        null
    }

    fun put(tourId: Long, distanceM: Double, groups: DoubleArray) {
        try {
            val o = read()
            o.put(tourId.toString(), JSONObject().put("d", distanceM).put("g", JSONArray().apply { groups.forEach { put(it) } }))
            val keys = o.keys().asSequence().mapNotNull { it.toLongOrNull() }.sorted().toList()
            keys.take((keys.size - MAX).coerceAtLeast(0)).forEach { o.remove(it.toString()) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) Problems.error("time", "Plan times file (plan-times.json) could not be saved (rename failed)")
            }
        } catch (e: Exception) {
            Problems.error("time", "Plan times file (plan-times.json) could not be saved", e)
        }
    }

    private fun read(): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        Problems.warn("time", "Plan times file (plan-times.json) unreadable; starting empty", e)
        JSONObject()
    }

    companion object {
        const val FILE = "plan-times.json"
        private const val MAX = 1000

        /** Rounded moving time from stored group seconds with factors [f] (same rounding as everywhere). */
        fun seconds(groups: DoubleArray, f: Factors): Double =
            TimeEstimate.round((0 until PaceModel.GROUPS).sumOf { f[it] * groups[it] })
    }
}

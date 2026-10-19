package com.onpath.app.time

import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/**
 * Learning across tours (docs/plan-time-estimate.md §4.4): per activity, the down / flat / up factors learned on
 * earlier tours (relative to the model × pace setting) and the break ratio. Updated after every tour with at least
 * [MIN_LEARNED_S] of moving time on a followed route; each tour weighs by its time per group, older tours fade with a
 * half-life of [HALF_LIFE_TOURS] tours. Immutable; persisted as JSON in Prefs. Reset per activity in Settings.
 */
class PaceStore private constructor(private val entries: Map<Activity, Entry>) {

    data class Entry(
        val factors: Factors,
        /** Weight per group (hours of compared walking / riding). */
        val weights: List<Double>,
        /** Break time ÷ moving time (not used for the arrival clock yet: fixed 10 %, owner decision Q2). */
        val breakRatio: Double,
        val breakWeight: Double,
        val tours: Int,
    )

    /** Learned factors for [a], null = nothing learned. */
    fun factors(a: Activity): Factors? = entries[a]?.takeIf { it.tours > 0 }?.factors

    fun entry(a: Activity): Entry? = entries[a]?.takeIf { it.tours > 0 }

    /** Activities with something learned, in the order of [Activity]. */
    fun learned(): List<Pair<Activity, Entry>> = Activity.entries.mapNotNull { a -> entry(a)?.let { a to it } }

    fun reset(a: Activity): PaceStore = PaceStore(entries - a)

    /**
     * After a tour: [tripFactors] = what the trip learned relative to the prior it ran with, [observedP] = compared
     * predicted seconds per group, [stored] = the learned factors the tour started from (null = none).
     * Returns this store unchanged when the tour had less than [MIN_LEARNED_S] on the route.
     */
    fun update(a: Activity, tripFactors: Factors, observedP: DoubleArray, observedA: DoubleArray, stored: Factors?,
               movingS: Double, breakS: Double): PaceStore {
        if (observedA.sum() < MIN_LEARNED_S) return this
        val old = entries[a]
        val decay = 0.5.pow(1.0 / HALF_LIFE_TOURS)
        val base = stored ?: Factors.ONE
        val f = DoubleArray(PaceModel.GROUPS)
        val w = DoubleArray(PaceModel.GROUPS)
        for (g in 0 until PaceModel.GROUPS) {
            val oldW = (old?.weights?.getOrNull(g) ?: 0.0) * decay
            val oldF = old?.factors?.get(g) ?: 1.0
            val newW = observedP[g] / 3600
            val newF = (base[g] * tripFactors[g]).coerceIn(TripPace.MIN_F, TripPace.MAX_F)
            w[g] = oldW + newW
            f[g] = if (w[g] > 0) ((oldW * oldF + newW * newF) / w[g]).coerceIn(TripPace.MIN_F, TripPace.MAX_F) else oldF
        }
        val bw = (old?.breakWeight ?: 0.0) * decay + movingS / 3600
        val br = if (bw > 0 && movingS > 0) ((old?.breakRatio ?: 0.0) * (old?.breakWeight ?: 0.0) * decay + breakS / movingS * movingS / 3600) / bw
        else old?.breakRatio ?: 0.0
        return PaceStore(entries + (a to Entry(Factors.of(f), w.toList(), br, bw, (old?.tours ?: 0) + 1)))
    }

    fun toJson(): String = JSONObject().apply {
        put("v", 1)
        entries.forEach { (a, e) ->
            put(a.name, JSONObject().put("f", JSONArray(e.factors.toArray().toList())).put("w", JSONArray(e.weights))
                .put("br", e.breakRatio).put("bw", e.breakWeight).put("n", e.tours))
        }
    }.toString()

    companion object {
        /** Only tours with this much moving time on a followed route teach across tours (owner decision Q4). */
        const val MIN_LEARNED_S = 1800.0
        const val HALF_LIFE_TOURS = 5.0

        val EMPTY = PaceStore(emptyMap())

        /** Never throws: a broken or missing value = nothing learned. */
        fun fromJson(s: String?): PaceStore {
            if (s.isNullOrBlank()) return EMPTY
            return try {
                val o = JSONObject(s)
                val m = HashMap<Activity, Entry>()
                for (a in Activity.entries) {
                    val e = o.optJSONObject(a.name) ?: continue
                    val f = e.optJSONArray("f") ?: continue
                    val fs = DoubleArray(PaceModel.GROUPS) { f.optDouble(it, 1.0) }
                    if (fs.any { it.isNaN() || it <= 0 }) continue
                    val w = e.optJSONArray("w")
                    m[a] = Entry(Factors.of(DoubleArray(PaceModel.GROUPS) { fs[it].coerceIn(TripPace.MIN_F, TripPace.MAX_F) }),
                        List(PaceModel.GROUPS) { w?.optDouble(it, 0.0)?.takeIf { v -> !v.isNaN() && v >= 0 } ?: 0.0 },
                        e.optDouble("br", 0.0).takeIf { !it.isNaN() } ?: 0.0, e.optDouble("bw", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                        e.optInt("n", 0))
                }
                PaceStore(m)
            } catch (e: Exception) {
                Problems.warn("time", "Learned pace unreadable; starting without learned pace", e)
                EMPTY
            }
        }
    }
}

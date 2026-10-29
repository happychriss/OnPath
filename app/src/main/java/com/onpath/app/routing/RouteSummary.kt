package com.onpath.app.routing

import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource
import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure helpers for the Komoot-style route summary (breakdown, difficulty, gradient). */
object RouteSummary {

    /** Sums several breakdowns (e.g. one per leg) per key, largest first; drops empty keys. */
    fun mergeShares(parts: List<List<Share>>): List<Share> {
        val sum = LinkedHashMap<String, Double>()
        parts.flatten().forEach { if (it.meters > 0) sum[it.key] = (sum[it.key] ?: 0.0) + it.meters }
        return sum.map { Share(it.key, it.value) }.sortedByDescending { it.meters }
    }

    enum class Difficulty(private val res: StringResource) {
        EASY(Res.string.plan_difficulty_easy), MODERATE(Res.string.plan_difficulty_moderate), DIFFICULT(Res.string.plan_difficulty_difficult);

        /** "Easy" / "Moderate" / "Difficult" in the app's language. */
        val label: String get() = Txt.s(res)
    }

    /**
     * Difficulty from distance and ascent alone ("effort kilometres", fitness only):
     *   effort = km + ascentM / climbFactor
     * Hike uses the Swiss/DIN "Leistungskilometer" (100 m up = 1 km, about 4 per hour at an average pace) with
     * Komoot's documented hiking bands (owner): easy ≤ 8 (≈ 2 h) and ≤ 300 m up, moderate ≤ 20 (≈ 5 h) and
     * ≤ 900 m up. Bikes count climbing more cheaply (100 m up = 4 km touring/e-bike, 5 km MTB), no ascent cap:
     *   BIKE_TOURING /
     *   BIKE_TREKKING   easy ≤ 40, moderate ≤ 80
     *   EBIKE           easy ≤ 60, moderate ≤ 120
     *   MTB             easy ≤ 25, moderate ≤ 50
     *   EMTB            easy ≤ 40, moderate ≤ 80
     * Above "moderate" = difficult. The label shown on a route also counts the trail technique: see [rating].
     */
    fun difficulty(activity: Activity, distanceM: Double, ascentM: Double): Difficulty {
        val (climbFactor, easy, moderate) = when (activity) {
            Activity.HIKE -> Triple(100.0, 8.0, 20.0)
            Activity.BIKE_TOURING, Activity.BIKE_TREKKING -> Triple(25.0, 40.0, 80.0)
            Activity.EBIKE -> Triple(25.0, 60.0, 120.0)
            Activity.MTB -> Triple(20.0, 25.0, 50.0)
            Activity.EMTB -> Triple(20.0, 40.0, 80.0)
        }
        val hike = activity == Activity.HIKE
        val effort = distanceM / 1000.0 + ascentM / climbFactor
        return when {
            effort <= easy && (!hike || ascentM <= HIKE_EASY_UP_M) -> Difficulty.EASY
            effort <= moderate && (!hike || ascentM <= HIKE_MODERATE_UP_M) -> Difficulty.MODERATE
            else -> Difficulty.DIFFICULT
        }
    }

    const val HIKE_EASY_UP_M = 300.0
    const val HIKE_MODERATE_UP_M = 900.0
    /** A trail grade counts only from this many metres (summed over the route): a few tagged metres are noise. */
    const val TECHNIQUE_MIN_M = 100.0
    /** Hike: this much T2 or harder makes it at least Moderate – hours on a mountain path ask for sure-footedness (owner). */
    const val HIKE_T2_MIN_M = 2_000.0
    /** Bike / E-bike: this share unpaved (in whole %) makes it at least Moderate – a quarter off the tarmac is felt on a touring bike (owner). */
    const val BIKE_UNPAVED_SHARE_PCT = 25
    /** Bike / E-bike: this much loose ground makes it at least Moderate, however long the route – a long loose stretch is hard on a touring bike even when it is a small share (owner). */
    const val BIKE_LOOSE_MIN_M = 5_000.0

    /** Surface keys ([WayAttrs.surface]) that are not paved: every one of them counts for the unpaved share. */
    private val UNPAVED = setOf("gravel", "unpaved", "ground", "natural_trail", "alpine")
    /**
     * Loose ground: unpaved but not "gravel" – that key folds in compacted and fine gravel, fine on any bike (like the
     * map's loose-surface edge, docs/map-style-notes.md) – or any surface with smoothness bad or worse, or rock / scree.
     */
    private val LOOSE = setOf("unpaved", "ground", "natural_trail", "alpine")
    private val ROUGH_SMOOTHNESS = setOf("bad", "very_bad", "horrible", "very_horrible", "impassable")

    /** Why a route is rated higher than distance and ascent give: a trail grade, or (Bike / E-bike) unpaved ground. */
    enum class Reason { GRADE, UNPAVED_SHARE, LOOSE }

    /**
     * What raised a route: [grade] "T3" / "S2" / "unpaved" (the badge word), [meters] at that grade or harder (unpaved /
     * loose metres for the surface reasons), [sharePct] the unpaved share in whole % (surface reasons only), the
     * [level] it asks for.
     */
    data class Technique(val level: Difficulty, val grade: String, val meters: Double,
                         val reason: Reason = Reason.GRADE, val sharePct: Int = 0) {
        /** The badge word: the trail grade ("T3", "S2"), or "unpaved" in the app's language. */
        val gradeLabel: String get() = if (grade == UNPAVED_WORD) Txt.s(Res.string.plan_difficulty_unpaved) else grade

        /** Route details' grey line "Moderate because …" for [level]. */
        fun because(level: String): String = when (reason) {
            Reason.GRADE -> Txt.s(Res.string.plan_because_grade, level, Format.distance(meters), grade)
            Reason.UNPAVED_SHARE -> Txt.s(Res.string.plan_because_unpaved, level, sharePct, Format.distance(meters))
            Reason.LOOSE -> Txt.s(Res.string.plan_because_loose, level, Format.distance(meters))
        }
    }

    /**
     * The label shown on a route: [level], and [raisedBy] when the trail technique (or a Bike's unpaved ground) raised it
     * above what distance and ascent alone give. [text] = "Moderate" or "Moderate · T3"; [because] = Route details' grey
     * line ("Moderate because of 250 m at T3 or harder"), null when nothing raised it.
     */
    data class Rating(val level: Difficulty, val raisedBy: Technique? = null) {
        val text: String get() = if (raisedBy == null) level.label else "${level.label} · ${raisedBy.gradeLabel}"
        val because: String? get() = raisedBy?.because(level.label)
    }

    /**
     * Difficulty like Komoot's ("fitness, sport and technical complexity", owner): [difficulty] from distance
     * and ascent, raised to what [technique] asks for when that is higher (trail grade T3+ / S2+, a hike's long T2, a
     * Bike's or E-bike's unpaved share or long loose stretch). Never lowered.
     */
    fun rating(activity: Activity, distanceM: Double, ascentM: Double, points: List<GeoPoint>): Rating {
        val fitness = difficulty(activity, distanceM, ascentM)
        val t = technique(activity, points)
        return if (t != null && t.level > fitness) Rating(t.level, t) else Rating(fitness)
    }

    /**
     * The level the trail technique of [points] asks for: the hardest grade with at least [TECHNIQUE_MIN_M] at it or
     * harder (summed over the route), from the way attributes of the points (lines without them – straight legs,
     * imported tracks – have none: null).
     * - Hike (SAC): T3 → at least Moderate, T4 or harder → Difficult (Komoot: SAC 1 easy, 2–3 moderate, 4–6 difficult);
     *   T2, the everyday mountain path, raises to Moderate only from [HIKE_T2_MIN_M] at T2 or harder.
     * - Every bike activity (mtb:scale, Komoot's legend S0–S1 easy, S2 moderate, S3–S5 difficult): S2 → at least
     *   Moderate, S3 or harder → Difficult; a hiking trail of T3 or harder counts like S3 (Komoot: T3+ trails are
     *   highly dangerous on a bike).
     * - Bike and E-bike only (touring.brf; owner: Gravel bike, MTB, E-MTB are made for it): at least
     *   Moderate when [BIKE_UNPAVED_SHARE_PCT] % or more of the route is unpaved ([UNPAVED]) or [BIKE_LOOSE_MIN_M] or
     *   more is loose ground ([LOOSE], smoothness bad or worse) – badge word "unpaved". The share is of the whole line;
     *   spans without way attributes count as not unpaved. A grade (S2, S3+, T3+) is named first.
     */
    fun technique(activity: Activity, points: List<GeoPoint>): Technique? {
        if (points.size < 2) return null
        val sacM = DoubleArray(7)   // metres with SAC ≥ k (T1..T6)
        val mtbM = DoubleArray(7)   // metres with mtb:scale ≥ k (S0..S6)
        var totalM = 0.0; var unpavedM = 0.0; var looseM = 0.0; var known = false
        for (i in 0 until points.size - 1) {
            val len = Geo.distance(points[i], points[i + 1])
            totalM += len
            val w = points[i].way ?: continue
            known = true
            for (k in 1..minOf(w.sac, 6)) sacM[k] += len
            for (k in 0..minOf(w.mtb, 6)) mtbM[k] += len
            if (w.surface in UNPAVED) unpavedM += len
            if (w.surface in LOOSE || w.rough != null ||
                (w.smoothness in ROUGH_SMOOTHNESS && w.surface != "asphalt" && w.surface != "paved")) looseM += len
        }
        if (!known) return null
        val sac = (6 downTo 1).firstOrNull { sacM[it] >= TECHNIQUE_MIN_M }
        if (activity == Activity.HIKE) return when {
            sac != null && sac >= 3 -> Technique(if (sac >= 4) Difficulty.DIFFICULT else Difficulty.MODERATE, "T$sac", sacM[sac])
            sacM[2] >= HIKE_T2_MIN_M -> Technique(Difficulty.MODERATE, "T2", sacM[2])
            else -> null
        }
        val mtb = (6 downTo 2).firstOrNull { mtbM[it] >= TECHNIQUE_MIN_M }
        val sharePct = if (totalM > 0) (unpavedM / totalM * 100).roundToInt() else 0
        val touring = activity == Activity.BIKE_TOURING || activity == Activity.EBIKE
        return when {
            mtb != null && mtb >= 3 -> Technique(Difficulty.DIFFICULT, "S$mtb", mtbM[mtb])
            sac != null && sac >= 3 -> Technique(Difficulty.DIFFICULT, "T$sac", sacM[sac])
            mtb == 2 -> Technique(Difficulty.MODERATE, "S2", mtbM[2])
            !touring -> null
            unpavedM >= TECHNIQUE_MIN_M && sharePct >= BIKE_UNPAVED_SHARE_PCT ->
                Technique(Difficulty.MODERATE, UNPAVED_WORD, unpavedM, Reason.UNPAVED_SHARE, sharePct)
            looseM >= BIKE_LOOSE_MIN_M -> Technique(Difficulty.MODERATE, UNPAVED_WORD, looseM, Reason.LOOSE, sharePct)
            else -> null
        }
    }

    /** Badge word for the surface reasons ("Moderate · unpaved"). */
    internal const val UNPAVED_WORD = "unpaved"

    /** Gradient classes for colouring the elevation profile (absolute slope, up or down). */
    enum class Steepness { FLAT, MODERATE, STEEP, VERY_STEEP }

    fun steepnessOf(gradePercent: Double): Steepness {
        val g = abs(gradePercent)
        return when {
            g < 5 -> Steepness.FLAT
            g < 10 -> Steepness.MODERATE
            g < 15 -> Steepness.STEEP
            else -> Steepness.VERY_STEEP
        }
    }

    /**
     * Steepness per profile segment i (between sample i and i+1). Slope is measured over a
     * window of at least [windowM] around the segment so DEM noise does not paint red stripes.
     * [dist] cumulative metres (ascending), [ele] elevation, same size.
     */
    fun steepness(dist: DoubleArray, ele: DoubleArray, windowM: Double = 100.0): List<Steepness> {
        val n = dist.size
        if (n < 2) return emptyList()
        return (0 until n - 1).map { i ->
            val mid = (dist[i] + dist[i + 1]) / 2
            var a = i; var b = i + 1
            while (dist[b] - dist[a] < windowM && (a > 0 || b < n - 1)) {
                // widen towards the side that keeps the window centred on the segment
                if (a > 0 && (b == n - 1 || mid - dist[a - 1] <= dist[b + 1] - mid)) a-- else b++
            }
            val run = dist[b] - dist[a]
            steepnessOf(if (run <= 0) 0.0 else (ele[b] - ele[a]) / run * 100)
        }
    }
}

package com.onpath.app.ui

import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.BRouterEngine
import com.onpath.app.routing.GeoPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a spot on the route is (order = order in the amber line). */
enum class SpotKind { PUSH, STEPS, ABOVE_T, STEEP }

/**
 * One spot worth knowing on the planned route: points [from]..[to] of the route line, [fromM]..[toM] along it.
 * [worst] = the highest SAC level (ABOVE_T) or the steepest grade in % (STEEP); 0 otherwise.
 */
data class RouteSpot(val kind: SpotKind, val from: Int, val to: Int, val fromM: Double, val toM: Double, val worst: Int = 0) {
    val lengthM: Double get() = toM - fromM
}

/**
 * Safety audit batch B (owner 2026-10-22): ONE amber line in the plan sheet when the route has something to know, e.g.
 * "1.2 km pushing · steps · 2 sections above T2"; tap → the list of spots. Pure, unit tested (RouteWarningsTest).
 *
 * What counts (only what the route's way attributes – BRouter way tags per span – and its elevations reliably give):
 * - PUSH (bikes): any way signed bicycle=dismount; footway / path with bicycle=no; footway / pedestrian without bike
 *   access (touring / trekking / e-bike: not a footway on a signed cycle route, owner 2026-10-25 – a pedestrian zone stays
 *   pushing); touring / trekking / e-bike also paths of SAC ≥ T2 or
 *   mtb:scale ≥ S2, MTB / e-MTB only paths of SAC ≥ T4
 *   or mtb:scale ≥ S4. A footway / path with bicycle=yes / designated / permissive (/ mtb) is never pushing. Sections
 *   < [PUSH_MIN_M] (a crossing, a short connector) are not listed.
 * - STEPS (bikes): highway=steps, any length. Hikers: steps are normal, not listed.
 * - ABOVE_T (hike): SAC level above the chosen T level, sections ≥ [ABOVE_T_MIN_M].
 * - STEEP: grade over a [STEEP_WINDOW_M] window (DEM noise) above [steepPercent] – hike 25 %, MTB / e-MTB 20 %,
 *   other bikes 15 % – up or down, sections ≥ [STEEP_MIN_M].
 * Spans of the same kind less than [MERGE_GAP_M] apart are one spot.
 */
object RouteWarnings {
    const val PUSH_MIN_M = 50.0
    const val ABOVE_T_MIN_M = 20.0
    const val STEEP_MIN_M = 80.0
    const val STEEP_WINDOW_M = 100.0
    const val MERGE_GAP_M = 30.0
    /** At most this many items in the line, then "+N". */
    const val MAX_ITEMS = 3

    fun isBike(a: Activity) = a != Activity.HIKE

    /** OSM `bicycle` values (as BRouter reports them) that open a footway / path to bikes. */
    val BIKES_ALLOWED = setOf("yes", "designated", "permissive", "mtb")

    /**
     * Profiles that cost a footway without a bicycle tag on a signed cycle route as ridden (accesspenalty 0, owner
     * 2026-10-25); mtb.brf still costs it as pushing, so the warning stays there for MTB / e-MTB.
     */
    private val FOOTWAY_ON_ROUTE_PROFILES = setOf("touring.brf", "trekking.brf")
    private fun footwayOnRouteRidden(a: Activity) = BRouterEngine.profileFile(a) in FOOTWAY_ON_ROUTE_PROFILES

    /** Grade (%) above which a section is "steep" for [a]. */
    fun steepPercent(a: Activity): Double = when (a) {
        Activity.HIKE -> 25.0
        Activity.MTB, Activity.EMTB -> 20.0
        else -> 15.0
    }

    /** Spots of the route [points] for [activity] at hiking level [sac] (1..5), in route order. */
    fun detect(points: List<GeoPoint>, activity: Activity, sac: Int): List<RouteSpot> {
        if (points.size < 2) return emptyList()
        val cum = Geo.cumulative(points)
        val out = ArrayList<RouteSpot>()
        // way kinds: span i = from point i to i+1, described by points[i].way
        for (kind in listOf(SpotKind.PUSH, SpotKind.STEPS, SpotKind.ABOVE_T)) {
            val hits = BooleanArray(points.size - 1) { i -> wayHit(kind, points[i], activity, sac) }
            val worst = { i: Int -> if (kind == SpotKind.ABOVE_T) points[i].way?.sac ?: 0 else 0 }
            out += spans(hits, cum, kind, worst, when (kind) { SpotKind.PUSH -> PUSH_MIN_M; SpotKind.ABOVE_T -> ABOVE_T_MIN_M; else -> 0.0 })
        }
        val grades = grades(points, cum)
        val limit = steepPercent(activity)
        val steep = BooleanArray(points.size - 1) { i -> grades[i]?.let { abs(it) > limit } == true }
        out += spans(steep, cum, SpotKind.STEEP, { i -> grades[i]?.let { abs(it).roundToInt() } ?: 0 }, STEEP_MIN_M)
        return out.sortedWith(compareBy({ it.fromM }, { it.kind.ordinal }))
    }

    private fun wayHit(kind: SpotKind, p: GeoPoint, a: Activity, sac: Int): Boolean {
        val w = p.way ?: return false
        return when (kind) {
            SpotKind.STEPS -> isBike(a) && w.wayType == "steps"
            SpotKind.PUSH -> isBike(a) && w.wayType != "steps" && when {
                w.bicycle == "dismount" -> true // signed: get off and push, on any way
                w.wayType != "footway" && w.wayType != "path" -> false
                w.bicycle in BIKES_ALLOWED -> false // a footway / path open to bikes: ridden, no false warning (owner)
                w.bicycle == "no" -> true
                // a footway on a signed cycle route is ridden (owner 2026-10-25) – only where the profile costs
                // it so: touring.brf / trekking.brf (mtb.brf charges it as pushing), never in a pedestrian zone (no cycling
                // there unless signed, German law)
                w.wayType == "footway" -> !(w.cycleRoute && !w.pedestrian && footwayOnRouteRidden(a))
                a == Activity.MTB || a == Activity.EMTB -> w.sac >= 4 || w.mtb >= 4
                else -> w.sac >= 2 || w.mtb >= 2
            }
            SpotKind.ABOVE_T -> a == Activity.HIKE && w.sac > sac.coerceIn(1, 5)
            SpotKind.STEEP -> false
        }
    }

    /** Runs of true in [hits] (span i = point i..i+1), joined over gaps < [MERGE_GAP_M], kept when ≥ [minM]. */
    private fun spans(hits: BooleanArray, cum: DoubleArray, kind: SpotKind, worstOf: (Int) -> Int, minM: Double): List<RouteSpot> {
        val runs = ArrayList<RouteSpot>()
        var i = 0
        while (i < hits.size) {
            if (!hits[i]) { i++; continue }
            var j = i; var worst = 0
            while (j < hits.size && hits[j]) { worst = maxOf(worst, worstOf(j)); j++ }
            val run = RouteSpot(kind, i, j, cum[i], cum[j], worst)
            val last = runs.lastOrNull()
            if (last != null && run.fromM - last.toM < MERGE_GAP_M) runs[runs.size - 1] = last.copy(to = j, toM = cum[j], worst = maxOf(last.worst, worst))
            else runs += run
            i = j
        }
        return runs.filter { it.lengthM >= minM && it.lengthM > 0 }
    }

    /** Grade (%) per span over a window of at least [STEEP_WINDOW_M] around it; null where elevations are missing. */
    private fun grades(points: List<GeoPoint>, cum: DoubleArray): Array<Double?> {
        val n = points.size
        val out = arrayOfNulls<Double>(n - 1)
        if (cum[n - 1] < STEEP_WINDOW_M) return out
        var a = 0; var b = 0
        for (i in 0 until n - 1) {
            val mid = (cum[i] + cum[i + 1]) / 2
            // two pointers: a = last point at or before mid − w/2, b = first point at or after mid + w/2 (clamped)
            while (a + 1 < n && cum[a + 1] <= mid - STEEP_WINDOW_M / 2) a++
            if (b < i + 1) b = i + 1
            while (b < n - 1 && cum[b] < mid + STEEP_WINDOW_M / 2) b++
            var lo = minOf(a, i); var hi = b
            // at the ends: widen to the other side until the window is long enough
            while (cum[hi] - cum[lo] < STEEP_WINDOW_M && hi < n - 1) hi++
            while (cum[hi] - cum[lo] < STEEP_WINDOW_M && lo > 0) lo--
            val e1 = points[lo].ele; val e2 = points[hi].ele
            val run = cum[hi] - cum[lo]
            if (e1 != null && e2 != null && run > 0) out[i] = (e2 - e1) / run * 100
        }
        return out
    }

    // ---------------- text ----------------

    private fun rounded(m: Double): String = Format.distance(if (m < 1000) Math.round(m / 10.0) * 10.0 else m)

    private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

    /** The items of the line, in [SpotKind] order. */
    fun items(spots: List<RouteSpot>, sac: Int): List<String> = SpotKind.entries.mapNotNull { k ->
        val s = spots.filter { it.kind == k }
        if (s.isEmpty()) null else when (k) {
            SpotKind.PUSH -> "${rounded(s.sumOf { it.lengthM })} pushing"
            SpotKind.STEPS -> if (s.size == 1) "steps" else "${s.size}× steps"
            SpotKind.ABOVE_T -> plural(s.size, "section above T$sac", "sections above T$sac")
            SpotKind.STEEP -> plural(s.size, "steep section", "steep sections")
        }
    }

    /** The amber line, or null when there is nothing to know: at most [MAX_ITEMS] items, then " +N". */
    fun text(spots: List<RouteSpot>, sac: Int): String? {
        val items = items(spots, sac)
        if (items.isEmpty()) return null
        val more = items.size - MAX_ITEMS
        return items.take(MAX_ITEMS).joinToString(" · ") + if (more > 0) " +$more" else ""
    }

    /** INFO line "New route for Gravel bike – check it" (hike with its level: "for Hike T4"); grey, not amber. */
    fun changedText(a: Activity, sac: Int): String =
        "New route for ${com.onpath.app.data.LastActivity.name(a, sac)} – check it"

    /** Kind of one list row in plain words: "Pushing", "Steps", "T4 – above your T2", "Steep, up to 28 %". */
    fun rowTitle(s: RouteSpot, sac: Int): String = when (s.kind) {
        SpotKind.PUSH -> "Pushing"
        SpotKind.STEPS -> "Steps"
        SpotKind.ABOVE_T -> "T${s.worst} – above your T$sac"
        SpotKind.STEEP -> "Steep, up to ${s.worst} %"
    }

    /** Second part of a row: "350 m · after 3.4 km". */
    fun rowDetail(s: RouteSpot): String =
        "${rounded(s.lengthM)} · " + if (s.fromM < 50) "at the start" else "after ${Format.distance(Math.round(s.fromM / 100.0) * 100.0)}"

    /** Where tapping a row moves the map: the spot's piece of line (a short one with room around it). */
    fun showPoints(points: List<GeoPoint>, s: RouteSpot): List<GeoPoint> =
        points.subList(s.from.coerceIn(0, points.size - 1), (s.to + 1).coerceIn(1, points.size))

    /** Short pieces: centre + zoom (fitting a 40 m piece would zoom in too far); null = fit [showPoints]. */
    fun centerFor(points: List<GeoPoint>, s: RouteSpot): Pair<GeoPoint, Double>? {
        if (s.lengthM >= 400) return null
        val piece = showPoints(points, s)
        val mid = piece[piece.size / 2]
        return GeoPoint(mid.lat, mid.lon) to if (s.lengthM < 150) 17.0 else 16.0
    }

    // ---------------- "route changed" (owner addition) ----------------

    const val CHANGE_SHARE = 0.10
    const val MOVED_M = 50.0
    const val ASCENT_MIN_DIFF_M = 30.0

    /**
     * After an activity / T level change: the new route differs noticeably from [before] – length or ascent by more
     * than 10 % (ascent also by more than 30 m: 10 % of a flat ride is nothing), or more than 10 % of either line lies
     * more than 50 m from the other (sampled, ≤ 200 points).
     */
    fun changedNoticeably(before: List<GeoPoint>, beforeDistM: Double, beforeAscM: Double,
                          after: List<GeoPoint>, afterDistM: Double, afterAscM: Double): Boolean {
        if (before.size < 2 || after.size < 2) return false
        if (abs(afterDistM - beforeDistM) > CHANGE_SHARE * maxOf(beforeDistM, afterDistM)) return true
        val dAsc = abs(afterAscM - beforeAscM)
        if (dAsc > CHANGE_SHARE * maxOf(beforeAscM, afterAscM) && dAsc > ASCENT_MIN_DIFF_M) return true
        return movedShare(after, before) > CHANGE_SHARE || movedShare(before, after) > CHANGE_SHARE
    }

    /** Share of sampled points of [x] farther than [MOVED_M] from the line [y]. */
    fun movedShare(x: List<GeoPoint>, y: List<GeoPoint>): Double {
        val step = maxOf(1, x.size / 200)
        var n = 0; var far = 0
        for (i in x.indices step step) {
            n++
            if (Geo.distanceToLine(x[i].lat, x[i].lon, y) > MOVED_M) far++
        }
        return if (n == 0) 0.0 else far.toDouble() / n
    }
}

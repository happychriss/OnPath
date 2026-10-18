package com.onpath.app.time

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WayAttrs

/**
 * "Time details" (owner: a debug view to understand the estimate): the route's 50 m model segments grouped into
 * slope bands with distance, height, base time (Normal pace), the factor applied and the final time, and grouped by
 * way type / difficulty with the time from the slope alone, the way factor and the time on that way. Pure, tested.
 */
object TimeBreakdown {
    data class Band(val label: String, val lo: Double, val hi: Double)

    val BANDS = listOf(
        Band("↓↓ < −20 %", Double.NEGATIVE_INFINITY, -0.20),
        Band("↓ −20…−5 %", -0.20, -0.05),
        Band("→ ±5 %", -0.05, 0.05),
        Band("↑ 5…20 %", 0.05, 0.20),
        Band("↑↑ > 20 %", 0.20, Double.POSITIVE_INFINITY),
    )

    data class Row(val label: String, val distanceM: Double, val upM: Double, val downM: Double,
                   val baseS: Double, val factor: Double, val finalS: Double) {
        /** Average horizontal speed (km/h) at the final time. */
        val kmh: Double get() = if (finalS > 0) distanceM / finalS * 3.6 else 0.0
    }

    /** One way class: [slopeS] = time from the slope alone, [wayS] = with the way (both Normal pace, nothing learned). */
    data class WayRow(val label: String, val distanceM: Double, val slopeS: Double, val wayS: Double) {
        val factor: Double get() = if (slopeS > 0) wayS / slopeS else 1.0
    }

    data class Result(val activity: Activity, val rows: List<Row>, val baseS: Double, val finalS: Double,
                      val factors: Factors, val segmentM: Double, val segments: Int,
                      /** By way type / difficulty, longest first; empty when the line has no way attributes. */
                      val wayRows: List<WayRow> = emptyList(),
                      /** Normal pace from the slope alone (= [baseS] without way attributes). */
                      val slopeOnlyS: Double = baseS)

    fun of(points: List<GeoPoint>, activity: Activity, factors: Factors): Result {
        val rt = RouteTimes.of(points, activity)
        val segs = rt.pieces()
        val step = rt.segmentLengthM
        val rows = BANDS.map { b ->
            var d = 0.0; var up = 0.0; var down = 0.0; var base = 0.0; var fin = 0.0
            for (p in segs) if (p.grade >= b.lo && p.grade < b.hi) {
                d += step; base += p.seconds; fin += p.seconds * factors[PaceModel.group(p.grade)]
                if (p.dz > 0) up += p.dz else down -= p.dz
            }
            val f = if (base > 0) fin / base else factors[PaceModel.group((b.lo.coerceAtLeast(-1.0) + b.hi.coerceAtMost(1.0)) / 2)]
            Row(b.label, d, up, down, base, f, fin)
        }
        val wayRows = if (!rt.hasWays) emptyList() else {
            val m = LinkedHashMap<String, DoubleArray>()
            for (p in segs) m.getOrPut(wayLabel(p.way, activity)) { DoubleArray(3) }.let {
                it[0] += step; it[1] += p.slopeOnlyS; it[2] += p.seconds
            }
            m.map { (k, v) -> WayRow(k, v[0], v[1], v[2]) }.sortedByDescending { it.distanceM }
        }
        return Result(activity, rows, rows.sumOf { it.baseS }, rows.sumOf { it.finalS }, factors, step, segs.size,
            wayRows, segs.sumOf { it.slopeOnlyS })
    }

    /** Row label of a way for [activity]: hiking by SAC grade / way type, bikes by surface, MTB grade, pushing. */
    fun wayLabel(w: WayAttrs?, activity: Activity): String {
        if (w == null) return "Unknown"
        if (activity == Activity.HIKE) {
            val faint = if (w.visibility in setOf("bad", "horrible", "no")) ", faint" else ""
            return when {
                w.wayType == "steps" -> "Steps"
                w.sac > 0 -> "Trail T${w.sac}$faint"
                w.rough != null -> "Rock / scree"
                else -> when (w.wayType) {
                    "quiet_road", "road", "busy_road" -> "Road"
                    "track" -> "Track"
                    "path", "footway", "cycleway" -> "Path"
                    else -> "Other"
                } + faint
            }
        }
        if (w.wayType == "steps") return "Steps (carried)"
        val r = PaceModel.ride(activity, w)
        if (r.pushFactor > 0) return "Pushed (${if (w.mtb >= 0) "S${w.mtb}" else if (w.sac > 0) "T${w.sac}" else "rough"})"
        val surf = when (w.surface) {
            "asphalt" -> "Asphalt"; "paved" -> "Paved"; "gravel" -> "Gravel"; "unpaved" -> "Unpaved"
            "ground" -> "Ground"; "natural_trail" -> "Trail"; "alpine" -> "Alpine"
            else -> "Unknown surface"
        }
        return if (w.mtb > 0) "$surf S${w.mtb}" else surf
    }
}

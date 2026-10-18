package com.onpath.app.time

import com.onpath.app.data.Geo
import com.onpath.app.gpx.TrackPoint
import org.json.JSONObject

/**
 * Moving time vs. breaks (docs/plan-time-estimate.md §4.2): a break starts when you stay within [radiusM] for at least
 * [minS] and ends at the first fix further away. Shorter stops (photo, map check, gate) stay moving time, like in
 * signpost times. A GPS gap of at least [minS] in which you hardly moved (hut, indoors) is a break too.
 *
 * Fed with an "active" clock in seconds that excludes paused time (the recording's elapsed time), so a pause is never
 * counted twice. Pure, serialisable (survives a service restart).
 */
class BreakDetector(private val radiusM: Double = RADIUS_M, private val minS: Double = MIN_S) {
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var anchorS = 0.0
    private var lastInS = 0.0
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastS = 0.0
    /** Breaks that have ended (s). */
    private var closedS = 0.0
    var count = 0
        private set
    /** Recent fixes (s, lat, lon) before a break: the anchor is the earliest one all later fixes stay near. */
    private val recent = ArrayDeque<DoubleArray>()

    /** Standing still for at least [minS] right now. */
    val inBreak: Boolean get() = !anchorLat.isNaN() && lastInS - anchorS >= minS

    /** All break time so far, including a break that is still going on (s). */
    fun breakS(): Double = closedS + if (inBreak) lastInS - anchorS else 0.0

    fun onFix(activeS: Double, lat: Double, lon: Double) {
        if (anchorLat.isNaN()) { anchor(activeS, lat, lon); remember(activeS, lat, lon); return }
        if (activeS < lastS) { remember(activeS, lat, lon); return } // clock went back (restart): ignore once
        val gap = activeS - lastS
        val slowGap = gap >= minS && Geo.distance(lastLat, lastLon, lat, lon) / gap < GAP_SPEED_MS
        if (inBreak) {
            if (Geo.distance(anchorLat, anchorLon, lat, lon) <= radiusM) lastInS = activeS
            else {
                closedS += lastInS - anchorS
                if (slowGap) { closedS += gap; count++ } // indoors, then out of the door
                anchor(activeS, lat, lon)
            }
        } else {
            // walking on: the stop candidate starts at the earliest recent fix that every later fix stayed near
            while (recent.isNotEmpty() && Geo.distance(recent.first()[1], recent.first()[2], lat, lon) > radiusM) recent.removeFirst()
            if (recent.isEmpty()) {
                // a long GPS gap without real movement (hut, indoors): a break as well
                if (slowGap) { closedS += gap; count++ }
                anchor(activeS, lat, lon)
            } else {
                val a = recent.first()
                anchorS = a[0]; anchorLat = a[1]; anchorLon = a[2]
                lastInS = activeS
                if (inBreak) { count++; recent.clear() } else recent.addLast(doubleArrayOf(activeS, lat, lon))
            }
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
        remember(activeS, lat, lon)
    }

    /** Pause or a jump in the clock: an ongoing break ends here, the next fix starts afresh. */
    fun reset() {
        if (inBreak) closedS += lastInS - anchorS
        anchorLat = Double.NaN; anchorLon = Double.NaN; lastLat = Double.NaN; lastLon = Double.NaN
        recent.clear()
    }

    private fun anchor(s: Double, lat: Double, lon: Double) {
        anchorLat = lat; anchorLon = lon; anchorS = s; lastInS = s
        recent.clear(); recent.addLast(doubleArrayOf(s, lat, lon))
    }
    private fun remember(s: Double, lat: Double, lon: Double) { lastLat = lat; lastLon = lon; lastS = s }

    fun toJson(): JSONObject = JSONObject()
        .put("aLat", if (anchorLat.isNaN()) JSONObject.NULL else anchorLat).put("aLon", if (anchorLon.isNaN()) JSONObject.NULL else anchorLon)
        .put("aS", anchorS).put("inS", lastInS)
        .put("lLat", if (lastLat.isNaN()) JSONObject.NULL else lastLat).put("lLon", if (lastLon.isNaN()) JSONObject.NULL else lastLon)
        .put("lS", lastS).put("closed", closedS).put("count", count)

    companion object {
        const val RADIUS_M = 25.0
        const val MIN_S = 120.0
        private const val GAP_SPEED_MS = 0.15
        private const val MAX_RECENT = 2000

        fun fromJson(o: JSONObject?): BreakDetector {
            val b = BreakDetector()
            if (o == null) return b
            fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
            b.anchorLat = d("aLat"); b.anchorLon = d("aLon"); b.anchorS = o.optDouble("aS", 0.0); b.lastInS = o.optDouble("inS", 0.0)
            b.lastLat = d("lLat"); b.lastLon = d("lLon"); b.lastS = o.optDouble("lS", 0.0)
            b.closedS = o.optDouble("closed", 0.0).takeIf { !it.isNaN() && it >= 0 } ?: 0.0
            b.count = o.optInt("count", 0)
            if (!b.anchorLat.isNaN() && !b.inBreak) b.recent.addLast(doubleArrayOf(b.anchorS, b.anchorLat, b.anchorLon))
            return b
        }

        /**
         * Breaks in a stored track (fallback when no live summary exists, e.g. an older tour). Each segment is read on
         * its own (a new segment follows a pause or a GPS gap, whose time is unknown), using the points' GPS times.
         */
        fun breaksIn(segments: List<List<TrackPoint>>): Double {
            var total = 0.0
            for (seg in segments) {
                val t0 = seg.firstOrNull { it.timeMs != null }?.timeMs ?: continue
                val b = BreakDetector()
                for (p in seg) { val t = p.timeMs ?: continue; b.onFix((t - t0) / 1000.0, p.lat, p.lon) }
                total += b.breakS()
            }
            return total
        }
    }
}

/** Moving time and breaks of a finished tour, plus "planned vs. needed" on the followed route (F-NAV-06). */
data class TourTimes(
    val movingS: Double,
    val breakS: Double,
    /** Model time (with the factors used during the tour) for the parts of the route that were walked; null = none. */
    val plannedS: Double? = null,
    /** Moving time you needed for those parts. */
    val neededS: Double? = null,
) {
    /** "Moving 2:52 · breaks 0:41" */
    val movingLine: String get() = "Moving ${TimeEstimate.hm(movingS)} · breaks ${TimeEstimate.hm(breakS)}"

    /** "On the route: planned 3:10 – you needed 2:52" (only with ≥ 10 min on the route). */
    val planLine: String?
        get() {
            val p = plannedS ?: return null; val n = neededS ?: return null
            if (p < 600 || n <= 0) return null
            return "On the route: planned ${TimeEstimate.hm(p)} – you needed ${TimeEstimate.hm(n)}"
        }

    fun toJson(): JSONObject = JSONObject().put("moving", movingS).put("breaks", breakS)
        .put("planned", plannedS ?: JSONObject.NULL).put("needed", neededS ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): TourTimes = TourTimes(
            o.optDouble("moving", 0.0), o.optDouble("breaks", 0.0),
            if (o.isNull("planned")) null else o.optDouble("planned"), if (o.isNull("needed")) null else o.optDouble("needed"),
        )

        /** From a tour's elapsed time (pauses excluded) and its break time. */
        fun of(elapsedS: Double, breakS: Double): TourTimes {
            val b = breakS.coerceIn(0.0, elapsedS.coerceAtLeast(0.0))
            return TourTimes(elapsedS.coerceAtLeast(0.0) - b, b)
        }
    }
}

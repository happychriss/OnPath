package com.onpath.app.time

import com.onpath.app.data.Geo
import com.onpath.app.gpx.TrackPoint
import org.json.JSONObject

/**
 * Moving time vs. breaks (docs/plan-time-estimate.md §4.2): a break starts when you stay within [radiusM] for at least
 * [minS] and ends at the first fix further away. Shorter stops (photo, map check, gate) stay moving time, like in
 * signpost times. A GPS gap of at least [minS] in which you hardly moved (hut, indoors) is a break too.
 * Slow walking on a steep path also stays within 25 m for 2 minutes, so a stop starts only when every fix of the last
 * [minS] lies within [ENTER_M] of where you are; once in a break, you may wander within [radiusM].
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
    /** Fixes of the stop candidate (s, lat, lon): the newest ones all within [ENTER_M] of the current position. */
    private val recent = ArrayDeque<DoubleArray>()

    /** Standing still for at least [minS] right now. */
    var inBreak: Boolean = false
        private set

    /** All break time so far, including a break that is still going on (s). */
    fun breakS(): Double = closedS + if (inBreak) lastInS - anchorS else 0.0

    /** One good fix; [alongM] = progress on a followed line when on it (slow but steady progress is no break). */
    fun onFix(activeS: Double, lat: Double, lon: Double, alongM: Double? = null) {
        val al = alongM ?: Double.NaN
        if (lastLat.isNaN()) { recent.clear(); recent.addLast(doubleArrayOf(activeS, lat, lon, al)); remember(activeS, lat, lon); return }
        if (activeS < lastS) { remember(activeS, lat, lon); return } // clock went back (restart): ignore once
        val gap = activeS - lastS
        val slowGap = gap >= minS && Geo.distance(lastLat, lastLon, lat, lon) / gap < GAP_SPEED_MS
        if (inBreak) {
            if (Geo.distance(anchorLat, anchorLon, lat, lon) <= radiusM) lastInS = activeS
            else {
                closedS += lastInS - anchorS
                inBreak = false
                if (slowGap) { closedS += gap; count++ } // indoors, then out of the door
                recent.clear(); recent.addLast(doubleArrayOf(activeS, lat, lon, al))
            }
        } else {
            // the stop so far: the newest fixes that all lie close to where you are now
            recent.addLast(doubleArrayOf(activeS, lat, lon, al))
            var k = recent.size - 1
            while (k > 0 && Geo.distance(recent[k - 1][1], recent[k - 1][2], lat, lon) <= ENTER_M) k--
            repeat(k) { recent.removeFirst() }
            // a long GPS gap without real movement (hut, indoors) that the stop above does not cover: a break as well
            if (recent.size == 1 && slowGap) { closedS += gap; count++ }
            val t0 = recent.first()[0]
            // very steep, very slow, but on the way: progress on the line during the last [minS]
            val a0 = (recent.firstOrNull { it[0] >= activeS - minS } ?: recent.first())[3]
            val progressing = !a0.isNaN() && !al.isNaN() && al - a0 >= PROGRESS_M
            if (activeS - t0 >= minS && !progressing) {
                inBreak = true; count++
                anchorLat = lat; anchorLon = lon; anchorS = t0; lastInS = activeS
                recent.clear()
            }
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
        remember(activeS, lat, lon)
    }

    /** Pause or a jump in the clock: an ongoing break ends here, the next fix starts afresh. */
    fun reset() {
        if (inBreak) closedS += lastInS - anchorS
        inBreak = false
        anchorLat = Double.NaN; anchorLon = Double.NaN; lastLat = Double.NaN; lastLon = Double.NaN
        recent.clear()
    }

    private fun remember(s: Double, lat: Double, lon: Double) { lastLat = lat; lastLon = lon; lastS = s }

    fun toJson(): JSONObject = JSONObject()
        .put("aLat", if (anchorLat.isNaN()) JSONObject.NULL else anchorLat).put("aLon", if (anchorLon.isNaN()) JSONObject.NULL else anchorLon)
        .put("aS", anchorS).put("inS", lastInS)
        .put("lLat", if (lastLat.isNaN()) JSONObject.NULL else lastLat).put("lLon", if (lastLon.isNaN()) JSONObject.NULL else lastLon)
        .put("lS", lastS).put("closed", closedS).put("count", count).put("on", inBreak)

    companion object {
        const val RADIUS_M = 25.0
        const val MIN_S = 120.0
        /** A stop starts only when the last [MIN_S] of fixes are this close to the current one (not walking slowly). */
        const val ENTER_M = 15.0
        /** On a followed line: this much progress during the stop candidate means still walking. */
        const val PROGRESS_M = 10.0
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
            b.inBreak = o.optBoolean("on", false) && !b.anchorLat.isNaN()
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

package com.onpath.app.time

import com.onpath.app.data.AppSettings
import com.onpath.app.data.Geo
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import java.util.Calendar
import kotlin.math.roundToLong

/**
 * Glue between the pure model and the app: which factors apply to a plan, how times are rounded and written.
 * One place, so the plan sheet, the route list and Navigate show the same number (docs/plan-time-estimate.md §5).
 */
object TimeEstimate {
    /** Break allowance on top of the moving time for the arrival clock (owner: fixed 10 %). */
    const val BREAK_ALLOWANCE = 0.10

    fun isBike(a: Activity) = a != Activity.HIKE

    fun pace(a: Activity, s: AppSettings): Pace = if (isBike(a)) s.bikePace else s.hikePace

    /**
     * Factors for planning and for the start of every tour: the pace setting alone (owner: learning per
     * tour only, nothing is carried from one tour to the next; e-bikes no faster than ~25 km/h on the flat, [Pace.factors]).
     */
    fun planFactors(a: Activity, s: AppSettings): Factors = pace(a, s).factors(a)

    /** Moving time of a whole line with [f], rounded like everywhere else ([round]). */
    fun planSeconds(points: List<GeoPoint>, a: Activity, f: Factors): Double = round(RouteTimes.of(points, a).total(f))

    /**
     * Time of an imported track: the moving time in the file's own timestamps ([fileSeconds]), else the model's for its
     * segments – the same number a planned route on the same points shows (one time model, no second speed table).
     * Neither counts the gap between segments. Without way attributes = the activity's usual way.
     */
    fun importedSeconds(segments: List<List<TrackPoint>>, a: Activity, f: Factors): Double =
        fileSeconds(segments) ?: round(segments.sumOf { s -> RouteTimes.of(s.map { it.toGeo() }, a).total(f) })

    /** Distance, ascent, descent and time of an imported track – the numbers of the track panel and My routes. */
    data class TrackStats(val distanceM: Double, val ascentM: Double, val descentM: Double, val durationS: Double)

    /**
     * [TrackStats] of an imported track (review routing L8: computed by hand in the app's import, the sync's import and
     * the web's import) – one function for all three: distance per segment, ascent with the GPS filter, [importedSeconds].
     */
    fun trackStats(segments: List<List<TrackPoint>>, a: Activity, f: Factors): TrackStats {
        val dist = segments.sumOf { s -> Geo.length(s.map { it.toGeo() }) }
        var up = 0.0; var down = 0.0
        segments.forEach { s -> val (u, d) = Geo.climb(s.map { it.ele }); up += u; down += d }
        return TrackStats(dist, up, down, importedSeconds(segments, a, f))
    }

    /**
     * Moving time in the file's timestamps (review routing M8 – was first → last: a 2-day hut tour read "31 h", a café
     * stop counted): per segment, without stops – staying within [STOP_RADIUS_M] for at least [STOP_MIN_S] (as
     * BreakDetector does on a tour), a gap in the points that long included – and never across a night between segments.
     * Null = no usable timestamps (time is estimated).
     */
    fun fileSeconds(segments: List<List<TrackPoint>>): Double? {
        var total = 0.0
        var timed = false
        for (seg in segments) {
            val pts = seg.filter { it.timeMs != null }
            if (pts.size < 2) continue
            var i = 0
            while (i < pts.size - 1) {
                // the longest run from i that stays within the radius: a stop when it lasts long enough
                var j = i
                while (j + 1 < pts.size && Geo.distance(pts[i].lat, pts[i].lon, pts[j + 1].lat, pts[j + 1].lon) <= STOP_RADIUS_M) j++
                val runS = (pts[j].timeMs!! - pts[i].timeMs!!) / 1000.0
                if (j > i && runS >= STOP_MIN_S) { i = j; continue } // a stop: not moving time
                val dt = (pts[i + 1].timeMs!! - pts[i].timeMs!!) / 1000.0
                if (dt > 0) { total += dt; timed = true }
                i++
            }
        }
        return total.takeIf { timed && it > 0 }
    }

    /** A stop in a track's timestamps: within this radius … */
    const val STOP_RADIUS_M = 25.0
    /** … for at least this long (as on a tour, BreakDetector.MIN_S). */
    const val STOP_MIN_S = 120.0

    /** Display rounding (docs §4.2): under 20 min to the minute, above to 5 minutes. */
    fun round(s: Double): Double = when {
        s.isNaN() || s <= 0 -> 0.0
        s < 20 * 60 -> (s / 60).roundToLong() * 60.0
        else -> (s / 300).roundToLong() * 300.0
    }

    /** Arrival clock rounded to 5 minutes, as "16:40" (local time). */
    fun clock(epochMs: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = (epochMs / 300_000.0).roundToLong() * 300_000 }
        return "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** "1:12" (hours:minutes) for moving time / breaks. */
    fun hm(s: Double): String {
        val m = (s.coerceAtLeast(0.0) / 60).toLong()
        return "%d:%02d".format(m / 60, m % 60)
    }

    /** Arrival = now + moving time to go + break allowance. */
    fun arrivalMs(nowMs: Long, movingToGoS: Double, allowance: Double = BREAK_ALLOWANCE): Long =
        nowMs + (movingToGoS * (1 + allowance) * 1000).roundToLong()
}

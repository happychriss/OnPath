package com.onpath.app.time

import com.onpath.app.data.AppSettings
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
     * Time of an imported track: the file's own (first → last timestamp, [fileSeconds]), else the model's for its
     * segments – the same number a planned route on the same points shows (one time model, no second speed table).
     * The gap between segments is not counted. Without way attributes = the activity's usual way.
     */
    fun importedSeconds(segments: List<List<TrackPoint>>, a: Activity, f: Factors): Double =
        fileSeconds(segments) ?: round(segments.sumOf { s -> RouteTimes.of(s.map { it.toGeo() }, a).total(f) })

    /** Seconds the file's timestamps span (first → last); null = fewer than two, or not increasing (time is estimated). */
    fun fileSeconds(segments: List<List<TrackPoint>>): Double? {
        val times = segments.flatten().mapNotNull { it.timeMs }
        return if (times.size > 1 && times.last() > times.first()) (times.last() - times.first()) / 1000.0 else null
    }

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

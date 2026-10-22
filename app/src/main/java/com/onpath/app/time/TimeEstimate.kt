package com.onpath.app.time

import com.onpath.app.data.AppSettings
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import java.util.Calendar
import kotlin.math.roundToLong

/**
 * Glue between the pure model and the app: which factors apply to a plan, how times are rounded and written.
 * One place, so the plan sheet, the route list and Navigate show the same number (docs/plan-time-estimate.md §5).
 */
object TimeEstimate {
    /** Break allowance on top of the moving time for the arrival clock (owner decision Q2: fixed 10 %). */
    const val BREAK_ALLOWANCE = 0.10

    fun isBike(a: Activity) = a != Activity.HIKE

    fun pace(a: Activity, s: AppSettings): Pace = if (isBike(a)) s.bikePace else s.hikePace

    /**
     * Factors for planning and for the start of every tour: the pace setting alone (owner 2026-10-22: learning per
     * tour only, nothing is carried from one tour to the next; e-bikes no faster than ~25 km/h on the flat, [Pace.factors]).
     */
    fun planFactors(a: Activity, s: AppSettings): Factors = pace(a, s).factors(a)

    /** Moving time of a whole line with [f], rounded like everywhere else ([round]). */
    fun planSeconds(points: List<GeoPoint>, a: Activity, f: Factors): Double = round(RouteTimes.of(points, a).total(f))

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

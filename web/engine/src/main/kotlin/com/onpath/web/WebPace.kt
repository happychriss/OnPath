package com.onpath.web

import com.onpath.app.data.AppSettings
import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Planner
import com.onpath.app.time.RouteTimes
import com.onpath.app.routing.Activity
import com.onpath.app.time.Pace
import com.onpath.app.time.TimeEstimate
import com.onpath.strings.*

/**
 * The pace setting on the web (owner 2026-10-29: "like the app's Settings → Pace", in the activity drop-down). The
 * app keeps one level for hiking and one for cycling ([AppSettings.hikePace] / [AppSettings.bikePace], Prefs keys
 * "hikePaceLevel" / "bikePaceLevel"); every plan time, the long-route estimate aside (the app: Average), and an
 * imported track's model time use it through the app's own [TimeEstimate.planFactors]. The page remembers the two
 * levels in localStorage and hands them over with [set] after the engine loaded.
 */
object WebPace {
    /** Only the two pace fields are used; the rest stays the app's defaults. */
    var settings = AppSettings()
        private set

    /** [hike] / [bike] = [Pace] names; unknown → the app's rule ([Pace.of]: "NORMAL" → Average, else the default). */
    fun set(hike: String, bike: String): String {
        settings = settings.copy(hikePace = Pace.of(hike), bikePace = Pace.of(bike))
        return "{\"hike\":" + Engine.str(settings.hikePace.name) + ",\"bike\":" + Engine.str(settings.bikePace.name) + "}"
    }

    fun factors(a: Activity) = TimeEstimate.planFactors(a, settings)

    /**
     * The drop-down's pace section for [activity]'s group – the app's PaceSection (ui/PaceSettings.kt): title "Your
     * pace", the group ("Hiking" / "Cycling · Trekking bike"), the five levels with what each means for this activity,
     * the chosen one, and the intro line. JSON.
     */
    fun choices(activity: String): String {
        val a = Activity.valueOf(activity)
        val bike = TimeEstimate.isBike(a)
        val group = if (bike) Txt.s(Res.string.settings_pace_cycling, navName(a)) else Txt.s(Res.string.settings_pace_hiking)
        val on = TimeEstimate.pace(a, settings)
        return buildString {
            append("{\"title\":").append(Engine.str(Txt.s(Res.string.settings_page_pace)))
            append(",\"group\":").append(Engine.str(if (bike) "bike" else "hike"))
            append(",\"groupLabel\":").append(Engine.str(group))
            append(",\"intro\":").append(Engine.str(Txt.s(Res.string.settings_pace_intro)))
            append(",\"selected\":").append(Engine.str(on.name))
            append(",\"levels\":").append(Pace.entries.joinToString(",", "[", "]") {
                "{\"key\":" + Engine.str(it.name) + ",\"label\":" + Engine.str(it.label) + ",\"meaning\":" + Engine.str(it.meaning(a)) + "}"
            })
            append('}')
        }
    }

    /**
     * The last plan's time again with the current pace (no new routing): what the sheet shows after a pace change.
     * Same call as [Engine.plan] ([TimeEstimate.planSeconds]), so the number is the one a new plan would give.
     * "null" when the last plan was made for other points, activity or level (the page then plans again).
     */
    fun planTime(points: String, activity: String, sac: Int): String {
        val a = Activity.valueOf(activity)
        val p = Engine.lastPlanPoints(Engine.parsePoints(points), a, sac) ?: return "null"
        val s = if (p.size >= 2) TimeEstimate.planSeconds(p, a, factors(a)) else 0.0
        return "{\"timeS\":" + Engine.num(s) + ",\"time\":" + Engine.str(Format.duration(s)) + "}"
    }

    /** [RouteTimes] of the last line asked for in [rangeStats] (dragging asks many times for the same line). */
    private var rangeTimes: Triple<List<GeoPoint>, Activity, RouteTimes>? = null

    /**
     * A stretch of the last plan between [fromM] and [toM] metres along its line (the big profile's marked range, owner
     * 2026-11-06): length, time and climb with the plan's own rules – the time from the same [RouteTimes] the plan's
     * total comes from ([TimeEstimate.planSeconds]: pace factors, [TimeEstimate.round]), climb with the plan's
     * hysteresis ([Planner.climb]: BRouter's 10 m filter). Distances along the line as [Geo.cumulative] counts them
     * (the profile's own). "null" when the last plan was made for other points, activity or level.
     */
    fun rangeStats(points: String, activity: String, sac: Int, fromM: Double, toM: Double): String {
        val a = Activity.valueOf(activity)
        val p = Engine.lastPlanPoints(Engine.parsePoints(points), a, sac) ?: return "null"
        if (p.size < 2) return "null"
        val cum = Geo.cumulative(p)
        val total = cum.last()
        val from = minOf(fromM, toM).coerceIn(0.0, total)
        val to = maxOf(fromM, toM).coerceIn(0.0, total)
        val rt = rangeTimes?.takeIf { it.first === p && it.second == a }?.third
            ?: RouteTimes.of(p, a).also { rangeTimes = Triple(p, a, it) }
        val f = factors(a)
        val g = rt.between(from, to)
        val s = TimeEstimate.round(g.indices.sumOf { f[it] * g[it] })
        // the elevations inside the range, its two ends interpolated on their segments
        val eles = ArrayList<Double?>()
        eles.add(eleAt(p, cum, from))
        for (i in p.indices) if (cum[i] > from && cum[i] < to) eles.add(p[i].ele)
        eles.add(eleAt(p, cum, to))
        val (up, down) = Geo.routeClimb(eles, Planner.ELE_FILTER_M)
        val len = to - from
        return "{\"lengthM\":" + Engine.num(len) + ",\"timeS\":" + Engine.num(s) + ",\"upM\":" + Engine.num(up) +
            ",\"downM\":" + Engine.num(down) + ",\"text\":" +
            Engine.str(Format.distance(len) + " · " + Format.duration(s) + " · ↑ " + Format.meters(up) + " ↓ " + Format.meters(down)) + "}"
    }

    /** The elevation [m] metres along [p] ([cum] = its cumulative distances), between its points; null = none known. */
    private fun eleAt(p: List<GeoPoint>, cum: DoubleArray, m: Double): Double? {
        var hi = cum.indexOfFirst { it >= m }
        if (hi <= 0) return p[0].ele ?: p.firstNotNullOfOrNull { it.ele }
        val lo = hi - 1
        val a = p[lo].ele; val b = p[hi].ele
        if (a == null || b == null) return a ?: b
        val span = cum[hi] - cum[lo]
        return if (span > 0) a + (b - a) * (m - cum[lo]) / span else a
    }

    /** Mirror of ui/Theme.kt `Activity.navName` (web/parity.json): the short name in "Cycling · Trekking bike". */
    internal fun navName(a: Activity): String = Txt.s(when (a) {
        Activity.BIKE_TOURING -> Res.string.activity_bike_touring_button
        Activity.BIKE_TREKKING -> Res.string.activity_bike_trekking_button
        Activity.EBIKE -> Res.string.activity_ebike_button
        Activity.MTB -> Res.string.activity_mtb_button
        Activity.EMTB -> Res.string.activity_emtb_button
        Activity.HIKE -> Res.string.activity_hike_button
    })
}

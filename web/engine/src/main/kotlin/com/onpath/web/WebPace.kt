package com.onpath.web

import com.onpath.app.data.AppSettings
import com.onpath.app.data.Format
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

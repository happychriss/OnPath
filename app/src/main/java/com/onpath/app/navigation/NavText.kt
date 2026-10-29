package com.onpath.app.navigation

import com.onpath.strings.*

import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import kotlin.math.abs
import kotlin.math.roundToInt

/** Plain-language texts for the off-route question / banner and its notification (English + German). Pure. */
object NavText {
    val TAKE: String get() = Txt.s(Res.string.nav_take_new_route)
    val KEEP: String get() = Txt.s(Res.string.nav_keep_original)

    fun title(p: NavPrompt): String = when (p) {
        NavPrompt.Searching -> Txt.s(Res.string.nav_title_searching)
        is NavPrompt.Question -> p.switch?.let { switchTitle(it) }
            ?: Txt.s(if (p.turnRound) Res.string.nav_title_turn_round else Res.string.nav_title_found)
        is NavPrompt.NoWay -> when (p.reason) {
            Rerouter.Reason.NO_DATA -> Txt.s(Res.string.nav_title_no_data)
            Rerouter.Reason.UNAVAILABLE -> Txt.s(Res.string.nav_title_unavailable)
            else -> Txt.s(Res.string.nav_title_no_way)
        }
        NavPrompt.Kept -> Txt.s(Res.string.nav_title_kept)
    }

    /**
     * The state line of the one amber off-route card in Navigate: the title there is just
     * "Off route", the distance + direction under it, this in small text.
     */
    fun state(p: NavPrompt): String = when (p) {
        NavPrompt.Searching -> Txt.s(Res.string.nav_state_searching)
        is NavPrompt.Question -> p.switch?.let { switchTitle(it) }
            ?: Txt.s(if (p.turnRound) Res.string.nav_state_turn_round else Res.string.nav_state_found)
        is NavPrompt.NoWay -> when (p.reason) {
            Rerouter.Reason.NO_DATA -> Txt.s(Res.string.nav_state_no_data)
            Rerouter.Reason.UNAVAILABLE -> Txt.s(Res.string.nav_state_unavailable)
            else -> Txt.s(Res.string.nav_state_no_way)
        }
        NavPrompt.Kept -> Txt.s(Res.string.nav_state_kept)
    }

    /** "New route for Gravel bike" / "New route for Hike T3". */
    fun switchTitle(sw: ActivitySwitch): String = Txt.s(Res.string.nav_switch_title, activityName(sw.activity, sw.sac))

    /** "Hike T3" / "MTB" ([com.onpath.app.data.LastActivity.name]). */
    fun activityName(a: Activity, sac: Int): String = com.onpath.app.data.LastActivity.name(a, sac)

    fun question(q: NavPrompt.Question): String = buildString {
        q.switch?.let { sw ->
            // "Rest of the route 9.3 km (+1.2 km) · 3 h 10 min"
            append(Txt.s(Res.string.nav_rest_of_route, Format.distance(round10(q.legM))))
            val e = round10(abs(q.extraM))
            if (e >= 100) append(" (").append(if (q.extraM > 0) "+" else "−").append(Format.distance(e)).append(")")
            append(" · ").append(Format.duration(sw.timeS))
            if (q.startOffM > FAR_START_M) append(" · ").append(Txt.s(Res.string.nav_nearest_way, Format.distance(round10(q.startOffM))))
            return@buildString
        }
        append(extra(q.extraM))
        if (q.rejoinInM.isNaN()) append(" · ").append(Txt.s(Res.string.nav_new_route_length, Format.distance(round10(q.legM))))   // fresh route (Rerouter.fresh)
        else append(" · ").append(Txt.s(Res.string.nav_rejoins_in, Format.distance(round10(q.rejoinInM))))
        if (q.startOffM > FAR_START_M) append(" · ").append(Txt.s(Res.string.nav_nearest_way, Format.distance(round10(q.startOffM))))
    }

    fun extra(m: Double): String {
        val r = round10(abs(m))
        return when {
            r < 10 -> Txt.s(Res.string.nav_about_as_long)
            m > 0 -> "+${Format.distance(r)}"
            else -> Txt.s(Res.string.nav_shorter, Format.distance(r))
        }
    }

    /**
     * "120 m north-east (direct)" – a bearing, not a path: it may cross cliffs or water. Not "straight line": the plan
     * uses that word for unrouted legs.
     */
    fun toRoute(h: RouteHint?): String? =
        h?.let { Txt.s(Res.string.nav_to_route_direct, Format.distance(round10(it.distanceM)), compass(it.bearingDeg)) }

    /**
     * "46.54120 N 10.90320 E · ±8 m · 1234 m" for an emergency call; "· fix 4 min old" when the fix is old.
     * Five decimals ≈ 1 m. Pure.
     */
    fun position(lat: Double, lon: Double, accuracyM: Float?, altitudeM: Double?, ageS: Long): String = buildString {
        append("%.5f %s %.5f %s".format(java.util.Locale.ROOT, abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W"))
        accuracyM?.let { append(" · ±").append(it.roundToInt()).append(" m") }
        altitudeM?.let { append(" · ").append(it.roundToInt()).append(" m") }
        if (ageS >= 60) append(" · ").append(Txt.s(Res.string.nav_position_age, (ageS / 60).toInt()))
    }

    /** "46.54120, 10.90320" – what is copied (pastes into any map app or message). */
    fun positionCopy(lat: Double, lon: Double): String = "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon)

    fun compass(deg: Double): String {
        val names = listOf(Res.string.nav_compass_n, Res.string.nav_compass_ne, Res.string.nav_compass_e, Res.string.nav_compass_se,
            Res.string.nav_compass_s, Res.string.nav_compass_sw, Res.string.nav_compass_w, Res.string.nav_compass_nw)
        return Txt.s(names[(((deg % 360 + 360) % 360) / 45.0).roundToInt() % 8])
    }

    private fun round10(m: Double) = (m / 10).roundToInt() * 10.0

    /** A new route that starts this far away is announced with the distance to the nearest way. */
    const val FAR_START_M = 150.0
}

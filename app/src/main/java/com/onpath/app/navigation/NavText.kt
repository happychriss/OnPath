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
    /**
     * "Off route – re-plan?" with a stop in question (StopSkip, owner 2026-10-30): "Yes" = the new route via the stop
     * ([NavPrompt.Question.via]), "Yes, skip stop 3" ([skipButton]) = the new route without it, Keep original.
     */
    val YES: String get() = Txt.s(Res.string.nav_yes)
    /**
     * The skip answer as a notification action: three actions share one row and Android cuts long labels ("Ja, Stopp 3
     * ausl…"), so the short "Yes, skip it" – the text above says which stop.
     */
    val SKIP_SHORT: String get() = Txt.s(Res.string.nav_notif_skip)

    /**
     * "Café Moser" / "stop 3" – the stop a new route leaves out (numbered like the plan's points). The name only when it
     * has at most [maxName] characters: it goes into a button ([skipButton]), and the line says the same label.
     */
    fun stopLabel(q: NavPrompt.Question, maxName: Int = NAME_MAX): String? =
        q.skipStop?.let { i -> q.skipName?.takeIf { it.length <= maxName } ?: Txt.s(Res.string.nav_stop_n, i + 1) }

    /** "Yes, skip stop 3" / "Ja, Gasthof Hochmuth auslassen" – the card's second answer. */
    fun skipButton(q: NavPrompt.Question, maxName: Int = NAME_MAX): String =
        Txt.s(Res.string.nav_yes_skip_stop, stopLabel(q, maxName) ?: "")

    /** "Guide me" / "Just record" – the far-start question's answers (owner 2026-10-31). */
    val LEAD: String get() = Txt.s(Res.string.nav_far_lead)
    val JUST_RECORD: String get() = Txt.s(Res.string.nav_far_record)

    fun title(p: NavPrompt): String = when (p) {
        NavPrompt.Searching -> Txt.s(Res.string.nav_title_searching)
        is NavPrompt.Question -> p.switch?.let { switchTitle(it) }
            ?: p.via?.let { Txt.s(Res.string.nav_title_replan) }
            // "You're 120 m off the route" (owner 2026-10-31)
            ?: p.offM?.let { Txt.s(if (p.turnRound) Res.string.nav_title_off_by_turn_round else Res.string.nav_title_off_by,
                Format.distance(round10(it))) }
            ?: Txt.s(if (p.turnRound) Res.string.nav_title_turn_round else Res.string.nav_title_found)
        // "Route starts 31 km away"
        is NavPrompt.FarStart -> Txt.s(Res.string.nav_far_title, farDistance(p.distanceM))
        is NavPrompt.LeadFailed -> Txt.s(Res.string.nav_lead_failed)
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
            ?: p.via?.let { Txt.s(Res.string.nav_state_replan) }
            ?: Txt.s(if (p.turnRound) Res.string.nav_state_turn_round else Res.string.nav_state_found)
        is NavPrompt.NoWay -> when (p.reason) {
            Rerouter.Reason.NO_DATA -> Txt.s(Res.string.nav_state_no_data)
            Rerouter.Reason.UNAVAILABLE -> Txt.s(Res.string.nav_state_unavailable)
            else -> Txt.s(Res.string.nav_state_no_way)
        }
        NavPrompt.Kept -> Txt.s(Res.string.nav_state_kept)
        // "south-west (direct) · recording" – a bearing, not a path
        is NavPrompt.FarStart -> if (p.searching) Txt.s(Res.string.nav_far_searching)
            else Txt.s(Res.string.nav_far_detail, compass(p.bearingDeg))
        is NavPrompt.LeadFailed -> Txt.s(Res.string.nav_lead_failed)
    }

    /** The far start's distance: whole kilometres from 10 km ("31 km"), else as everywhere ("4.2 km", "350 m"). */
    fun farDistance(m: Double): String =
        if (m >= 10_000) "${(m / 1000).roundToInt()} km" else Format.distance(round10(m))

    /** "New route for Gravel bike" / "New route for Hike T3". */
    fun switchTitle(sw: ActivitySwitch): String = Txt.s(Res.string.nav_switch_title, activityName(sw.activity, sw.sac))

    /** "Hike T3" / "MTB" ([com.onpath.app.data.LastActivity.name]). */
    fun activityName(a: Activity, sac: Int): String = com.onpath.app.data.LastActivity.name(a, sac)

    /** The question's line; [maxName] as for [stopLabel] (the card passes a smaller one at a large font). */
    fun question(q: NavPrompt.Question, maxName: Int = NAME_MAX): String = buildString {
        q.switch?.let { sw ->
            // "Rest of the route 9.3 km (+1.2 km) · 3 h 10 min"
            append(Txt.s(Res.string.nav_rest_of_route, Format.distance(round10(q.legM))))
            val e = round10(abs(q.extraM))
            if (e >= 100) append(" (").append(if (q.extraM > 0) "+" else "−").append(Format.distance(e)).append(")")
            append(" · ").append(Format.duration(sw.timeS))
            if (q.startOffM > FAR_START_M) append(" · ").append(Txt.s(Res.string.nav_nearest_way, Format.distance(round10(q.startOffM))))
            return@buildString
        }
        val via = q.via
        // + movedM: the rider kept moving since the line was computed (Navigator.live)
        val newM by lazy { Format.distance(round10((q.legM + q.movedM).coerceAtLeast(0.0))) }
        val rejoinM by lazy { Format.distance(round10((q.rejoinInM + q.movedM).coerceAtLeast(0.0))) }
        if (via != null) {
            // "Re-plan?": what going via the stop costs on top of leaving it out, then the new route without it
            // (both lines move with the rider by the same amount, so the difference needs no movedM)
            append(Txt.s(Res.string.nav_via_stop, stopLabel(q, maxName) ?: "", extra(via.extraM - q.extraM).replaceFirstChar { it.lowercase() }))
            if (q.rejoinInM.isNaN()) append(" · ").append(Txt.s(Res.string.nav_new_route_length, newM))   // fresh route (Rerouter.fresh)
            else append(" · ").append(Txt.s(Res.string.nav_rejoins_in, rejoinM))
        } else if (q.rejoinInM.isNaN()) {
            // fresh route (Rerouter.fresh): "New route to the end: 3.4 km (350 m longer)" (owner 2026-10-31)
            append(Txt.s(Res.string.nav_line_fresh, newM, longer(q.extraM + q.movedM)))
        } else {
            // way back: "Back on the route after 600 m · 350 m longer"
            append(Txt.s(Res.string.nav_line_rejoin, rejoinM, longer(q.extraM + q.movedM)))
        }
        if (q.startOffM > FAR_START_M) append(" · ").append(Txt.s(Res.string.nav_nearest_way, Format.distance(round10(q.startOffM))))
        // left out without asking (an ordinary route point far aside): said, so the plan's marker is not a surprise
        if (via == null) stopLabel(q, Int.MAX_VALUE)?.let { append(" · ").append(Txt.s(Res.string.nav_without_stop, it)) }
    }

    /**
     * "350 m longer" / "350 m shorter" / "about as long" – the new remaining distance minus what was left of the plan
     * ([NavPrompt.Question.extraM]).
     */
    fun longer(m: Double): String {
        val r = round10(abs(m))
        return when {
            r < 10 -> Txt.s(Res.string.nav_about_as_long).replaceFirstChar { it.lowercase() }
            m > 0 -> Txt.s(Res.string.nav_longer, Format.distance(r))
            else -> Txt.s(Res.string.nav_shorter, Format.distance(r))
        }
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

    /** A stop's name longer than this is said as "stop 3" (the skip button wraps to at most two lines). */
    const val NAME_MAX = 16
    /** The same at a large phone font (the buttons are stacked, ~17 characters a line at 2×). */
    const val NAME_MAX_LARGE = 10

    /** A new route that starts this far away is announced with the distance to the nearest way. */
    const val FAR_START_M = 150.0
}

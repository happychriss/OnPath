package com.onpath.app.ui

import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.strings.*

/**
 * The next plan stop on the turn card (owner 2026-11-03, the comparison page's card 1 – Wikiloc's "Zeller Horn in 50 m"):
 * on a long stretch the card says "1,2 km · Stopp 2 · Leiteralm" when the stop comes before the turn it would show.
 * A turn before the stop keeps the turn on top. Pure, unit-tested (`NextStopTest`).
 */
data class StopAhead(
    /** The number the map shows ("Stopp 7" after a plan edit, [NavSession.numberOf]). */
    val number: Int,
    val name: String?,
    /** The plan's last stop. */
    val goal: Boolean,
    /** From here along the shown line. */
    val distanceM: Double,
) {
    /**
     * The card's line (owner 2026-11-03: the circle instead of the word "Stopp"): "② Leiteralm" / "②" – the map's
     * numbered circle ([StopText] token); the destination keeps its word: "Ziel · Hütte" / "Ziel" (the big sign on the
     * left is the red flag). A long name ends in "…".
     */
    val label: String get() = when {
        goal && name != null -> Txt.s(Res.string.nav_card_goal_named, name)
        goal -> Txt.s(Res.string.nav_card_goal)
        name != null -> StopTokens.stop(number) + " " + name
        else -> StopTokens.stop(number)
    }
}

object NextStop {
    /** A stop this little ahead is "here" – the voice says it is reached; the card shows what comes after. */
    const val PASSING_M = 5.0

    /**
     * Where each stop of [s] lies on the line navigated now ([NavSession.active]); null = not on it. Without a re-route
     * the plan's own distance; after one, the untouched rest of the original is shifted to the new line (stops before
     * the rejoin are behind or left out).
     */
    fun alongs(s: NavSession): List<Double?> = s.stops.map { st ->
        when {
            s.current == null -> st.alongM
            st.alongM >= s.resumeAlongM -> s.spliceAtM + (st.alongM - s.resumeAlongM)
            else -> null
        }
    }

    /**
     * The next stop ahead of [alongM] (metres on the navigated line), or null: a followed track has none; reached and
     * left-out stops never come again; the plan's start (stop 0 when the tour follows the whole plan) is not a stop
     * ahead – the far-start box is its place.
     */
    fun ahead(s: NavSession, alongM: Double): StopAhead? {
        if (s.kind != NavKind.PLAN || s.stops.isEmpty()) return null
        val at = alongs(s)
        val i = s.stops.indices
            .filter { it !in s.visited && it !in s.skipped && (it > 0 || s.firstNumber > 1) }
            .filter { (at[it] ?: return@filter false) > alongM + PASSING_M }
            .minByOrNull { at[it]!! } ?: return null
        return StopAhead(s.numberOf(i), s.stops[i].name?.takeIf { it.isNotBlank() }, i == s.stops.lastIndex,
            at[i]!! - alongM)
    }

    /**
     * Up to [max] stops ahead (owner 2026-11-05, like Komoot – under the profile in Navigate's card): the same rules as
     * [ahead] (reached / left-out stops and the start never), nearest first; the goal comes last by itself.
     */
    fun aheadList(s: NavSession, alongM: Double, max: Int = 3): List<StopAhead> {
        if (s.kind != NavKind.PLAN || s.stops.isEmpty()) return emptyList()
        val at = alongs(s)
        return s.stops.indices
            .filter { it !in s.visited && it !in s.skipped && (it > 0 || s.firstNumber > 1) }
            .filter { (at[it] ?: return@filter false) > alongM + PASSING_M }
            .sortedBy { at[it]!! }
            .take(max)
            .map { i -> StopAhead(s.numberOf(i), s.stops[i].name?.takeIf { it.isNotBlank() }, i == s.stops.lastIndex, at[i]!! - alongM) }
    }

    /**
     * Moving seconds from here to a stop in the SAME model as the arrival clock: the card's to-go time ([toGoS], the
     * service's learned one or the plain model) shared out by the plain model's split – here → end ([plainHereS]) vs
     * stop → end ([plainAfterS]). 0 when the plain model knows nothing.
     */
    fun movingS(toGoS: Double, plainHereS: Double, plainAfterS: Double): Double =
        if (plainHereS <= 0.0) 0.0 else (toGoS * (plainHereS - plainAfterS) / plainHereS).coerceIn(0.0, toGoS)

    /** The stop takes the card when it comes before the turn shown ([turnM]; null = no turn). */
    fun shown(turnM: Double?, stop: StopAhead?): Boolean = stop != null && (turnM == null || stop.distanceM < turnM)
}

/** When Navigate's clock times are worked out again: at every full minute (a stop's "an 14:20" never lags the clock). */
object MinuteClock {
    /** Milliseconds from [nowMs] to the next full minute (1 ms … 60 s). */
    fun untilNextMs(nowMs: Long): Long = 60_000L - Math.floorMod(nowMs, 60_000L)
}

/** A stop row under the profile: the stop and its arrival clock ("an 14:20"; null = no time). */
data class StopRow(val stop: StopAhead, val arriveAt: String?) {
    /** The row's name: the stop's own name, else "Ziel" / "Stopp 3" (the sign beside it shows the number too). */
    val title: String get() = stop.name ?: if (stop.goal) Txt.s(Res.string.nav_card_goal) else Txt.s(Res.string.nav_stop_n, stop.number)
}

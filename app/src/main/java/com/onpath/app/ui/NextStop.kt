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

    /** The stop takes the card when it comes before the turn shown ([turnM]; null = no turn). */
    fun shown(turnM: Double?, stop: StopAhead?): Boolean = stop != null && (turnM == null || stop.distanceM < turnM)
}

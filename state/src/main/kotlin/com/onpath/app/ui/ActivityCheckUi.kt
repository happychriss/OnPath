package com.onpath.app.ui

import com.onpath.app.navigation.ActivityFit
import com.onpath.app.routing.Activity

// The activity check sheet's state types and pure rules, cut out verbatim of ActivitySwitchSheet.kt (rebuild phase 2
// piece 16b: the check's slice in :state holds them). Same package, so no screen or test import changes.

/** One row of the activity check sheet. */
sealed interface ActivityRowState {
    /**
     * Not routed yet (owner 2026-11-07: "erst dann, wenn ich wirklich eine Routenart ausgewählt habe"): a tap routes
     * the rest of the route for this activity only, then switches when a route is found.
     */
    data object Idle : ActivityRowState
    /** The rest of the route is being routed for this activity (spinner). */
    data object Checking : ActivityRowState
    data class Ready(val fit: ActivityFit) : ActivityRowState
    /** No route for this activity (no data, nothing found, timeout): shown, not choosable. */
    data class Failed(val message: String) : ActivityRowState
    /** No route is followed (free recording): choosing switches at once. */
    data object Plain : ActivityRowState
}

data class ActivityRow(val activity: Activity, val sac: Int, val state: ActivityRowState) {
    val choosable: Boolean get() = state is ActivityRowState.Ready || state == ActivityRowState.Plain || state == ActivityRowState.Idle
}

/** The check-and-switch sheet (Navigate → tap the activity icon). Stays until a row or "Keep …" is chosen. */
data class ActivityCheckUi(
    val current: Activity,
    val sac: Int,
    val rows: List<ActivityRow>,
    /** E.g. "Waiting for GPS…" or "No route followed …"; null = none. */
    val note: String? = null,
)

/** The check sheet's pure rules (unit-tested, ActivityCheckRulesTest). */
object ActivityCheckRules {
    /**
     * Hike's T chip [level] chosen while a route is followed (K1): the Hike row now stands for that level and is routed
     * for it (Checking – only this row, owner 2026-11-07); hiking at that very level already → no Hike row (nothing to switch to). Hiking at another level →
     * a Hike row for the new level, first in the list.
     */
    fun withHikeLevel(ui: ActivityCheckUi, level: Int): ActivityCheckUi {
        val others = ui.rows.filter { it.activity != Activity.HIKE }
        if (ui.current == Activity.HIKE && level == ui.sac) return ui.copy(rows = others)
        val row = ActivityRow(Activity.HIKE, level, ActivityRowState.Checking)
        val old = ui.rows.indexOfFirst { it.activity == Activity.HIKE }
        val rows = if (old >= 0) ui.rows.toMutableList().also { it[old] = row } else listOf(row) + others
        return ui.copy(rows = rows)
    }

    /**
     * Row [a] was chosen at [level] (owner 2026-11-07: only what is chosen is computed): that row alone is routed
     * (Checking); a row still routing from an earlier tap is cancelled and plain again; found / failed rows stay.
     */
    fun checking(ui: ActivityCheckUi, a: Activity, level: Int): ActivityCheckUi = ui.copy(rows = ui.rows.map {
        when {
            it.activity == a -> it.copy(sac = level, state = ActivityRowState.Checking)
            it.state == ActivityRowState.Checking -> it.copy(state = ActivityRowState.Idle)
            else -> it
        }
    })

    /**
     * The header's grey note goes under "Wechseln zu" when title, gap and note do not fit on one line of [rowW] – the
     * title never breaks inside a word (large German font: "Wechs / eln zu"). Widths in one unit (px or dp).
     */
    fun noteStacked(rowW: Float, titleW: Float, noteW: Float, gap: Float): Boolean = titleW + gap + noteW > rowW

    /** The T level the chips show as chosen: the Hike row's level, else the current level while hiking, else none. */
    fun chipLevel(ui: ActivityCheckUi): Int =
        ui.rows.firstOrNull { it.activity == Activity.HIKE }?.sac ?: if (ui.current == Activity.HIKE) ui.sac else 0
}

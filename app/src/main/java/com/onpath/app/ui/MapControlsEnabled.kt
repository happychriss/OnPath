package com.onpath.app.ui

/**
 * When the map column's eye, whole route, undo and redo can act (owner 2026-11-06: they are always there and only greyed –
 * "funktioniert das Ausgrauen immer zuverlässig?"). Pure – MapScreen feeds it, MapControlsEnabledTest walks every state.
 *
 * - **Eye** (hold = hide the route): a line on the map – the plan's route, a shown track or the followed line.
 * - **Whole route**: the same lines as [WholeRoute] (Plan: shown › planned › followed; Navigate: followed only).
 * - **Undo / Redo** (Plan only): the plan's history has a step and no track sheet is open (Undo there would change the
 *   hidden plan behind the track).
 * Not in the Points step ([pointsOpen]): the column has no room there and steps back anyway.
 * While a route is calculated the old line stays ([planLine] true) – nothing flickers grey.
 */
internal object MapControlsEnabled {
    data class In(
        val mode: AppMode,
        val pointsOpen: Boolean = false,
        /** The plan's route has a line (≥ 2 points) – also the old one while a new one is calculated. */
        val planLine: Boolean = false,
        /** A track / tour from My routes is shown (its sheet is open) and has a line. */
        val shownLine: Boolean = false,
        /** A track sheet is open at all (with or without a line). */
        val shownOpen: Boolean = shownLine,
        /** The tour follows a line (plan, track, or the way to the start). */
        val followLine: Boolean = false,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
    )

    fun eye(s: In): Boolean = !s.pointsOpen && (s.planLine || s.shownLine || s.followLine)
    fun wholeRoute(s: In): Boolean = WholeRoute.shows(s.mode, s.pointsOpen, s.planLine, s.shownLine, s.followLine)
    fun undo(s: In): Boolean = s.mode == AppMode.PLAN && s.canUndo && !s.shownOpen
    fun redo(s: In): Boolean = s.mode == AppMode.PLAN && s.canRedo && !s.shownOpen
}

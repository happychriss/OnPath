package com.onpath.app.domain.plan

import com.onpath.app.data.Tour

/**
 * The plan on the map as My routes → Planned shows it (owner 2026-11-06 23:00: "da könnte schon unter Zuletzt ‚gerade in
 * Bearbeitung' stehen, dann weiß ich, dass immer gespeichert wird"). [name] = the saved route's / Zuletzt plan's name it
 * still belongs to, null = a new plan; [linkedId] = that tour's id (-1 = none); [key] = its points (lat, lon) – a plan
 * under Zuletzt with the same points is the same plan; [unsaved] = it differs from its last save (RouteRules.isUnsaved).
 */
data class LivePlan(
    val name: String?,
    val points: Int,
    val distanceM: Double,
    val ascentM: Double,
    val durationS: Double,
    val linkedId: Long,
    val key: List<Pair<Double, Double>>,
    val unsaved: Boolean,
) {
    /** Speichern needs a route: two points at least (like ☰ Plan speichern). */
    val canSave: Boolean get() = points >= 2
}

/**
 * The one row under "Zuletzt" in My routes → Planned (owner 2026-11-12: exactly one – the plan worked on last, saved or
 * not; [LivePlanRows.of]). [live] = it is the plan on the map (its own row, tag "In Bearbeitung"; a tap goes back to the
 * map); [tourId] = the draft or saved route it is (-1 = a new plan on the map); [saved] = that tour is a saved route as
 * it is – the small "Gespeichert" tag, no Speichern; it stays under "Gespeichert" too. [NONE] = no Zuletzt section.
 */
data class RecentRow(val live: Boolean, val tourId: Long, val saved: Boolean) {
    val shown: Boolean get() = live || tourId >= 0

    companion object {
        val NONE = RecentRow(live = false, tourId = -1L, saved = false)
    }
}

/** Pure, unit-tested (`LivePlanRowsTest`). */
object LivePlanRows {
    /** The live row's id in the list (no tour in the database has it). */
    const val LIVE_ID = -2L

    /**
     * [drafts] = the plans under Zuletzt (newest first), [saved] = the saved planned routes (both after the tab filter),
     * [recentId] = the tour that took the Zuletzt place last (RoutesState.recentId; -1 = none known). The plan on the map
     * is the row whenever there is one (a point at least; during a tour the same: the plan on the map is the plan
     * followed) – a saved route as it is with the "Gespeichert" mark, a changed one or a draft with Speichern. An empty
     * map: the tour [recentId] names, if still there – unless a draft outside the bin is newer than it (the app died
     * between the draft's insert and its taking the place; a restore): a draft never hides, the newest one is the row;
     * no place known (older installs, a deleted saved route) → the newest draft; else nothing.
     */
    fun of(live: LivePlan?, drafts: List<Tour>, saved: List<Tour>, recentId: Long = -1L): RecentRow {
        if (live != null && live.points > 0) {
            val linkedSaved = saved.firstOrNull { it.id == live.linkedId && !it.draft }
            val draft = drafts.firstOrNull { it.id == live.linkedId || (live.key.isNotEmpty() && keyOf(it) == live.key) }
            return RecentRow(live = true, tourId = linkedSaved?.id ?: draft?.id ?: -1L, saved = linkedSaved != null && !live.unsaved)
        }
        val pointed = (drafts + saved).firstOrNull { it.id == recentId }
        val newest = drafts.maxByOrNull { it.createdMs }
        val t = when {
            pointed == null -> newest
            newest == null || newest.id == pointed.id -> pointed
            !pointed.draft || newest.createdMs > pointed.createdMs -> newest
            else -> pointed
        } ?: return RecentRow.NONE
        return RecentRow(live = false, tourId = t.id, saved = !t.draft)
    }

    private fun keyOf(t: Tour) = t.waypoints.map { it.lat to it.lon }
}

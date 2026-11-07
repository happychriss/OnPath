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
 * Where the plan on the map goes in My routes → Planned ([LivePlanRows.of]): [onTop] = its own row first under
 * "Zuletzt" with the tag "In Bearbeitung"; [markedSavedId] = it IS that saved route (not a draft): the route's row under
 * "Gespeichert" carries the tag instead (no second row); [hiddenDraftIds] = rows under Zuletzt that are this very plan
 * (loaded from Zuletzt, or the same points kept there before) – never shown twice.
 */
data class LiveRows(val onTop: Boolean, val markedSavedId: Long, val hiddenDraftIds: Set<Long>) {
    companion object {
        val NONE = LiveRows(onTop = false, markedSavedId = -1L, hiddenDraftIds = emptySet())
    }
}

/** Pure, unit-tested (`LivePlanRowsTest`). */
object LivePlanRows {
    /** The live row's id in the list (no tour in the database has it). */
    const val LIVE_ID = -2L

    /**
     * [drafts] = the plans under Zuletzt, [saved] = the saved planned routes (both after the tab filter and the search).
     * An empty map (no point) = no entry. During a tour the same: the plan on the map is the plan followed.
     */
    fun of(live: LivePlan?, drafts: List<Tour>, saved: List<Tour>): LiveRows {
        if (live == null || live.points == 0) return LiveRows.NONE
        val same = drafts.filter { d -> d.id == live.linkedId || (live.key.isNotEmpty() && keyOf(d) == live.key) }
            .map { it.id }.toSet()
        if (live.linkedId >= 0 && saved.any { it.id == live.linkedId && !it.draft })
            return LiveRows(onTop = false, markedSavedId = live.linkedId, hiddenDraftIds = same)
        return LiveRows(onTop = true, markedSavedId = -1L, hiddenDraftIds = same)
    }

    private fun keyOf(t: Tour) = t.waypoints.map { it.lat to it.lon }
}

package com.onpath.app.ui.routes

import com.onpath.app.data.TourKind

/**
 * Which actions a row in My routes offers and where (owner 2026-11-07 09:40, after the clean-up page): every action lives
 * in exactly ONE place.
 * - Zuletzt (drafts): Speichern and Löschen right on the row (fast clean-up); its ⋮ holds only what is left (Umbenennen,
 *   GPX exportieren) – never Speichern or Löschen a second time.
 * - The plan on the map ("In Bearbeitung", on top or as the tag on its saved route): as before – a tap goes back to the
 *   plan, Speichern on the row when it can be saved; no ⋮ on the top row (it is no stored route).
 * - Gespeichert / Gemacht: the clean list – tile + name + facts + ›, no buttons on the row, every action in ⋮.
 * A tap on the row (or on its preview tile, marked ⤢) opens the large overview – except the plan on the map.
 * Edit or follow (owner 2026-11-07): a planned route offers "Planen" (blue, load it to edit) and "Los geht's" (green, load
 * it and start the tour – [RoutesGo]) – the plan sheet's words and colours: in a saved row's ⋮ both on top; under
 * Zuletzt "Planen" on the row before Speichern · Löschen, "Los geht's" in its ⋮. Not during a tour (a tap / the overview's
 * "Folgen" follows at once) and not for the plan on the map (a tap goes back to it).
 * A recorded tour that followed a plan (owner 2026-11-07, [com.onpath.app.data.TourPlan]) offers the same "Planen" /
 * "Los geht's" on top of its ⋮ – they load the plan it followed (its last version); "Als Plan übernehmen" (a plan from
 * the track) is then left out – the plan itself is there. Without a stored plan (old tours, "Nur aufzeichnen") "Als Plan
 * übernehmen" stays the way back. Never for the tour being recorded, never during a tour. Pure.
 */
object RouteRowActions {
    enum class Section { LIVE, DRAFT, SAVED }
    enum class Action { PLAN, GO, SAVE, DELETE, PLAN_FROM, RENAME, COPY, EXPORT_GPX, SHARE, EDIT_RIDES }

    /**
     * A row's facts "4,1 km · ↑ 82 m · 1 h 15 min": a value never breaks inside at a large font ("1 h" and "15 min" stay
     * together, no-break spaces within each value), the line wraps only between the values.
     */
    fun facts(distance: String, ascent: String, duration: String): String =
        listOf(distance, "↑ $ascent", duration).joinToString(" · ") { it.replace(' ', ' ') }

    /** [inline] on the row, [menu] in its ⋮ (in this order), [chevron] = "›" (a tap opens the overview). */
    data class Row(val inline: List<Action>, val menu: List<Action>, val chevron: Boolean, val opensOverview: Boolean)

    /**
     * [liveMarked] = a saved route that is the plan on the map ("In Bearbeitung" tag); [canSaveLive] = that plan can be
     * saved now (top row: ≥ 2 points; tagged saved route: changed since saving). [recording] = the tour being recorded
     * (never deleted from here – that would lose the track). [touring] = a tour runs: no Planen / Los geht's.
     * [hasPlan] = a recorded tour carries the plan it followed.
     */
    fun of(
        section: Section, kind: TourKind, recording: Boolean = false, hasRides: Boolean = false,
        hasDistance: Boolean = true, waypoints: Int = 0, liveMarked: Boolean = false, canSaveLive: Boolean = false,
        touring: Boolean = false, hasPlan: Boolean = false,
    ): Row {
        val followedPlan = kind == TourKind.RECORDED && hasPlan && !recording && !touring
        val plans = (kind == TourKind.PLANNED && !touring && !liveMarked) || followedPlan
        val goes = plans && (waypoints >= 2 || followedPlan)
        return of(section, kind, recording, hasRides, hasDistance, waypoints, liveMarked, canSaveLive, plans, goes, followedPlan)
    }

    private fun of(
        section: Section, kind: TourKind, recording: Boolean, hasRides: Boolean, hasDistance: Boolean, waypoints: Int,
        liveMarked: Boolean, canSaveLive: Boolean, plans: Boolean, goes: Boolean, followedPlan: Boolean,
    ): Row = when (section) {
        Section.LIVE -> Row(inline = if (canSaveLive) listOf(Action.SAVE) else emptyList(), menu = emptyList(),
            chevron = false, opensOverview = false)
        Section.DRAFT -> Row(inline = listOfNotNull(Action.PLAN.takeIf { plans }, Action.SAVE, Action.DELETE),
            menu = listOfNotNull(Action.GO.takeIf { goes }, Action.RENAME, Action.EXPORT_GPX),
            chevron = false, opensOverview = true)
        Section.SAVED -> Row(
            inline = if (liveMarked && canSaveLive) listOf(Action.SAVE) else emptyList(),
            menu = buildList {
                if (plans) add(Action.PLAN)
                if (goes) add(Action.GO)
                if (kind != TourKind.PLANNED && hasDistance && !recording && !followedPlan) add(Action.PLAN_FROM)
                add(Action.RENAME)
                if (kind == TourKind.PLANNED) add(Action.COPY)
                add(Action.EXPORT_GPX)
                if (kind == TourKind.PLANNED && waypoints >= 2) add(Action.SHARE)
                if (hasRides && !recording) add(Action.EDIT_RIDES)
                if (!recording) add(Action.DELETE)
            },
            chevron = !liveMarked, opensOverview = !liveMarked,
        )
    }
}

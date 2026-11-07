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
 * A tap on the row (or on its preview tile, marked ⤢) opens the large overview – except the plan on the map. Pure.
 */
object RouteRowActions {
    enum class Section { LIVE, DRAFT, SAVED }
    enum class Action { SAVE, DELETE, PLAN_FROM, RENAME, COPY, EXPORT_GPX, SHARE, EDIT_RIDES }

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
     * (never deleted from here – that would lose the track).
     */
    fun of(
        section: Section, kind: TourKind, recording: Boolean = false, hasRides: Boolean = false,
        hasDistance: Boolean = true, waypoints: Int = 0, liveMarked: Boolean = false, canSaveLive: Boolean = false,
    ): Row = when (section) {
        Section.LIVE -> Row(inline = if (canSaveLive) listOf(Action.SAVE) else emptyList(), menu = emptyList(),
            chevron = false, opensOverview = false)
        Section.DRAFT -> Row(inline = listOf(Action.SAVE, Action.DELETE), menu = listOf(Action.RENAME, Action.EXPORT_GPX),
            chevron = false, opensOverview = true)
        Section.SAVED -> Row(
            inline = if (liveMarked && canSaveLive) listOf(Action.SAVE) else emptyList(),
            menu = buildList {
                if (kind != TourKind.PLANNED && hasDistance && !recording) add(Action.PLAN_FROM)
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

package com.onpath.app.domain

/**
 * The three groups of every context menu (owner 2026-11-26, `docs/team/items/2026-11-26-context-menus-wording.md`),
 * always in this order with a thin line between them: [Group.ROUTE] (what the point becomes – Start → Stopp → Ziel),
 * [Group.PLACE] (Merken · In Google Maps öffnen), [Group.REMOVE] (always last). The rows come from [PinMenuItems.of] /
 * [MapGestures.pointMenu] / [PlaceAddMenu.Menu.rows]; app and planner draw the line where [lineBefore] says. Pure.
 */
object MenuGroups {
    enum class Group { ROUTE, PLACE, REMOVE }

    /** Row [i] of a menu whose rows belong to [groups] gets a thin line above it: it starts a new group. */
    fun lineBefore(groups: List<Group>, i: Int): Boolean = i in 1 until groups.size && groups[i] != groups[i - 1]
}

package com.onpath.app.domain

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Where the found place's menu goes (owner 2026-11-13, "A mit Ausweich B"): a picked search result / shared place gets
 * the pin's hold menu, already open, above the pin (A). When it does not fit there – decided by the menu's measured
 * height, not by the font scale – the same rows show as a card at the bottom (B, name + address on top). Pure.
 *
 * All values in pixels of the map box: [top] / [bottom] = the free map between the top bar and the plan sheet.
 */
object PinMenuPlace {
    enum class Kind {
        /** Above the pin's head (after [Decision.panPx], when not 0). */
        ABOVE,
        /** Under the pin's tip – only when above does not fit even with the map moved (the pin near the bottom). */
        BELOW,
        /** A card at the bottom over the plan sheet. */
        CARD,
    }

    /** [panPx] > 0: the map moves its content down by this much first, so the menu fits above the pin. */
    data class Decision(val kind: Kind, val panPx: Int = 0)

    /** Room kept under the pin's tip when the map moves it down (the place stays clearly above the sheet), dp. */
    const val TIP_ROOM_DP = 24

    /** Gap between the pin's head and the menu (MapOverlayHost's overlays), dp. */
    const val GAP_DP = 22

    /**
     * @param headY top of the pin's head; [tipY] its tip (= the place)
     * @param menuH the menu's measured height
     * @param tipRoom [TIP_ROOM_DP] in pixels; [gap] [GAP_DP] in pixels
     * @param mayPan the map may move (Plan, the pin's own menu)
     */
    fun decide(headY: Float, tipY: Float, menuH: Int, top: Int, bottom: Int, gap: Int, tipRoom: Int, mayPan: Boolean = true): Decision {
        val above = headY - gap - menuH
        if (above >= top) return Decision(Kind.ABOVE)
        val need = ceil(top - above).toInt()
        if (mayPan && tipY + need <= bottom - tipRoom) return Decision(Kind.ABOVE, need)
        if (tipY + gap + menuH <= bottom) return Decision(Kind.BELOW)
        return Decision(Kind.CARD)
    }

    /**
     * The map move of ONE opened menu, handed out once ([want] while measuring, [take] where the map is moved). Release
     * flow 10, 2026-11-19: the effect that moves the map ran twice for one decision (launched before the move was
     * known, it read the new value; then relaunched for it) – the map moved 2 × 428 px, the pin went to the sheet's
     * edge and its name chip could not show once the menu closed.
     */
    class PanOnce {
        private var wanted = 0
        private var done = false

        /** The decision wants the map moved by [px] (0 = not at all); only the first wish counts. */
        fun want(px: Int) { if (!done && wanted == 0) wanted = px }

        /** True once a move was wished – whether or not it was taken already. */
        val asked: Boolean get() = wanted != 0

        /** The move to make now: the wished one the first time, 0 after that. */
        fun take(): Int {
            if (done || wanted == 0) return 0
            done = true
            return wanted
        }
    }

    /**
     * B: how far the map moves its content up (a negative pixel count, 0 = not at all) so the pin stays visible above a
     * card whose top is at [cardTop] – never so far that the pin's head goes under the top bar ([top]).
     */
    fun cardPan(headY: Float, tipY: Float, cardTop: Int, top: Int, tipRoom: Int): Int {
        val want = cardTop - tipRoom - tipY
        if (want >= 0f) return 0
        val most = top - headY // the farthest up the head may go (≤ 0 when the head is below the top bar)
        return min(0, max(want, most).toInt())
    }
}

/**
 * The rows of a held place's menu ([PoiMenu]: a map POI or the found place's pin), in their order – the same for a hold
 * and for a picked search result (owner 2026-11-13). Since 2026-11-26 (owner, `docs/team/items/2026-11-26-context-menus-wording.md`)
 * in three groups, a thin line between them ([group], [MenuGroups.lineBefore]): Route (Als Start → Als Stopp → Als Ziel,
 * then „Rundtour ab hier“) → Ort (Merken · In Google Maps öffnen) → Entfernen („Nadel entfernen“, the pin only). Pure.
 */
object PinMenuItems {
    enum class Row { START_HERE, ROUND_TRIP, GO_HERE, ADD, ADD_STOP, NEW_DESTINATION, OVERNIGHT, FAVOURITE, HOME, GOOGLE, REMOVE_PIN }

    /** The group a row belongs to – a thin line where the group changes ([MenuGroups.lineBefore]). */
    fun group(r: Row): MenuGroups.Group = when (r) {
        Row.START_HERE, Row.GO_HERE, Row.ADD, Row.ADD_STOP, Row.NEW_DESTINATION, Row.OVERNIGHT -> MenuGroups.Group.ROUTE
        Row.ROUND_TRIP -> MenuGroups.Group.LOOP // its own line above it (owner 2026-11-27)
        Row.FAVOURITE, Row.HOME, Row.GOOGLE -> MenuGroups.Group.PLACE
        Row.REMOVE_PIN -> MenuGroups.Group.REMOVE
    }

    /** Whether row [i] of [rows] gets a thin line above it (it starts a new group). */
    fun lineBefore(rows: List<Row>, i: Int): Boolean = MenuGroups.lineBefore(rows.map(::group), i)

    /**
     * @param emptyPlan no point yet; [startHere] false during a tour ([MapGestures.offersStartHere])
     * @param menu the add entries with 2+ points ([PlaceAddMenu]), shown in [PlaceAddMenu.Menu.rows]' fixed order
     *   Stopp → Ziel; null = one entry „Als Ziel“ / its [Row.ADD]
     * @param pin the found place's pin: the menu ends with "Nadel entfernen"
     * @param keep the place can be kept (owner 2026-11-15, „Orte merken“): in the Ort group, before Google,
     *   „☆ Merken“ and „⌂ Als Zuhause festlegen“ – what is about the place itself
     * @param overnight a multi-day tour: „Als Übernachtung“ after the add rows (the planner draws it; the app has no
     *   multi-day editing)
     * @param roundTrip the tour suggestions are on (developer switch, tour-suggestions.md §7 stage 4): „Rundtour ab hier“
     *   last in the Route group – only where „Als Start“ is offered (owner 2026-11-15 / 2026-11-26)
     */
    fun of(emptyPlan: Boolean, startHere: Boolean, menu: PlaceAddMenu.Menu?, pin: Boolean, keep: Boolean = false,
           roundTrip: Boolean = false, fromMe: Boolean = true, overnight: Boolean = false): List<Row> {
        fun row(i: PlaceAddMenu.Item) = when (i) {
            PlaceAddMenu.Item.ADD_STOP -> Row.ADD_STOP
            PlaceAddMenu.Item.NEW_DESTINATION -> Row.NEW_DESTINATION
            PlaceAddMenu.Item.START_HERE -> Row.START_HERE
            PlaceAddMenu.Item.GO_HERE -> Row.GO_HERE
            PlaceAddMenu.Item.SET_DESTINATION -> Row.ADD
        }
        val rows = mutableListOf<Row>()
        when {
            // the likeliest on top (owner 2026-11-26 night): „Als Ziel“ from my position when it can, else „Als Start“;
            // „Rundtour ab hier“ always last
            emptyPlan -> {
                if (fromMe || !startHere) { rows += Row.GO_HERE; if (startHere) rows += Row.START_HERE }
                else { rows += Row.START_HERE; rows += Row.GO_HERE }
                if (startHere && roundTrip) rows += Row.ROUND_TRIP
            }
            menu == null -> rows += Row.ADD
            else -> menu.rows.mapTo(rows, ::row)
        }
        // a multi-day tour (owner 2026-11-26 night: no „Hotel für diese Tour“ dialog any more): „Als Übernachtung“ after
        // the add rows – the place goes in where it fits the route and ends that day ([com.onpath.app.routing.PlanDays.nightAtPlace])
        if (overnight && !emptyPlan) rows += Row.OVERNIGHT
        if (keep) { rows += Row.FAVOURITE; rows += Row.HOME }
        rows += Row.GOOGLE
        if (pin) rows += Row.REMOVE_PIN
        return rows
    }
}

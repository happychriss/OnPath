package com.onpath.app.ui

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
 * and for a picked search result (owner 2026-11-13). Pure.
 */
object PinMenuItems {
    enum class Row { START_HERE, GO_HERE, ADD, ADD_STOP, NEW_DESTINATION, GOOGLE, REMOVE_PIN, MORE }

    /**
     * @param emptyPlan no point yet; [startHere] false during a tour ([MapGestures.offersStartHere])
     * @param menu the add entries with 2+ points ([PlaceAddMenu]); null = one entry "Set as destination" / its [Row.ADD]
     * @param pin the found place's pin: the menu ends with "Nadel entfernen"
     * @param more "⋯ Mehr" was tapped: the hidden alternative shows instead of it
     */
    fun of(emptyPlan: Boolean, startHere: Boolean, menu: PlaceAddMenu.Menu?, pin: Boolean, more: Boolean = false): List<Row> {
        fun row(i: PlaceAddMenu.Item) = when (i) {
            PlaceAddMenu.Item.ADD_STOP -> Row.ADD_STOP
            PlaceAddMenu.Item.NEW_DESTINATION -> Row.NEW_DESTINATION
            PlaceAddMenu.Item.START_HERE -> Row.START_HERE
            PlaceAddMenu.Item.GO_HERE -> Row.GO_HERE
            PlaceAddMenu.Item.SET_DESTINATION -> Row.ADD
        }
        val rows = mutableListOf<Row>()
        when {
            emptyPlan -> { if (startHere) rows += Row.START_HERE; rows += Row.GO_HERE }
            menu == null -> rows += Row.ADD
            else -> menu.main.mapTo(rows, ::row)
        }
        rows += Row.GOOGLE
        if (pin) rows += Row.REMOVE_PIN
        // the alternative a clear case hides is never lost (owner 2026-11-07 11:30)
        if (!emptyPlan && menu != null && menu.more.isNotEmpty()) { if (more) menu.more.mapTo(rows, ::row) else rows += Row.MORE }
        return rows
    }
}

package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint

/**
 * The "Show whole route" map button (owner 2026-10-31, the browser planner's ⛶ `fit_screen`): when it shows and what
 * the camera fits. Pure – AppViewModel.showWholeRoute does the tap, MapController.fitTo the camera.
 */
internal object WholeRoute {

    /** Which line the button shows. */
    enum class Source { SHOWN, PLANNED, FOLLOWED }

    /**
     * Plan: a shown track (the plan line is not drawn then), else the planned route, else the followed line (a tour
     * runs, no plan). Navigate: the followed line – with the lead-in to the start once "Guide me" was taken.
     * null = nothing to show: no button.
     */
    fun source(mode: AppMode, planned: Boolean, shown: Boolean, followed: Boolean): Source? = when (mode) {
        AppMode.PLAN -> when {
            shown -> Source.SHOWN
            planned -> Source.PLANNED
            followed -> Source.FOLLOWED
            else -> null
        }
        AppMode.NAVIGATE -> if (followed) Source.FOLLOWED else null
    }

    /** The button shows: something to show, and not in the Points step (little map left; only Undo + locate there). */
    fun shows(mode: AppMode, pointsOpen: Boolean, planned: Boolean, shown: Boolean, followed: Boolean): Boolean =
        !pointsOpen && source(mode, planned, shown, followed) != null

    /**
     * What the camera fits: every point of [lines], every [waypoints] point (a point the route does not reach lies off
     * the line) and – Navigate – the [position], so you see where you are on the whole route.
     */
    fun bounds(lines: List<List<GeoPoint>>, waypoints: List<GeoPoint>, position: GeoPoint?): List<GeoPoint> =
        lines.flatten() + waypoints + listOfNotNull(position)

    /**
     * An edit that gives the plan its first route (fewer than two points → two or more: the first destination set,
     * "Go here", a route opened into an empty plan): the route is fitted once it is computed (field A-019 – the line
     * lay half off the map). Later map edits never move the map ([fitAfterPlace]: a place from the card does).
     */
    fun firstRoute(pointsBefore: Int, pointsAfter: Int): Boolean = com.onpath.app.routing.RouteFit.firstRoute(pointsBefore, pointsAfter)

    /**
     * A place added from the place card or the candidate marker's menu ("Add as new destination", "Add as stop 2", "Set
     * as destination" – a search result or shared place, often far off the shown part): the new route is fitted once it
     * is computed, like the first route (field A-019 add-on, 09d: the map stayed on the place). Only when the plan
     * changed and has a route. A hold on the map never moves it (the user is looking there).
     */
    fun fitAfterPlace(pointsBefore: List<GeoPoint>, pointsAfter: List<GeoPoint>): Boolean =
        com.onpath.app.routing.RouteFit.fitAfterPlace(pointsBefore, pointsAfter)

    /** Wait after the route arrived before fitting: the plan sheet grows to its route size and reports it (120 ms after it rests) – its height is the padding. */
    const val SETTLE_MS = 500L
}

/**
 * Room the camera leaves free when it fits a route (pixels; MapController.fitTo). [above] = another room the fit may
 * take instead, which may name another one in turn (Plan: below the activity button instead of beside it, the whole
 * width above the map buttons – [FitPaddingRule]); the one that shows the route biggest wins ([FitPaddingRule.pick]).
 */
data class FitPadding(val left: Int, val top: Int, val right: Int, val bottom: Int, val above: FitPadding? = null)

/**
 * One padding rule for every fit (opening a route, "Show whole route"): nothing of the route hides under the top
 * elements or the bottom ones, and no more margin than that (field A-019 add-on, 01b: with the sheet pulled up the
 * destination lay under the map buttons; with the small sheet the route had a wide margin).
 * - Plan: below the search pill / ☰ ([TOP_BAR_BOTTOM]) or the strip / box under it ([planTopDp], dp below the status
 *   bar); above the plan sheet at its CURRENT height ([sheetPx]). The map buttons stand in a column on the right above
 *   the sheet ([fabWidthPx] × [fabHeightPx], measured): the route fits either beside them (right edge = the column) or
 *   above them over the whole width (bottom edge = the column's top) – [FitPadding.above], the bigger route wins
 *   ([pick]). The activity button in the top left (measured: its right / bottom edge, `activityRightPx` /
 *   `activityBottomPx`) gives each room two forms – beside it (left edge right of the button) or below it (top edge
 *   under it): a point is never drawn under it (owner's Pixel 2026-11-07, ② under the button). Every edge keeps
 *   [AIR_DP] for a route point marker's radius.
 * - Navigate: below the turn / status column or the message box under it ([navTopDp]); above the slider
 *   ([navBottomDp]); clear of the big map buttons on the right.
 */
internal object FitPaddingRule {
    const val SIDE_DP = 48
    const val GAP_DP = 12
    /** Navigate's right edge: 16 dp margin + a 56 dp map button + 8 dp. */
    const val NAV_RIGHT_DP = 16 + 56 + 8
    /** A route point marker's radius (30 dp marker) + 1: a point on the edge of the room is drawn whole. */
    const val AIR_DP = 16
    /** Plan: the left / free right edge – the marker's room and a little air. */
    const val PLAN_SIDE_DP = AIR_DP + 8
    /** Plan's map footer (MapFooter): 16 dp right of the button column, 12 dp below it, scale bar + © on the left. */
    const val FAB_END_DP = 16
    const val FAB_BOTTOM_DP = 12
    const val FOOTER_DP = 48

    fun of(mode: AppMode, density: Float, statusBarPx: Int, planTopDp: Int, sheetPx: Int, navTopDp: Int, navBottomDp: Int,
           fabWidthPx: Int = 0, fabHeightPx: Int = 0, activityRightPx: Int = 0, activityBottomPx: Int = 0): FitPadding {
        fun px(dp: Int) = (dp * density).toInt()
        return when (mode) {
            AppMode.PLAN -> {
                val top = statusBarPx + px(maxOf(planTopDp, TOP_BAR_DP) + GAP_DP)
                val fabW = if (fabWidthPx > 0) fabWidthPx else (MAP_FAB.value * density).toInt()
                // over the whole width, above the button column (only once its height is known)
                val above = if (fabHeightPx <= 0) null
                    else FitPadding(px(PLAN_SIDE_DP), top, px(PLAN_SIDE_DP), sheetPx + px(FAB_BOTTOM_DP + AIR_DP) + fabHeightPx)
                // beside the button column, down to the scale bar / © above the sheet
                val beside = FitPadding(px(PLAN_SIDE_DP), top, fabW + px(FAB_END_DP + AIR_DP), sheetPx + px(FOOTER_DP))
                val rooms = listOfNotNull(beside, above)
                // the activity button (top left, measured; 0 = not shown): each room beside it or below it
                val clearLeft = activityRightPx + px(AIR_DP)
                val clearTop = activityBottomPx + px(AIR_DP)
                val all = if (activityRightPx <= 0 || clearTop <= top) rooms
                    else rooms.flatMap { r -> listOf(r.copy(left = maxOf(r.left, clearLeft)), r.copy(top = maxOf(r.top, clearTop))) }
                all.foldRight(null as FitPadding?) { r, next -> r.copy(above = next) }!!
            }
            AppMode.NAVIGATE -> FitPadding(px(SIDE_DP), statusBarPx + px(navTopDp + GAP_DP), px(NAV_RIGHT_DP),
                px(navBottomDp + 2 * GAP_DP))
        }
    }

    /**
     * The room that shows the route biggest of [pad] and the rooms it names ([FitPadding.above], in turn): [zoomFor] =
     * the camera zoom a fit into that room gives (null = unknown: that room is not taken; the first unknown → [pad]).
     * A tie keeps the earlier one (beside the buttons first).
     */
    fun pick(pad: FitPadding, zoomFor: (FitPadding) -> Double?): FitPadding {
        if (pad.above == null) return pad
        val first = zoomFor(pad.copy(above = null)) ?: return pad
        var best = pad
        var bestZoom = first
        var alt = pad.above
        while (alt != null) {
            val room = alt.copy(above = null)
            val z = zoomFor(room)
            if (z != null && z > bestZoom + 1e-6) { best = room; bestZoom = z }
            alt = alt.above
        }
        return best
    }

    /** [TOP_BAR_BOTTOM] in dp (search pill + ☰ below the status bar). */
    private val TOP_BAR_DP = TOP_BAR_BOTTOM.value.toInt()
}

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
     * lay half off the map). Later edits never move the map.
     */
    fun firstRoute(pointsBefore: Int, pointsAfter: Int): Boolean = pointsBefore < 2 && pointsAfter >= 2

    /** Wait after the route arrived before fitting: the plan sheet grows to its route size and reports it (120 ms after it rests) – its height is the padding. */
    const val SETTLE_MS = 500L
}

/**
 * Room the camera leaves free when it fits a route (pixels; MapController.fitTo). [above] = a second room the fit may
 * take instead (Plan: the whole width above the map buttons, [FitPaddingRule]) – the one that shows the route bigger wins.
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
 *   ([pick]). Every edge keeps [AIR_DP] for a route point marker's radius.
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
           fabWidthPx: Int = 0, fabHeightPx: Int = 0): FitPadding {
        fun px(dp: Int) = (dp * density).toInt()
        return when (mode) {
            AppMode.PLAN -> {
                val top = statusBarPx + px(maxOf(planTopDp, TOP_BAR_DP) + GAP_DP)
                val fabW = if (fabWidthPx > 0) fabWidthPx else (MAP_FAB.value * density).toInt()
                // over the whole width, above the button column (only once its height is known)
                val above = if (fabHeightPx <= 0) null
                    else FitPadding(px(PLAN_SIDE_DP), top, px(PLAN_SIDE_DP), sheetPx + px(FAB_BOTTOM_DP + AIR_DP) + fabHeightPx)
                // beside the button column, down to the scale bar / © above the sheet
                FitPadding(px(PLAN_SIDE_DP), top, fabW + px(FAB_END_DP + AIR_DP), sheetPx + px(FOOTER_DP), above)
            }
            AppMode.NAVIGATE -> FitPadding(px(SIDE_DP), statusBarPx + px(navTopDp + GAP_DP), px(NAV_RIGHT_DP),
                px(navBottomDp + 2 * GAP_DP))
        }
    }

    /**
     * The room that shows the route bigger: [zoomFor] = the camera zoom a fit into that room gives (null = unknown).
     * A tie keeps the first (beside the buttons).
     */
    fun pick(pad: FitPadding, zoomFor: (FitPadding) -> Double?): FitPadding {
        val alt = pad.above ?: return pad
        val a = zoomFor(pad.copy(above = null)) ?: return pad
        val b = zoomFor(alt) ?: return pad
        return if (b > a + 1e-6) alt else pad
    }

    /** [TOP_BAR_BOTTOM] in dp (search pill + ☰ below the status bar). */
    private val TOP_BAR_DP = TOP_BAR_BOTTOM.value.toInt()
}

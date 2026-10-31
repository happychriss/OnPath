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

/** Room the camera leaves free when it fits a route (pixels; MapController.fitTo). */
data class FitPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * One padding rule for every fit (opening a route, "Show whole route"): nothing of the route hides under the top
 * elements or the bottom ones.
 * - Plan: below the search pill / ☰ ([TOP_BAR_BOTTOM]) or the strip / box under it ([planTopDp], dp below the status
 *   bar); above the plan sheet ([sheetPx]); the controls sit on the sheet's edge.
 * - Navigate: below the turn / status column or the message box under it ([navTopDp]); above the slider
 *   ([navBottomDp]); clear of the big map buttons on the right.
 */
internal object FitPaddingRule {
    const val SIDE_DP = 48
    const val GAP_DP = 12
    /** Navigate's right edge: 16 dp margin + a 56 dp map button + 8 dp. */
    const val NAV_RIGHT_DP = 16 + 56 + 8

    fun of(mode: AppMode, density: Float, statusBarPx: Int, planTopDp: Int, sheetPx: Int, navTopDp: Int, navBottomDp: Int): FitPadding {
        fun px(dp: Int) = (dp * density).toInt()
        return when (mode) {
            AppMode.PLAN -> FitPadding(px(SIDE_DP), statusBarPx + px(maxOf(planTopDp, TOP_BAR_DP) + GAP_DP),
                px(SIDE_DP), sheetPx + px(SIDE_DP))
            AppMode.NAVIGATE -> FitPadding(px(SIDE_DP), statusBarPx + px(navTopDp + GAP_DP), px(NAV_RIGHT_DP),
                px(navBottomDp + 2 * GAP_DP))
        }
    }

    /** [TOP_BAR_BOTTOM] in dp (search pill + ☰ below the status bar). */
    private val TOP_BAR_DP = TOP_BAR_BOTTOM.value.toInt()
}

package com.onpath.app.ui

import com.onpath.app.recording.GpsStatus
import kotlin.math.max
import kotlin.math.min

/**
 * Map gesture rules of Plan mode (owner spec v3; pure, no Android – unit tested in MapGesturesTest).
 * **Hold only** – a quick tap never changes anything, nothing is ever dragged.
 *
 * | hold ≥ 0.3 s on…                         | vibration | result |
 * | --- | --- | --- |
 * | empty map, plan still empty              | 1×        | first-point menu: Start here · Go here from my location |
 * | empty map / route line                   | 1×        | add point (smart insertion), no message |
 * | POI that is not a route point            | 1×        | POI menu: Add to route · Open in Google Maps |
 * | route point (incl. a POI that is a stop) | 2×        | point menu ([pointMenu]) |
 *
 * The marker wins (within [MARKER_RADIUS_DP], nearest marker first), then a POI, then the line / map.
 * With a menu open a tap anywhere closes it; a hold on another marker / POI opens that one's menu, a hold on
 * the empty map only closes the menu (no point is added while dismissing). A long press counts as a hold.
 * Navigate (or a shown track): nothing is added or moved, a POI tap opens read-only details.
 */
object MapGestures {
    /** Screen radius around a marker that counts as "on the marker", at any zoom. */
    const val MARKER_RADIUS_DP = 26f

    /** MapLibre's long click is reported as [HOLD] too. */
    enum class Press { TAP, HOLD }

    sealed interface Action {
        data class PointMenu(val index: Int) : Action
        data object PoiMenu : Action
        data object AddPoint : Action
        /** First hold on an empty plan: "Start here" · "Go here from my location". */
        data object FirstPointMenu : Action
        data object CloseMenu : Action
        data object PoiDetails : Action
        data object None : Action
    }

    /** Vibration when a hold is armed (ring full): one short click, or a double pulse on a route point. */
    enum class Buzz { NONE, ONE, TWO }

    /** Entries of the point menu, in display order. */
    enum class PointAction { MAKE_START, END_HERE, GOOGLE, DELETE }

    /**
     * @param marker index of the marker under the finger (see [nearestMarker]) or -1
     * @param poi a POI is under the finger
     */
    fun decide(editable: Boolean, press: Press, menuOpen: Boolean, marker: Int, poi: Boolean, planEmpty: Boolean): Action {
        if (!editable) return if (poi && press == Press.TAP) Action.PoiDetails else Action.None
        if (press == Press.TAP) return if (menuOpen) Action.CloseMenu else Action.None
        return when {
            marker >= 0 -> Action.PointMenu(marker)
            poi -> Action.PoiMenu
            menuOpen -> Action.CloseMenu
            planEmpty -> Action.FirstPointMenu
            else -> Action.AddPoint
        }
    }

    /**
     * Why "Go here from my location" in the first-point menu is disabled, or null when a usable position exists.
     * A stale fix (status SEARCHING) is not used: the route would start in the wrong place.
     */
    fun fromMeBlocked(status: GpsStatus, hasFix: Boolean): String? = when (status) {
        GpsStatus.NO_PERMISSION -> "No location permission"
        GpsStatus.OFF -> "Location off"
        GpsStatus.SEARCHING -> "Searching GPS…"
        GpsStatus.WEAK, GpsStatus.GOOD -> if (hasFix) null else "Searching GPS…"
    }

    /** How the armed hold feels (decided at finger-down, same targets as [decide]); [Buzz.NONE] = no ring either. */
    fun buzz(editable: Boolean, menuOpen: Boolean, marker: Int, poi: Boolean): Buzz = when {
        !editable -> Buzz.NONE
        marker >= 0 -> Buzz.TWO
        poi -> Buzz.ONE
        menuOpen -> Buzz.NONE
        else -> Buzz.ONE
    }

    /**
     * Sensible options for route point [index] of [count]: "Make starting point" (not on the start), "End route
     * here" (middle points only), "Open in Google Maps" (named place / POI only), "Delete" (always).
     */
    fun pointMenu(index: Int, count: Int, isPlace: Boolean): List<PointAction> = buildList {
        if (index > 0) add(PointAction.MAKE_START)
        if (index in 1 until count - 1) add(PointAction.END_HERE)
        if (isPlace) add(PointAction.GOOGLE)
        add(PointAction.DELETE)
    }

    /** Vibration pattern for [Vibrator] waveforms (off/on ms): one short pulse, or two clearly separated ones. */
    fun pattern(b: Buzz): LongArray = when (b) {
        Buzz.NONE -> LongArray(0)
        Buzz.ONE -> longArrayOf(0, 30)
        Buzz.TWO -> longArrayOf(0, 30, 90, 30)
    }

    /**
     * Index of the marker nearest to the tap ([x],[y]) within [radiusPx], or -1. Works in screen pixels, so
     * the touch target stays the same size at every zoom; when markers overlap (zoomed out) the nearest wins,
     * on a tie the later one (drawn on top).
     */
    fun nearestMarker(markersX: FloatArray, markersY: FloatArray, x: Float, y: Float, radiusPx: Float): Int {
        var best = -1
        var bestD = radiusPx * radiusPx
        for (i in markersX.indices) {
            val dx = markersX[i] - x
            val dy = markersY[i] - y
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * Top-left of an overlay of size [w]×[h] anchored at ([ax],[ay]): centred above the anchor with [gap],
     * below it when there is no room above, always kept inside [left]..[right] × [top]..[bottom].
     */
    fun placeOverlay(
        ax: Float, ay: Float, w: Int, h: Int,
        left: Int, top: Int, right: Int, bottom: Int, gap: Int,
    ): Pair<Int, Int> {
        val x = (ax - w / 2f).toInt().coerceIn(left, max(left, right - w))
        val above = (ay - gap - h).toInt()
        val below = (ay + gap).toInt()
        val y = when {
            above >= top -> above
            below + h <= bottom -> below
            else -> min(max(top, above), max(top, bottom - h))
        }
        return x to y
    }

    /** True while the anchor is on the visible map area (else the overlay closes). */
    fun anchorVisible(ax: Float, ay: Float, width: Int, height: Int): Boolean =
        ax >= 0 && ay >= 0 && ax <= width && ay <= height
}

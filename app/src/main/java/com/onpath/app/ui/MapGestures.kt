package com.onpath.app.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Map gesture rules (pure, no Android – unit tested in MapGesturesTest).
 *
 * | press \ target | marker            | POI (no stop)       | route line / empty map |
 * | --- | --- | --- | --- |
 * | quick tap      | delete overlay    | Google bubble       | hint "hold to add" (once) |
 * | hold ≥ 0.3 s   | delete overlay    | add POI (pinned)    | add point (smart insertion) |
 * | long press     | drag the point    | add POI (pinned)    | line: drag-insert; empty: add point |
 *
 * The marker always wins (within [MARKER_RADIUS_DP], nearest marker first), then a POI, then the line.
 * While an overlay is open a tap / hold only closes it, unless it hits another marker / POI (then that
 * one's overlay opens instead); a long press on a marker still drags it. Navigate (or a shown track):
 * nothing is added or moved, a POI tap opens read-only details.
 */
object MapGestures {
    /** Screen radius around a marker that counts as "on the marker", at any zoom. */
    const val MARKER_RADIUS_DP = 26f

    enum class Press { TAP, HOLD, LONG_PRESS }

    sealed interface Action {
        data class PointOverlay(val index: Int) : Action
        data class DragPoint(val index: Int) : Action
        data object PoiBubble : Action
        data object AddPoi : Action
        data object AddPoint : Action
        data object LineDrag : Action
        data object CloseOverlay : Action
        data object HoldHint : Action
        data object PoiDetails : Action
        data object None : Action
    }

    /**
     * @param marker index of the marker under the finger (see [nearestMarker]) or -1
     * @param poi a POI is under the finger
     * @param onLine the press is on the planned route line (within the snap distance)
     */
    fun decide(
        editable: Boolean, press: Press, overlayOpen: Boolean,
        marker: Int, poi: Boolean, onLine: Boolean, holdHintShown: Boolean,
    ): Action {
        if (!editable) return if (poi && press == Press.TAP) Action.PoiDetails else Action.None
        if (marker >= 0) return if (press == Press.LONG_PRESS) Action.DragPoint(marker) else Action.PointOverlay(marker)
        if (overlayOpen) return if (poi && press == Press.TAP) Action.PoiBubble else Action.CloseOverlay
        if (poi) return if (press == Press.TAP) Action.PoiBubble else Action.AddPoi
        return when (press) {
            Press.TAP -> if (holdHintShown) Action.None else Action.HoldHint
            Press.HOLD -> Action.AddPoint
            Press.LONG_PRESS -> if (onLine) Action.LineDrag else Action.AddPoint
        }
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

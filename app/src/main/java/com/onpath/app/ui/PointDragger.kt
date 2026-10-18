package com.onpath.app.ui

import android.graphics.PointF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import com.onpath.app.map.MapController
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.routing.WaypointInsertion
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.hypot

/**
 * Plan-mode press-and-drag on the map (F-PLAN-04):
 * - long-press a marker and drag → move that point; release → reroute
 * - long-press a marker without moving → small menu (Remove / Make start / Make destination)
 * - long-press the route line (and optionally drag) → insert a stop into that leg
 *
 * MapLibre reports the long press while the finger is still down; from then on this class
 * consumes the touch stream (the map gets one CANCEL so it does not pan).
 */
class PointDragger(
    private val mapView: MapView,
    private val map: MapLibreMap,
    private val vm: AppViewModel,
    private val controller: () -> MapController?,
) {
    private var moveIndex = -1      // waypoint being moved
    private var insertIndex = -1    // new stop index when dragging the line
    private var start = PointF()
    private var moved = false
    private var mapCancelled = false
    private val active get() = moveIndex >= 0 || insertIndex >= 0

    /** From the map's long-click listener. Returns true when a drag starts. */
    fun onLongPress(ll: LatLng): Boolean {
        val c = controller() ?: return false
        if (!vm.canEditMap) return false // Navigate never moves anything
        val screen = map.projection.toScreenLocation(ll)
        val idx = c.waypointAt(screen)
        if (idx >= 0) {
            moveIndex = idx
        } else {
            val route = vm.route?.points ?: return false
            val p = GeoPoint(ll.latitude, ll.longitude)
            val hit = WaypointInsertion.nearestOnRoute(route, p) ?: return false
            if (hit.second > snapMeters(c) || vm.waypoints.size < 2) return false
            insertIndex = WaypointInsertion.legOfRouteIndex(vm.waypoints, route, hit.first) + 1
        }
        start = screen; moved = false; mapCancelled = false
        mapView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    /** From MapView's OnTouchListener; true = event consumed. */
    fun onTouch(ev: MotionEvent): Boolean {
        if (!active) return false
        if (!mapCancelled) {
            mapCancelled = true
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            runCatching { mapView.onTouchEvent(cancel) }
            cancel.recycle()
        }
        val c = controller()
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1 || c == null) { reset(c); return true }
                if (!moved && hypot(ev.x - start.x, ev.y - start.y) > c.dpToPx(8f)) moved = true
                if (moved) preview(c, at(ev))
            }
            MotionEvent.ACTION_UP -> {
                val p = if (moved) at(ev) else at(start.x, start.y)
                when {
                    moveIndex >= 0 && moved -> vm.movePoint(moveIndex, p)
                    moveIndex >= 0 -> vm.pointMenu = moveIndex
                    else -> vm.insertPoint(insertIndex, p)
                }
                reset(c)
            }
            MotionEvent.ACTION_CANCEL -> reset(c)
        }
        return true
    }

    private fun preview(c: MapController, p: GeoPoint) {
        val w = vm.waypoints.toList()
        val shown = if (moveIndex >= 0) PlanEdits.replace(w, moveIndex, p)
        else w.toMutableList().apply { add(insertIndex.coerceIn(0, size), p) }
        val i = if (moveIndex >= 0) moveIndex else insertIndex.coerceIn(0, w.size)
        c.setWaypoints(shown)
        c.setDragPreview(shown.getOrNull(i - 1), p, shown.getOrNull(i + 1))
    }

    private fun reset(c: MapController?) {
        moveIndex = -1; insertIndex = -1; moved = false
        c?.setDragPreview(null, null, null)
        c?.setWaypoints(if (vm.canEditMap) vm.waypoints.toList() else emptyList(), vm.pinnedIndices())
    }

    private fun at(ev: MotionEvent) = at(ev.x, ev.y)
    private fun at(x: Float, y: Float): GeoPoint =
        map.projection.fromScreenLocation(PointF(x, y)).let { GeoPoint(it.latitude, it.longitude) }

    companion object {
        /** ~24 dp around the line counts as "on the line". */
        fun snapMeters(c: MapController) = c.dpToPx(24f) * c.metersPerPixel()
    }
}

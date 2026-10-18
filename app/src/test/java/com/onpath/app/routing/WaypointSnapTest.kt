package com.onpath.app.routing

import com.onpath.app.ui.PlanHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointSnapTest {
    // A straight east-west "way" at lat 46.68; 0.0001° lat ≈ 11 m.
    private val way = (0..10).map { GeoPoint(46.68, 11.10 + it * 0.001) }
    private fun off(lon: Double, dLat: Double) = GeoPoint(46.68 + dLat, lon)

    @Test fun startAndEndTakeLineEnds() {
        val wps = listOf(off(11.10, 0.0005), off(11.11, -0.0005))
        val on = WaypointSnap.onRoute(wps, way)
        assertEquals(way.first(), on[0])
        assertEquals(way.last(), on[1])
    }

    @Test fun viaUsesMatchedOnLineElseProjection() {
        val via = off(11.1055, 0.0004)
        val wps = listOf(way.first(), via, way.last())
        val matched = GeoPoint(46.68, 11.1052)
        assertEquals(matched, WaypointSnap.onRoute(wps, way, listOf(null, matched, null))[1])
        // matched point not on the drawn line (e.g. a cut spike) → projection of the via
        val proj = WaypointSnap.onRoute(wps, way, listOf(null, GeoPoint(46.681, 11.1052), null))[1]!!
        assertEquals(46.68, proj.lat, 1e-9)
        assertEquals(11.1055, proj.lon, 1e-9)
        // no matched info at all → projection too
        assertEquals(proj, WaypointSnap.onRoute(wps, way)[1])
    }

    @Test fun unknownWithoutLine() {
        assertEquals(listOf(null, null), WaypointSnap.onRoute(listOf(way[0], way[1]), listOf(way[0])))
    }

    @Test fun applyMovesOnlyFreePointsWithMeaningfulDistance() {
        val a = off(11.10, 0.0005)   // ~55 m off → moves
        val b = off(11.105, 0.00002) // ~2 m off → stays
        val c = off(11.11, 0.0005)   // pinned → stays
        val snapped = WaypointSnap.apply(listOf(a, b, c), listOf(way[0], GeoPoint(46.68, 11.105), way[10]), keep = setOf(2))!!
        assertEquals(listOf(way[0], b, c), snapped)
    }

    @Test fun applyIgnoresFarAndUnknownAndReturnsNullWhenNothingMoves() {
        val far = GeoPoint(46.70, 11.10) // ~2.2 km
        assertNull(WaypointSnap.apply(listOf(far, way[3]), listOf(way[0], null)))
        assertNull(WaypointSnap.apply(listOf(way[0], way[3]), listOf(way[0], way[3])))
        assertNull(WaypointSnap.apply(listOf(way[0]), emptyList())) // size mismatch
    }

    @Test fun applyKeepsElevationOfPoint() {
        val p = GeoPoint(46.6805, 11.10, 512.0)
        assertEquals(GeoPoint(46.68, 11.10, 512.0), WaypointSnap.apply(listOf(p, way[5]), listOf(way[0], way[5]))!![0])
    }

    @Test fun snappingAgainIsStable() {
        val wps = listOf(off(11.10, 0.0005), off(11.1055, 0.0004), off(11.11, -0.0003))
        val once = WaypointSnap.apply(wps, WaypointSnap.onRoute(wps, way))!!
        assertNull(WaypointSnap.apply(once, WaypointSnap.onRoute(once, way)))
    }

    @Test fun movesSkipsAmbiguousValues() {
        val a = way[0]; val b = way[5]
        assertEquals(mapOf(a to b), WaypointSnap.moves(listOf(a, way[1]), listOf(b, way[1])))
        assertEquals(emptyMap<GeoPoint, GeoPoint>(), WaypointSnap.moves(listOf(a, a), listOf(b, way[6])))
    }

    /** Snap after "add point" amends the current state: undo removes the point, redo brings the snapped one. */
    @Test fun undoRedoAfterSnap() {
        val h = PlanHistory()
        val a = off(11.10, 0.0005); val b = off(11.11, 0.0005)
        var cur = listOf(a)
        h.record(cur); cur = cur + b // add destination
        val snapped = WaypointSnap.apply(cur, WaypointSnap.onRoute(cur, way))!!
        h.replacePoints(WaypointSnap.moves(cur, snapped)); cur = snapped
        val undone = h.undo(cur)!!
        assertEquals(listOf(way[0]), undone) // b gone, a shown where it was snapped
        assertTrue(!h.canUndo)
        assertEquals(snapped, h.redo(undone))
    }
}

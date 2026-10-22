package com.onpath.app.cells

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Offline maps → "Clean up" (owner 2026-10-22): which installed map areas stay. */
class MapCleanupTest {
    // Meran, Bozen, far away: Vienna, Rome, Munich
    private val meran = GeoPoint(46.67, 11.16)
    private val bozen = GeoPoint(46.50, 11.35)
    private val vienna = GeoPoint(48.21, 16.37)
    private val rome = GeoPoint(41.90, 12.50)
    private val munich = GeoPoint(48.14, 11.58)
    private val paris = GeoPoint(48.86, 2.35)

    private fun cell(p: GeoPoint) = CellId.at(p)!!
    private val installed = listOf(meran, bozen, vienna, rome, munich, paris).map { cell(it) }.distinct().map { it to 10_000_000L }

    private fun removed(i: MapCleanup.Inputs) = MapCleanup.plan(installed, i).remove.toSet()

    @Test fun nothingKeptRemovesEverything() {
        val p = MapCleanup.plan(installed, MapCleanup.Inputs())
        assertEquals(installed.map { it.first }, p.remove)
        assertEquals(installed.size * 10_000_000L, p.removeBytes)
    }

    @Test fun keepsTheAreasAlongThePlan() {
        val gone = removed(MapCleanup.Inputs(plan = listOf(meran, bozen)))
        assertFalse(cell(meran) in gone); assertFalse(cell(bozen) in gone)
        assertTrue(cell(vienna) in gone); assertTrue(cell(rome) in gone)
    }

    @Test fun planWithOnlyOnePointKeepsItsArea() {
        assertFalse(cell(rome) in removed(MapCleanup.Inputs(plan = listOf(rome))))
    }

    @Test fun keepsSavedRoutes() {
        val gone = removed(MapCleanup.Inputs(savedRoutes = listOf(listOf(vienna, GeoPoint(48.25, 16.45)), listOf(rome))))
        assertFalse(cell(vienna) in gone); assertFalse(cell(rome) in gone)
        assertTrue(cell(meran) in gone); assertTrue(cell(paris) in gone)
    }

    @Test fun keepsTheRunningTourAndItsFollowedRoute() {
        val gone = removed(MapCleanup.Inputs(tour = listOf(listOf(munich, GeoPoint(48.15, 11.60))), followed = listOf(paris, GeoPoint(48.80, 2.40))))
        assertFalse(cell(munich) in gone); assertFalse(cell(paris) in gone)
        assertTrue(cell(meran) in gone)
    }

    @Test fun keepsThePositionWithAMargin() {
        // 5 km west of the border of the Meran cell: its neighbour across the border stays too
        val c = cell(meran)
        val west = c.bbox[0]
        val nearBorder = GeoPoint(meran.lat, west + 0.03) // ~2.3 km inside
        val keep = MapCleanup.keep(MapCleanup.Inputs(position = nearBorder))
        assertTrue(c in keep)
        assertTrue(CellId(c.x - 1, c.y) in keep)
        assertFalse(CellId(c.x + 2, c.y) in keep)
        assertTrue(cell(vienna) in removed(MapCleanup.Inputs(position = meran)))
    }

    @Test fun nothingToDoWhenEverythingIsNeeded() {
        val p = MapCleanup.plan(installed.take(1), MapCleanup.Inputs(position = meran))
        assertTrue(p.nothingToDo)
        assertEquals(0L, p.removeBytes)
    }
}

package com.onpath.app.cells

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Offline maps → "Clean up": which installed map areas stay. */
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

    /**
     * Owner 2026-11-07: a bike route's corridor is 20 km, a hike's 10 km – Clean up keeps what the route's own corridor
     * loaded (no download churn), and an unknown activity keeps the largest (robust first).
     */
    @Test fun keptCorridorFollowsTheActivity() {
        val line = listOf(GeoPoint(46.6956, 11.10935), GeoPoint(46.70605, 11.11125))
        val band = CellId.alongLine(line, 20_000.0) - CellId.alongLine(line, 10_000.0).toSet()
        assertTrue(band.isNotEmpty())
        fun kept(i: MapCleanup.Inputs) = MapCleanup.keep(i)
        assertTrue(band.none { it in kept(MapCleanup.Inputs(plan = line, planActivity = Activity.HIKE)) })
        assertTrue(band.all { it in kept(MapCleanup.Inputs(plan = line, planActivity = Activity.MTB)) })
        assertTrue(band.all { it in kept(MapCleanup.Inputs(plan = line)) })
        // saved routes: each with its own activity, a missing one = unknown = the largest
        assertTrue(band.none { it in kept(MapCleanup.Inputs(savedRoutes = listOf(line), savedActivities = listOf(Activity.HIKE))) })
        assertTrue(band.all { it in kept(MapCleanup.Inputs(savedRoutes = listOf(line), savedActivities = listOf(Activity.EBIKE))) })
        assertTrue(band.all { it in kept(MapCleanup.Inputs(savedRoutes = listOf(line), savedActivities = listOf(null))) })
        assertTrue(band.all { it in kept(MapCleanup.Inputs(savedRoutes = listOf(line))) })
        // a running tour: by its activity, unknown = the largest
        assertTrue(band.none { it in kept(MapCleanup.Inputs(tour = listOf(line), followed = line, tourActivity = Activity.HIKE)) })
        assertTrue(band.all { it in kept(MapCleanup.Inputs(tour = listOf(line))) })
        assertTrue(band.all { it in kept(MapCleanup.Inputs(followed = line)) })
    }

    /** The cleanup keeps exactly what the download corridor asks for (CellTriggers.corridorOf) – per activity. */
    @Test fun keepsWhatTheDownloadCorridorLoads() {
        val line = listOf(GeoPoint(46.6956, 11.10935), GeoPoint(46.70605, 11.11125))
        val src = object : CellSource {
            override fun cellsAround(p: GeoPoint, radiusM: Double) = CellId.near(p, radiusM)
            override fun cellsAlong(line: List<GeoPoint>, corridorM: Double) = CellId.alongLine(line, corridorM)
            override fun missing(cells: List<CellId>) = cells
            override fun hasDetailedMap(p: GeoPoint) = false
            override fun request(cells: List<CellId>, reason: CellReason, ownPoint: Boolean) = cells
            override fun markUsed(cells: Collection<CellId>) {}
            override val downloadsAvailable = true
        }
        for (a in Activity.entries + listOf(null)) {
            val loaded = CellTriggers(src).corridorOf(line, a)
            assertTrue("$a", MapCleanup.keep(MapCleanup.Inputs(plan = line, planActivity = a)).containsAll(loaded))
        }
    }
}

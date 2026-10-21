package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import com.onpath.app.data.RoutingDataCheck
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Newer map of an installed area: veil while it loads, hint while built; routing data rules. */
class MapUpdatesTest {
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)
    private val inst = setOf(a)

    @Test fun onlyInstalledAreasCount() {
        assertNull(MapUpdates.of(mapOf(b to CellState.Downloading(1, 10)), inst)) // a NEW area: the overlay's job
        assertNull(MapUpdates.of(mapOf(a to CellState.Ready("v1")), inst))
        assertNull(MapUpdates.of(mapOf(a to CellState.Requested), inst))        // a quick check: nothing flickers
    }

    @Test fun loadingVeilsWithProgress() {
        val u = MapUpdates.of(mapOf(a to CellState.Downloading(5, 10)), inst) as MapUpdateUi.Loading
        assertEquals(0.5f, u.progress!!, 0.001f)
        assertTrue(MapUpdates.of(mapOf(a to CellState.Installing), inst) is MapUpdateUi.Loading)
    }

    @Test fun preparingIsAHint() {
        val u = MapUpdates.of(mapOf(a to CellState.Building("base", 0.2, 330)), inst) as MapUpdateUi.Preparing
        assertEquals(6, u.minutes)
        assertEquals("Newer map for this area being prepared · about 6 min", MapUpdates.text(u))
        assertNull((MapUpdates.of(mapOf(a to CellState.Queued(null, null)), inst) as MapUpdateUi.Preparing).minutes)
    }

    @Test fun routingDataNewer() {
        assertTrue(RoutingDataCheck.isNewer(2000, 1000))
        assertFalse(RoutingDataCheck.isNewer(1000, 2000))
        assertFalse(RoutingDataCheck.isNewer(0, 1000))      // server date unknown
        assertFalse(RoutingDataCheck.isNewer(2000, null))   // no file: the first download is a different flow
    }

    @Test fun routeChangeIsNoticedOnlyWhenTheWayChanged() {
        val line = (0..50).map { GeoPoint(46.67 + it * 0.0002, 11.16) }
        assertFalse(RoutingDataCheck.differs(line, line))
        assertFalse(RoutingDataCheck.differs(line, line.map { GeoPoint(it.lat, it.lon + 0.00005) })) // ~4 m aside
        val detour = line.mapIndexed { i, p -> if (i in 20..30) GeoPoint(p.lat, p.lon + 0.002) else p }    // ~150 m
        assertTrue(RoutingDataCheck.differs(line, detour))
    }
}

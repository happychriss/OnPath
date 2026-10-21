package com.onpath.app.ui

import com.onpath.app.data.RoutingDataLoader
import com.onpath.app.data.RoutingDataLoader.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One loading modal for map + routing data (field test 2026-10-21): texts, one % by bytes, auto-recalculate rule. */
class RoutingLoadsTest {
    private val mb = 1_048_576L
    private fun routing(done: Long, total: Long, phase: Phase = Phase.LOADING) =
        RoutingDataLoader.Status(listOf("E10_N45.rd5"), phase, done, total, null)

    @Test fun mapAndRoutingInOneModal() {
        val map = MapLoadUi(MapLoadUi.Kind.LOADING, 50, 60, regions = 4, bytes = 30 * mb)
        val ui = RoutingLoads.combine(map, routing(0, 200 * mb))!!
        assertEquals("Loading the map for your area", ui.title)
        assertEquals("4 regions + routing data · about 230 MB", ui.totals)
        // by bytes: 50 % of 30 MB + 0 % of 200 MB = 15/230
        assertEquals(6, ui.percent)
    }

    @Test fun routingWeightedBySize() {
        val map = MapLoadUi(MapLoadUi.Kind.LOADING, 100, 0, regions = 1, bytes = 10 * mb)
        assertEquals(52, RoutingLoads.combine(map, routing(100 * mb, 200 * mb))!!.percent) // (10 + 100) / 210
    }

    @Test fun onlyRoutingData() {
        val ui = RoutingLoads.combine(null, routing(50 * mb, 200 * mb))!!
        assertEquals("Loading routing data for this area", ui.title)
        assertEquals("about 200 MB", ui.totals)
        assertEquals(25, ui.percent)
    }

    @Test fun neverBackwardsNever100Early() {
        assertEquals(40, RoutingLoads.combine(null, routing(10 * mb, 200 * mb), held = 40)!!.percent)
        assertEquals(99, RoutingLoads.combine(null, routing(200 * mb, 200 * mb))!!.percent)
    }

    @Test fun offlineAndFailure() {
        val off = RoutingLoads.combine(null, routing(0, 200 * mb, Phase.OFFLINE))!!
        assertEquals(MapLoadUi.Kind.OFFLINE, off.kind)
        assertEquals(MapLoadUi.OFFLINE_TEXT, off.line)
        val failed = RoutingLoads.combine(null, routing(0, 200 * mb, Phase.FAILED))!!
        assertEquals("The routing data for this area could not be loaded", failed.title)
        assertTrue(failed.retryable)
        val map = MapLoadUi(MapLoadUi.Kind.LOADING, 50, 60, regions = 2, bytes = 20 * mb)
        assertEquals(MapLoadUi.Kind.OFFLINE, RoutingLoads.combine(map, routing(0, 200 * mb, Phase.OFFLINE))!!.kind)
    }

    @Test fun bothThereClosesIt() {
        assertNull(RoutingLoads.combine(null, null))
        val map = MapLoadUi(MapLoadUi.Kind.LOADING, 50, 60, regions = 2, bytes = 20 * mb)
        assertEquals("2 regions · about 20 MB", RoutingLoads.combine(map, null)!!.totals)
    }

    @Test fun planIsRecalculatedWhenRoutingDataArrives() {
        assertTrue(RoutingLoads.recalcOnArrival(AppMode.PLAN, onTour = false, points = 2))
        assertFalse(RoutingLoads.recalcOnArrival(AppMode.PLAN, onTour = false, points = 1))
        assertFalse(RoutingLoads.recalcOnArrival(AppMode.PLAN, onTour = true, points = 3))
        assertFalse(RoutingLoads.recalcOnArrival(AppMode.NAVIGATE, onTour = false, points = 3))
    }
}

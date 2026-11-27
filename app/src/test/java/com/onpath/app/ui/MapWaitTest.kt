package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import com.onpath.app.data.RoutingDataLoader
import com.onpath.app.data.RoutingDataLoader.Phase
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.StepShares
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wait window for the map of a place (form A, owner 2026-11-19, [MapWait]): what happens, the regions, the routing data
 * in the same window, offline / failed, the rows. The bar's movement itself: [PercentStallTest].
 */
class MapWaitTest {
    private val mb = 1_048_576L
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)
    private fun routing(done: Long, total: Long, phase: Phase = Phase.LOADING) =
        RoutingDataLoader.Status(listOf("E10_N45.rd5:E11_N46"), phase, done, total, 1.0 * mb)

    @Test fun preparingCountsTheServersTimeDown() {
        val w = MapWait()
        val first = w.update(listOf(a), mapOf(a to CellState.Queued(3, 240)), emptyMap(), null, null, 0)!!
        assertEquals(MapLoadUi.Kind.PREPARING, first.kind)
        val later = w.update(listOf(a), mapOf(a to CellState.Queued(3, 240)), emptyMap(), null, null, 60_000)!!
        assertTrue("moves by the server's time: ${later.percent}", later.percent > first.percent)
        assertTrue("inside the prepare share", later.percent < 30)
        assertTrue("the server's time left: ${later.secondsLeft}", (later.secondsLeft ?: 0) >= 170)
        assertEquals("Map for your area", later.title)
        assertEquals(listOf("being prepared", "load", "add to the map"), later.steps.map { it.label })
        assertTrue("the server builds: the window says why it takes minutes", later.building)
        assertEquals(MapLoadUi.BUILD_TEXT, later.window.note)
    }

    @Test fun offlinePausesAndSaysWhy() {
        val w = MapWait()
        w.update(listOf(a), mapOf(a to CellState.Downloading(4 * mb, 10 * mb)), emptyMap(), null, null, 0)
        val off = w.update(listOf(a), mapOf(a to CellState.Offline("No connection")), emptyMap(), null, null, 10_000)!!
        val still = w.update(listOf(a), mapOf(a to CellState.Offline("No connection")), emptyMap(), null, null, 70_000)!!
        assertEquals(MapLoadUi.Kind.OFFLINE, off.kind)
        assertEquals("the bar stands, grey, with its number", off.percent, still.percent)
        assertEquals(MapLoadUi.OFFLINE_TEXT, off.line)
        assertTrue(off.paused)
    }

    /** Field bug 2026-11-27: Madrid on Wi-Fi while the map server rebuilt (429) – the window said „Keine Internetverbindung“. */
    @Test fun aBusyServerIsSaidAsSuchNeverAsNoInternet() {
        val w = MapWait()
        w.update(listOf(a), mapOf(a to CellState.Queued(1, 60)), emptyMap(), null, null, 0)
        val busy = "The map server is busy – trying again later."
        val s = w.update(listOf(a), mapOf(a to CellState.Offline(busy, serverSide = true)), emptyMap(), null, null, 10_000)!!
        assertEquals(MapLoadUi.Kind.SERVER, s.kind)
        assertEquals("the server's own words", busy, s.line)
        assertTrue("it waits like offline: the bar stands", s.paused)
        // a real offline cell beside it: then the phone is offline – that is the fact to say
        val both = MapWait().update(listOf(a, b), mapOf(a to CellState.Offline(busy, serverSide = true), b to CellState.Offline("No connection")),
            emptyMap(), null, null, 0)!!
        assertEquals(MapLoadUi.Kind.OFFLINE, both.kind)
    }

    @Test fun aSearchedPlaceIsNotYourArea() {
        assertEquals("Map for your area", MapLoadUi(MapLoadUi.Kind.LOADING, 10, null).title)
        assertEquals("Map for this place", MapLoadUi(MapLoadUi.Kind.LOADING, 10, null, here = false).title)
        assertEquals("The map for this place could not be loaded", MapLoadUi(MapLoadUi.Kind.FAILED, 10, null, here = false).title)
    }

    @Test fun failureIsPlainAndOnlyWhenNothingElseGoesOn() {
        val f = MapWait().update(listOf(a), mapOf(a to CellState.Failed("This area could not be prepared (tippecanoe exit 1).", true)),
            emptyMap(), null, null, 0)!!
        assertEquals(MapLoadUi.Kind.FAILED, f.kind)
        assertTrue(f.retryable)
        assertFalse(f.line!!.contains("tippecanoe"))
        assertEquals("The map for your area could not be loaded", f.title)
        val going = MapWait().update(listOf(a, b), mapOf(a to CellState.Failed("x"), b to CellState.Queued(1, 60)), emptyMap(), null, null, 0)!!
        assertEquals(MapLoadUi.Kind.PREPARING, going.kind)
        val offline = MapWait().update(listOf(a, b), mapOf(a to CellState.Offline("x"), b to CellState.Queued(1, 60)), emptyMap(), null, null, 0)!!
        assertEquals(MapLoadUi.Kind.OFFLINE, offline.kind)
    }

    @Test fun regionsSizesAndTheEnd() {
        val w = MapWait()
        val ui = w.update(listOf(a, b), mapOf(a to CellState.Downloading(6 * mb, 12 * mb), b to CellState.Building("base", 0.0, 60)),
            emptyMap(), null, null, 0)!!
        assertEquals(2, ui.regions)
        assertEquals(12 * mb + MapLoads.ESTIMATED_REGION_BYTES, ui.bytes)
        assertEquals("one region still prepared: that is the running step", MapLoadUi.Kind.PREPARING, ui.kind)
        assertEquals("1 map area · ≈ 9 MB", MapLoads.askText(1, MapLoads.ESTIMATED_REGION_BYTES))
        assertEquals(100, w.update(listOf(a, b), mapOf(a to CellState.Ready("v"), b to CellState.Ready("v")), emptyMap(), null, null, 1000)!!.percent)
        assertNull(w.update(listOf(a, b), mapOf(a to CellState.Ready("v"), b to CellState.Ready("v")), emptyMap(), null, null, 2000))
    }

    /** Map and routing data: one row „loading map + routing data“; the routing file's rewrite is part of the join. */
    @Test fun routingDataIsPartOfTheLoadingStep() {
        val w = MapWait()
        val ui = w.update(listOf(a), mapOf(a to CellState.Downloading(5 * mb, 10 * mb)), emptyMap(), routing(2 * mb, 10 * mb), null, 0)!!
        assertTrue(ui.routing)
        assertEquals(20 * mb, ui.bytes)
        assertEquals(listOf("prepared", "loading map + routing data", "add to the map"), ui.steps.map { it.label })
        assertEquals("loading", ui.steps[1].short)
        // the map is in, the routing file is still written: the join step
        val joining = w.update(listOf(a), mapOf(a to CellState.Ready("v")), emptyMap(),
            routing(10 * mb, 10 * mb).copy(writing = true, written = 0.5), null, 5_000)!!
        assertEquals(MapLoadUi.Kind.FINISHING, joining.kind)
        // all there
        assertEquals(100, w.update(listOf(a), mapOf(a to CellState.Ready("v")), emptyMap(), null, null, 6_000)!!.percent)
        assertNull(w.update(listOf(a), mapOf(a to CellState.Ready("v")), emptyMap(), null, null, 7_000))
    }

    @Test fun onlyRoutingData() {
        val ui = MapWait().update(emptyList(), emptyMap(), emptyMap(), routing(3 * mb, 10 * mb), null, 0)!!
        assertEquals(0, ui.regions)
        assertEquals(MapLoadUi.Kind.LOADING, ui.kind)
        assertEquals(listOf("loading routing data", "add to the map"), ui.steps.map { it.label })
        val off = MapWait().update(emptyList(), emptyMap(), emptyMap(), routing(0, 200 * mb, Phase.OFFLINE), null, 0)!!
        assertEquals(MapLoadUi.Kind.OFFLINE, off.kind)
        val failed = MapWait().update(emptyList(), emptyMap(), emptyMap(), routing(0, 200 * mb, Phase.FAILED), null, 0)!!
        assertEquals(MapLoadUi.Kind.FAILED, failed.kind)
        assertTrue(failed.retryable)
        assertNull(MapWait().update(emptyList(), emptyMap(), emptyMap(), null, null, 0))
    }

    /** The learned shares decide where each step's part of the bar ends: a slow joiner's join takes more of the bar. */
    @Test fun learnedSharesWeighTheSteps() {
        val slowJoin = StepShares.bounded(0.2, 0.3, 0.5)
        val ui = MapWait(slowJoin).update(listOf(a), mapOf(a to CellState.Installing), emptyMap(), null, null, 0)!!
        assertTrue("join begins at 50 %: ${ui.percent}", ui.percent in 49..52)
        val def = MapWait().update(listOf(a), mapOf(a to CellState.Installing), emptyMap(), null, null, 0)!!
        assertTrue("join begins at 80 %: ${def.percent}", def.percent in 79..81)
    }

    @Test fun planIsRecalculatedWhenRoutingDataArrives() {
        assertTrue(RoutingLoads.recalcOnArrival(AppMode.PLAN, onTour = false, points = 2))
        assertFalse(RoutingLoads.recalcOnArrival(AppMode.PLAN, onTour = false, points = 1))
        assertFalse(RoutingLoads.recalcOnArrival(AppMode.PLAN, onTour = true, points = 3))
        assertFalse(RoutingLoads.recalcOnArrival(AppMode.NAVIGATE, onTour = false, points = 3))
    }
}

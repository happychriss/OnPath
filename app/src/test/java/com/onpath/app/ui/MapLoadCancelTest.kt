package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellWhy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modal → Cancel = stop (owner). */
class MapLoadCancelTest {
    private val own = CellId(543, 361)
    private val east = CellId(544, 361)
    private val north = CellId(543, 362)

    @Test fun searchedPlaceStopsItsCellsAndRemovesTheRing() {
        val job = CellJob(listOf(own, east), own, CellWhy.SEARCHED)
        val o = MapLoadCancel.decide(setOf(own, east), job, jobWaited = true, installed = emptySet(), bySearch = true)
        assertEquals(listOf(own, east), o.stop)
        assertTrue(o.clearSearchMark)
        assertTrue(o.closeCard) // no "waiting for Wi-Fi" card afterwards
    }

    @Test fun pointSetStopsLoadingButKeepsThePoint() {
        val job = CellJob(listOf(own, east), own, CellWhy.PLANNED)
        val o = MapLoadCancel.decide(setOf(own, east), job, jobWaited = true, installed = emptySet(), bySearch = false)
        assertEquals(listOf(own, east), o.stop)
        assertFalse(o.clearSearchMark) // nothing about points: they stay in the plan
        assertTrue(o.closeCard)
    }

    @Test fun installedRegionsStay() {
        val job = CellJob(listOf(own, east, north), own, CellWhy.PLANNED)
        val o = MapLoadCancel.decide(setOf(own, east), job, jobWaited = true, installed = setOf(east), bySearch = false)
        assertEquals(listOf(own, north), o.stop) // the card's whole job stops, installed ones are kept
    }

    @Test fun cardNotWaitedForStaysAndItsCellsGoOn() {
        val corridor = CellJob(listOf(north), null, CellWhy.PLANNED)
        val o = MapLoadCancel.decide(setOf(own), corridor, jobWaited = false, installed = emptySet(), bySearch = true)
        assertEquals(listOf(own), o.stop)
        assertFalse(o.closeCard)
    }

    /**
     * Field 2026-11-07 (S10, a far destination): the map for the searched place was already there – the window was still
     * up (it stays readable for a moment, or waited for routing data) – and Cancel took the destination too. Cancel stops
     * nothing of its map then: the found place stays.
     */
    @Test fun mapAlreadyThereKeepsTheSearchedPlace() {
        val job = CellJob(listOf(own, east), own, CellWhy.SEARCHED)
        val o = MapLoadCancel.decide(setOf(own, east), job, jobWaited = true, installed = setOf(own, east), bySearch = true)
        assertEquals(emptyList<CellId>(), o.stop)
        assertFalse("map there: the pin stays", o.clearSearchMark)
    }

    @Test fun onlyRoutingDataWaitedForKeepsTheSearchedPlace() {
        // the map regions arrived (the modal no longer waits for them), routing data still loads in the same window
        val job = CellJob(listOf(own), own, CellWhy.SEARCHED)
        val o = MapLoadCancel.decide(emptySet(), job, jobWaited = true, installed = setOf(own), bySearch = true)
        assertFalse(o.clearSearchMark)
    }

    @Test fun partlyLoadedSearchStillRemovesTheRing() {
        val job = CellJob(listOf(own, east), own, CellWhy.SEARCHED)
        val o = MapLoadCancel.decide(setOf(own, east), job, jobWaited = true, installed = setOf(east), bySearch = true)
        assertEquals(listOf(own), o.stop)
        assertTrue("Cancel = stop: its map stops, the ring goes (owner)", o.clearSearchMark)
    }
}

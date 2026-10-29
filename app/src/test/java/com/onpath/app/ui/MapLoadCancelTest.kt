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
}

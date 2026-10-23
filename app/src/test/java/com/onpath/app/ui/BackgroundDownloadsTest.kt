package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The background-download strip (owner 2026-10-22): what counts, progress, and when it shows / hides. */
class BackgroundDownloadsTest {
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)
    private val c = CellId(545, 361)
    private val mb = 1_048_576L

    @Test fun onlyLoadingCellsOutsideTheModalCount() {
        val states = mapOf(
            a to CellState.Downloading(2 * mb, 10 * mb),
            b to CellState.Building("base", 0.5, 60),
            c to CellState.WaitingForWifi(5 * mb), // waits: nothing runs
        )
        val items = BackgroundProgress.cellItems(states, waitedFor = setOf(b)) { if (it == b) 8 * mb else null }
        assertEquals(listOf("cell:${a.id}"), items.map { it.key })
        assertEquals(BackgroundProgress.Item("cell:${a.id}", 2 * mb, 10 * mb), items.single())
        assertTrue(BackgroundProgress.cellItems(mapOf(a to CellState.Ready("v1"), b to CellState.Failed("x")), emptySet()) { null }.isEmpty())
    }

    @Test fun progressCountsFinishedItemsAndNeverGoesBack() {
        val p = BackgroundProgress()
        val one = p.update(listOf(BackgroundProgress.Item("a", 5 * mb, 10 * mb), BackgroundProgress.Item("b", 0, 10 * mb)), 2, 0)!!
        assertEquals(25, one.percent)
        assertEquals(20 * mb, one.bytes)
        // a finished (left the list): counted as done
        val two = p.update(listOf(BackgroundProgress.Item("b", 5 * mb, 10 * mb)), 1, 0)!!
        assertEquals(75, two.percent)
        assertEquals("1 area · 10 MB · 75 %", two.line)
        // a new item joins: the percentage does not drop
        val three = p.update(listOf(BackgroundProgress.Item("b", 5 * mb, 10 * mb), BackgroundProgress.Item("c", 0, 100 * mb)), 2, 0)!!
        assertEquals(75, three.percent)
        // all done: the session ends, a new one starts at 0
        assertNull(p.update(emptyList(), 0, 0))
        assertEquals(0, p.update(listOf(BackgroundProgress.Item("d", 0, mb)), 1, 0)!!.percent)
    }

    /** Owner 2026-10-23: a small named chip under ☰ instead of the full-width strip; the names ride along with the progress. */
    @Test fun namesRideAlongAndThePlanShowsAChip() {
        val groups = LoadNames.groups(listOf(LoadEntry(LoadWhy.POINT, "Bad Waldsee", bytes = 10 * mb)))
        val p = BackgroundProgress()
        val ui = p.update(listOf(BackgroundProgress.Item("a", 3 * mb, 10 * mb)), 1, 0, groups = groups)!!
        assertEquals("Bad Waldsee · 30 %", ui.chipText)
        assertEquals(listOf("Around Bad Waldsee · 10 MB · loading"), ui.groups.map { it.line })
        // a name change does not restart the 10 s wait nor hide the chip (the gate keys on nothing but presence)
        val gate = backgroundGate()
        gate.update(ui, 0)
        assertNotNull(gate.update(ui.copy(groups = emptyList()), 10_000).shown)
        val src = java.io.File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue("Plan shows the chip under ☰", "BackgroundDownloadChip(" in src && "BackgroundDownloadStrip" !in src)
    }

    @Test fun routingDataAloneIsNotNamed() {
        assertEquals("9 MB · 10 %", BackgroundDownloadUi(0, 1, 9 * mb, 10).line)
    }

    /** Owner: appears only after a background download ran > 10 s (2 s is too hectic), stays until all is done. */
    @Test fun stripShowsAfterTenSecondsAndStaysUntilDone() {
        val gate = backgroundGate()
        val ui = BackgroundDownloadUi(2, 0, 20 * mb, 5)
        assertNull(gate.update(ui, 0).shown)
        assertNull(gate.update(ui.copy(percent = 20), 9_000).shown) // progress updates do not restart the wait
        assertNotNull(gate.update(ui.copy(percent = 30), 10_000).shown)
        assertEquals(60, gate.update(ui.copy(percent = 60), 60_000).shown?.percent) // progress shows at once, no timeout
        assertNull(gate.update(null, 70_000).shown) // all done: goes
    }

    @Test fun shortBackgroundDownloadsNeverShow() {
        val gate = backgroundGate()
        val ui = BackgroundDownloadUi(1, 0, mb, 0)
        assertNull(gate.update(ui, 0).shown)
        assertNull(gate.update(null, 8_000).shown) // done after 8 s: never shown
        assertNull(gate.update(ui, 9_000).shown) // a new one waits its own 10 s
        assertNull(gate.update(ui, 18_000).shown)
        assertNotNull(gate.update(ui, 19_000).shown)
    }

    @Test fun waitingLineInSettings() {
        assertEquals("Waiting to download: 16 areas · about 380 MB", WaitingDownloads(16, 2, 380 * mb).line)
        assertEquals("Waiting to download · about 9 MB", WaitingDownloads(0, 1, 9 * mb).line)
        assertEquals(false, WaitingDownloads(0, 0, 0).any)
    }

    /** Owner 2026-10-22: one loading indicator – a newer version of an area is told by the strip, no veil on the map. */
    @Test fun newerMapIsToldByTheStrip() {
        val onlyNewer = BackgroundProgress().update(listOf(BackgroundProgress.Item("cell:${a.id}", 0, 10 * mb)), 1, 0, newer = 1, newerEtaMin = 6)!!
        assertEquals("Loading newer map", onlyNewer.title)
        assertEquals("Newer map for 1 of your areas – replaces the old one when loaded (being prepared, about 6 min)", onlyNewer.newerLine)
        val mixed = BackgroundDownloadUi(regions = 3, routingParts = 0, bytes = 0, percent = 10, newer = 1)
        assertEquals("Loading map", mixed.title)
        assertNull(BackgroundDownloadUi(regions = 3, routingParts = 1, bytes = 0, percent = 10).newerLine)
        // MapScreen no longer reads cell states (no "Loading the newer map…" veil): download ticks don't recompose it
        val src = java.io.File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue("MapScreen must not collect cell states", "cells.states.collectAsState" !in src && "MapUpdateLayer" !in src)
    }
}

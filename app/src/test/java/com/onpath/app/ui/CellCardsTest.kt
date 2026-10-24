package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellState
import com.onpath.app.cells.CellWhy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card texts per state (map-cells.md §9). */
class CellCardsTest {
    private val own = CellId(543, 361)
    private val east = CellId(544, 361)
    private val north = CellId(543, 360)
    private val job = CellJob(listOf(own, east, north), own, CellWhy.PLANNED)
    private val MB = 1_048_576L

    private fun card(states: Map<CellId, CellState>, sizes: Map<CellId, Long> = emptyMap(), available: Boolean = true,
                     wifiOnly: Boolean = false, declined: Boolean = false) =
        CellCards.build(job, states, sizes, available, wifiOnly, declined)

    @Test fun nothingWithoutJob() = assertNull(CellCards.build(null, emptyMap(), emptyMap(), true, false, false))

    @Test fun notAvailableInThisBuild() =
        assertEquals("Map downloads not available in this build", card(emptyMap(), available = false)!!.title)

    @Test fun preparingWithEtaAndWhy() {
        val c = card(mapOf(own to CellState.Building("base", 0.4, 300), east to CellState.Queued(2, 360), north to CellState.Requested))
            as CellCardUi.Working
        assertEquals("Preparing map for this area – about 6 min", c.title)
        assertEquals("Getting the detailed map because you planned here.", c.why)
        assertEquals(listOf("Your point", "Area to the east", "Area to the north"), c.rows.map { it.name })
        assertTrue(c.rows[0].own)
        assertNull(c.question)
    }

    @Test fun downloadingCountsReadyCellsAndBytes() {
        val c = card(mapOf(own to CellState.Ready("2026-10-19"), east to CellState.Downloading(4 * MB, 10 * MB), north to CellState.Queued(1, 60)),
            sizes = mapOf(own to 10 * MB)) as CellCardUi.Working
        assertEquals("Downloading 2 of 3 · 14 MB", c.title)
        assertEquals(0.4f, c.progress!!, 0.01f)
        assertEquals("ready ✓", c.rows[0].state)
    }

    @Test fun mobileQuestionWithSizeUntilAnswered() {
        val st = mapOf(own to CellState.Ready("v"), east to CellState.WaitingForWifi(12 * MB), north to CellState.WaitingForWifi(13 * MB))
        val c = card(st, sizes = mapOf(own to 9 * MB)) as CellCardUi.Working
        assertEquals("Use mobile data? · 2 map areas, 25 MB", c.question!!.text)
        assertEquals("Detailed map ready to download", c.title)
        val answered = card(st, sizes = mapOf(own to 9 * MB), declined = true) as CellCardUi.Working
        assertNull(answered.question)
        assertEquals("2 map areas wait for Wi-Fi · 25 MB", answered.wifiNote)
        // just answered "Wi-Fi only": a quiet status, no choice (review 2026-10-24 C8)
        assertEquals(NoticeLook.QUIET, noticeLook(answered))
        val wifiOnly = card(st, sizes = mapOf(own to 9 * MB), wifiOnly = true) as CellCardUi.Working
        assertNull(wifiOnly.question)
        assertEquals("2 map areas wait for Wi-Fi · 25 MB", wifiOnly.wifiNote)
        // Settings says Wi-Fi only: "Load now over mobile data" stays offered (owner Q6 2026-10-23) – a question
        assertEquals(NoticeLook.QUESTION, noticeLook(wifiOnly))
        assertEquals(NoticeLook.QUESTION, noticeLook(c))
    }

    @Test fun offlineOneLine() {
        val c = card(mapOf(own to CellState.Offline("No connection – the detailed map for this area will load when you are online."),
            east to CellState.Offline("No connection – x"), north to CellState.Offline("No connection – x")))
        assertEquals(CellCards.OFFLINE_TITLE, c!!.title)
        assertTrue(c is CellCardUi.Offline)
        val down = card(mapOf(own to CellState.Offline("Map server not reachable – trying again."), east to CellState.Requested, north to CellState.Requested))
        // one cell offline but others still asked: still the working card
        assertTrue(down is CellCardUi.Working)
    }

    @Test fun failedWithRetry() {
        val c = card(mapOf(own to CellState.Ready("v"), east to CellState.Failed("This area could not be prepared (disk). Planning still works with a straight line."),
            north to CellState.Ready("v")), sizes = mapOf(own to MB, north to MB)) as CellCardUi.Failed
        assertTrue(c.title.startsWith("This area could not be prepared"))
        assertTrue(c.retryable)
        val two = card(mapOf(own to CellState.Failed("Download damaged – please try again later.", true),
            east to CellState.Failed("x", false), north to CellState.Ready("v")), sizes = mapOf(north to MB)) as CellCardUi.Failed
        assertEquals("2 map areas failed: Download damaged – please try again later.", two.title)
    }

    @Test fun doneWithCountAndSize() {
        val c = card(mapOf(own to CellState.Ready("v"), east to CellState.Ready("v")), sizes = mapOf(own to 20 * MB, east to 12 * MB, north to 10 * MB))
        assertEquals("Map ready offline ✓ (3 areas, 42 MB)", c!!.title)
    }

    @Test fun manyCellsCollapse() {
        val cells = (0 until 6).map { CellId(540 + it, 365) }
        val j = CellJob(cells, null, CellWhy.PLANNED)
        val c = CellCards.build(j, cells.associateWith { CellState.Queued(null, null) }, emptyMap(), true, false, false) as CellCardUi.Working
        assertEquals(3, c.rows.size)
        assertEquals("Map area 1", c.rows[0].name)
        assertEquals("3 more areas · waiting", c.more)
        assertEquals("Preparing map for this area", c.title)
    }

    // first start (phone test 2026-10-21): no detailed map anywhere, no position yet
    @Test fun firstStartAsksForLocation() {
        val c = CellCards.firstStart(null, available = true, noDetailedMap = true, waitingForPosition = true, hidden = false, permission = false)
        assertEquals(CellCardUi.NeedPosition.ALLOW, c?.title)
        val w = CellCards.firstStart(null, available = true, noDetailedMap = true, waitingForPosition = true, hidden = false, permission = true)
        assertEquals(CellCardUi.NeedPosition.WAITING, w?.title)
    }

    @Test fun firstStartOnlyWhenNothingElseBringsTheMap() {
        fun f(job: CellJob? = null, available: Boolean = true, none: Boolean = true, waiting: Boolean = true, hidden: Boolean = false) =
            CellCards.firstStart(job, available, none, waiting, hidden, permission = false)
        assertNull(f(job = job))          // a trigger's card is shown instead
        assertNull(f(available = false))  // no downloads in this build: "not available" / missing-data screen
        assertNull(f(none = false))       // a cell or region package is there
        assertNull(f(waiting = false))    // T3 has run (or Navigate)
        assertNull(f(hidden = true))      // the user closed it
    }
}

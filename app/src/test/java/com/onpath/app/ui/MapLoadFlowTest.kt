package com.onpath.app.ui

import com.onpath.app.cells.MapLoadService
import com.onpath.app.domain.OfflineReady
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellState
import com.onpath.app.cells.CellWhy
import com.onpath.app.cells.MapCells
import com.onpath.app.data.Geo
import com.onpath.app.data.RoutingDataLoader
import com.onpath.app.domain.ActionPercent
import com.onpath.app.domain.StepShares
import com.onpath.app.routing.GeoPoint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map loading as the user sees it (converted from BackgroundDownloadsTest, CellCardsTest, LoadNamesTest, MapLoadCancelTest,
 * MapLoadWaitTest, MapNoticeTest, MapUpdatesTest, OfflineResultTest – test-audit G10; C6 / C10 / C13): the one number of
 * the background sign, card / chip / notification texts by state, Cancel = stop and nothing half-written, a newer map is no
 * mix of two versions, routing data is never named, the one plain line after Clean up. The loading notification is in
 * MapLoadServiceTest, the "Ohne Netz" pill in core's OfflineReadyTest, the routing-data checks in :state's
 * RoutingDataCheckTest. The wait window / progress design of 2026-11-19 (WaitProgress*, WaitWindow*) has its own tests.
 */
class MapLoadFlowTest {
    // ---- from BackgroundDownloadsTest.kt ----
    // Form B, the sign beside the map (owner 2026-11-19): what counts, the ONE number, its lines, when it shows / hides.
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)
    private val c = CellId(545, 361)
    private val mb = 1_048_576L

    @Test fun onlyLoadingCellsOutsideTheWindowCount() {
        val states = mapOf(
            a to CellState.Downloading(2 * mb, 10 * mb),
            b to CellState.Building("base", 0.5, 60),
            c to CellState.WaitingForWifi(5 * mb), // waits: nothing runs
        )
        val items = BackgroundProgress.cellItems(states, waitedFor = setOf(b)) { if (it == b) 8 * mb else null }
        assertEquals(listOf("cell:${a.id}"), items.map { it.key })
        assertEquals(ActionPercent.Item("cell:${a.id}", 10 * mb, 0.30 + 0.50 * 0.2), items.single())
        assertTrue(BackgroundProgress.cellItems(mapOf(a to CellState.Ready("v1"), b to CellState.Failed("x")), emptySet()) { null }.isEmpty())
    }

    /** Each area by its steps: the server's own progress, its bytes, the pieces in while it is joined. */
    @Test fun eachLoadByItsSteps() {
        val s = StepShares.DEFAULT
        val joining = MapCells.Joining(setOf(c), 50, 100)
        val items = BackgroundProgress.cellItems(mapOf(a to CellState.Building("base", 0.5, 60), c to CellState.Installing), emptySet(),
            shares = s, joining = joining) { 10 * mb }
        assertEquals(listOf(0.15, 0.90), items.map { it.fraction })
        val r = RoutingDataLoader.Status(listOf("x"), RoutingDataLoader.Phase.LOADING, 5 * mb, 10 * mb, null)
        assertEquals(0.5 * 5 / 7, BackgroundProgress.routingItem(r, s).fraction, 1e-9)
        assertEquals(5.0 / 7 + 2.0 / 7 * 0.5, BackgroundProgress.routingItem(r.copy(doneBytes = 10 * mb, writing = true, written = 0.5), s).fraction, 1e-9)
    }

    /** Areas queued / prepared on the server: the sign moves by the server's time left – no 0 % that stands for minutes. */
    @Test fun preparingOnTheServerMovesThePercent() {
        val p = BackgroundProgress(ActionPercent())
        val prep = com.onpath.app.domain.PrepareProgress()
        val states = mapOf(a to CellState.Queued(2, 240), b to CellState.Building("base", 0.0, 240))
        val shown = (0 until 120 step 10).map { t ->
            p.update(BackgroundProgress.cellItems(states, emptySet(), prepare = prep, now = t * 1000L) { 10 * mb }, 2, 0)!!.percent
        }
        for (i in 1 until shown.size) assertTrue("moves on, never back: $shown", shown[i] > shown[i - 1] || shown[i] == shown[i - 1] && i % 2 == 0)
        assertTrue("moving: $shown", shown.last() >= 9)
        assertTrue("inside the prepare share: $shown", shown.last() < 30)
    }

    /** A check for a newer version of an installed area (just asked, the server has not answered) is no loading. */
    @Test fun checkingInstalledAreaIsNotLoading() {
        val states = mapOf(a to CellState.Requested, b to CellState.Requested, c to CellState.Building("base", 0.2, 60))
        val items = BackgroundProgress.cellItems(states, emptySet(), installed = setOf(a, c)) { null }
        assertEquals(listOf("cell:${b.id}", "cell:${c.id}"), items.map { it.key })
    }

    /** One number over the action: finished loads count in full, a new load joining never drops it, the end starts afresh. */
    @Test fun oneNumberCountsFinishedLoadsAndNeverGoesBack() {
        val p = BackgroundProgress(ActionPercent())
        val one = p.update(listOf(ActionPercent.Item("a", 10 * mb, 0.5), ActionPercent.Item("b", 10 * mb, 0.0)), 2, 0)!!
        assertEquals(25, one.percent)
        assertEquals(20 * mb, one.bytes)
        val two = p.update(listOf(ActionPercent.Item("b", 10 * mb, 0.5)), 1, 0)!!
        assertEquals(75, two.percent)
        val three = p.update(listOf(ActionPercent.Item("b", 10 * mb, 0.5), ActionPercent.Item("c", 100 * mb, 0.0)), 2, 0)!!
        assertEquals(75, three.percent)
        assertNull(p.update(emptyList(), 0, 0))
        assertEquals(0, p.update(listOf(ActionPercent.Item("d", mb, 0.0)), 1, 0)!!.percent)
    }

    /** The details: one line per name with where it stands; a name that finished stays as "done" until the action ends. */
    @Test fun linesByNameTheFinishedOnesDone() {
        val p = BackgroundProgress(ActionPercent())
        fun groups(vararg g: Pair<String, LoadPhase>) = LoadNames.groups(g.map { (n, ph) -> LoadEntry(LoadWhy.POINT, n, bytes = 10 * mb, phase = ph) })
        p.update(listOf(ActionPercent.Item("a", mb, 0.1)), 2, 0, groups = groups("Bad Waldsee" to LoadPhase.LOADING, "Meran" to LoadPhase.PREPARING))
        val ui = p.update(listOf(ActionPercent.Item("a", mb, 0.5)), 1, 0, groups = groups("Meran" to LoadPhase.FINISHING))!!
        assertEquals(listOf("Around Bad Waldsee" to "done", "Around Meran" to "almost done"), ui.rows.map { it.name to it.state })
        assertEquals("The maps you have stay.", ui.notes.last())
        // a name change does not restart the 10 s wait nor hide the sign (the gate keys on nothing but presence)
        val gate = backgroundGate()
        gate.update(ui, 0)
        assertNotNull(gate.update(ui.copy(rows = emptyList()), 10_000).shown)
        val src = java.io.File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue("Plan shows the sign under ☰", "BackgroundDownloadChip(" in src)
    }

    /** Owner: appears only after a background download ran > 10 s (2 s is too hectic), stays until all is done. */
    @Test fun signShowsAfterTenSecondsAndStaysUntilDone() {
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

    /** A newer version of an area is told in the sign's details (when it is ready), no veil on the map. */
    @Test fun newerMapIsToldInTheDetails() {
        val onlyNewer = BackgroundProgress(ActionPercent()).update(listOf(ActionPercent.Item("cell:${a.id}", 10 * mb, 0.0)), 1, 0,
            newer = 1, newerEtaMin = 6)!!
        assertEquals("Newer map ready in about 6 min.", onlyNewer.notes.first())
        assertEquals(listOf("The maps you have stay."), BackgroundDownloadUi(3, 1, 0, 10).notes)
        // MapScreen no longer reads cell states (no "Loading the newer map…" veil): download ticks don't recompose it
        val src = java.io.File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue("MapScreen must not collect cell states", "cells.states.collectAsState" !in src && "MapUpdateLayer" !in src)
    }

    /** The sign and the notification feed the same action: the same number (owner 2026-11-19). */
    @Test fun theNotificationSaysTheSameNumber() {
        val svc = java.io.File("src/main/java/com/onpath/app/cells/MapLoadService.kt").readText()
        assertTrue(svc.contains("BackgroundAction.shared.update(items)"))
        assertTrue(java.io.File("src/main/java/com/onpath/app/ui/BackgroundDownloads.kt").readText()
            .contains("class BackgroundProgress(private val action: ActionPercent = BackgroundAction.shared)"))
    }

    // ---- from CellCardsTest.kt ----
    // Card texts per state (map-cells.md §9).
    private val own = CellId(543, 361)
    private val east = CellId(544, 361)
    private val north = CellId(543, 360)
    private val job = CellJob(listOf(own, east, north), own, CellWhy.PLANNED)

    private fun card(states: Map<CellId, CellState>, sizes: Map<CellId, Long> = emptyMap(), available: Boolean = true,
                     wifiOnly: Boolean = false, declined: Boolean = false) =
        CellCards.build(job, states, sizes, available, wifiOnly, declined)

    @Test fun noCardWithoutJobAndANotAvailableCardInThisBuild() {
        assertNull("nothing without a job", CellCards.build(null, emptyMap(), emptyMap(), true, false, false))
        assertEquals("Map downloads not available in this build", card(emptyMap(), available = false)!!.title)
    }

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
        val c = card(mapOf(own to CellState.Ready("2026-10-19"), east to CellState.Downloading(4 * mb, 10 * mb), north to CellState.Queued(1, 60)),
            sizes = mapOf(own to 10 * mb)) as CellCardUi.Working
        assertEquals("Downloading 2 of 3 · 14 MB", c.title)
        assertEquals(0.4f, c.progress!!, 0.01f)
        assertEquals("ready ✓", c.rows[0].state)
    }

    @Test fun mobileQuestionWithSizeUntilAnswered() {
        val st = mapOf(own to CellState.Ready("v"), east to CellState.WaitingForWifi(12 * mb), north to CellState.WaitingForWifi(13 * mb))
        val c = card(st, sizes = mapOf(own to 9 * mb)) as CellCardUi.Working
        assertEquals("Use mobile data? · 2 map areas, 25 MB", c.question!!.text)
        assertEquals("Detailed map ready to download", c.title)
        val answered = card(st, sizes = mapOf(own to 9 * mb), declined = true) as CellCardUi.Working
        assertNull(answered.question)
        assertEquals("2 map areas wait for Wi-Fi · 25 MB", answered.wifiNote)
        // just answered "Wi-Fi only": a quiet status, no choice
        assertNull(mapNoticeKind(answered))
        val wifiOnly = card(st, sizes = mapOf(own to 9 * mb), wifiOnly = true) as CellCardUi.Working
        assertNull(wifiOnly.question)
        assertEquals("2 map areas wait for Wi-Fi · 25 MB", wifiOnly.wifiNote)
        // Settings says Wi-Fi only: "Load now over mobile data" stays offered – a question
        assertEquals(MessageKind.FRAGE, mapNoticeKind(wifiOnly))
        assertEquals(MessageKind.FRAGE, mapNoticeKind(c))
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
            north to CellState.Ready("v")), sizes = mapOf(own to mb, north to mb)) as CellCardUi.Failed
        assertTrue(c.title.startsWith("This area could not be prepared"))
        assertTrue(c.retryable)
        val two = card(mapOf(own to CellState.Failed("Download damaged – please try again later.", true),
            east to CellState.Failed("x", false), north to CellState.Ready("v")), sizes = mapOf(north to mb)) as CellCardUi.Failed
        assertEquals("2 map areas failed: Download damaged – please try again later.", two.title)
    }

    @Test fun doneWithCountAndSize() {
        val c = card(mapOf(own to CellState.Ready("v"), east to CellState.Ready("v")), sizes = mapOf(own to 20 * mb, east to 12 * mb, north to 10 * mb))
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

    // first start: no detailed map anywhere, no position yet
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

    // ---- from LoadNamesTest.kt ----
    // Human names for loading map areas: by reason, else the nearest overview town; grouped; shortened.

    private fun label(e: LoadEntry) = LoadNames.label(e)

    @Test fun everyReasonHasAPlainName() {
        assertEquals("around Bad Waldsee" to "Bad Waldsee", label(LoadEntry(LoadWhy.POINT, "Bad Waldsee", "Ravensburg")))
        assertEquals("along your route to Gasthof Taser Alm" to "Gasthof Taser Alm",
            label(LoadEntry(LoadWhy.ROUTE, "Gasthof Taser Alm", "Meran")))
        assertEquals("newer map near Meran" to "Meran", label(LoadEntry(LoadWhy.REFRESH, null, "Meran")))
        assertEquals("ahead on your tour", label(LoadEntry(LoadWhy.AHEAD, null, "Meran")).first)
    }

    @Test fun fallbackIsTheNearestTownThenMapArea() {
        assertEquals("near Ravensburg" to "Ravensburg", label(LoadEntry(null, null, "Ravensburg")))
        assertEquals("near Ravensburg", label(LoadEntry(LoadWhy.POINT, null, "Ravensburg")).first) // a point without a name
        assertEquals("map area" to null, label(LoadEntry(null, null, null)))
        assertEquals("along your route", label(LoadEntry(LoadWhy.ROUTE, null, null)).first)
        assertEquals("newer map near Taser Alm", label(LoadEntry(LoadWhy.REFRESH, "Taser Alm", null)).first)
        assertEquals("newer map", label(LoadEntry(LoadWhy.REFRESH, null, null)).first)
    }

    @Test fun groupedByNameMostImportantFirst() {
        val g = LoadNames.groups(listOf(
            LoadEntry(LoadWhy.REFRESH, town = "Meran", bytes = 3 * mb, phase = LoadPhase.PREPARING),
            LoadEntry(null, paths = true, bytes = 2 * mb),
            LoadEntry(LoadWhy.ROUTE, "Gasthof Taser Alm", bytes = 10 * mb, phase = LoadPhase.WAITING),
            LoadEntry(LoadWhy.POINT, "Bad Waldsee", bytes = 5 * mb),
            LoadEntry(LoadWhy.ROUTE, "Gasthof Taser Alm", bytes = 14 * mb, phase = LoadPhase.LOADING),
        ))
        assertEquals(listOf("along your route to Gasthof Taser Alm", "around Bad Waldsee", "newer map near Meran"), g.map { it.label })
        // the route-planning part's 2 MB join the most important line (never a line of its own, owner)
        assertEquals("Along your route to Gasthof Taser Alm (2 areas) · 26 MB · loading", g[0].line)
        assertEquals("Around Bad Waldsee · 5 MB · loading", g[1].line)
        assertEquals("Newer map near Meran · 3 MB · being prepared", g[2].line)
    }

    @Test fun whatTheUserWaitsForComesFirst() {
        val g = LoadNames.groups(listOf(
            LoadEntry(LoadWhy.ROUTE, "Taser Alm"),
            LoadEntry(LoadWhy.POINT, "Bad Waldsee", waiting = true),
        ))
        assertEquals("Bad Waldsee" to 1, LoadNames.chip(g))
    }

    @Test fun chipNamesTheFirstAndCountsTheOthers() {
        assertEquals("Map" to 0, LoadNames.chip(emptyList()))
        assertEquals("Map" to 0, LoadNames.chip(LoadNames.groups(listOf(LoadEntry(null, paths = true)))))
        val two = LoadNames.groups(listOf(LoadEntry(LoadWhy.ROUTE, "Taser Alm"), LoadEntry(LoadWhy.ROUTE, "Taser Alm"),
            LoadEntry(null, town = "Meran")))
        assertEquals("Taser Alm" to 1, LoadNames.chip(two))
        assertEquals("Your route" to 0, LoadNames.chip(LoadNames.groups(listOf(LoadEntry(LoadWhy.ROUTE)))))
    }

    @Test fun longNamesAreShortened() {
        assertEquals("Bad Waldsee", LoadNames.short("Bad Waldsee"))
        val s = LoadNames.short("Berggasthof Gampenalm am Hochplateau über Unsere Liebe Frau")
        assertTrue(s, s.length <= LoadNames.CHIP_NAME_MAX && s.endsWith("…"))
        assertEquals("Berggasthof Gampenalm…", s) // cut at a word end
        assertEquals("Abcdefghijklmnopqrstuvw…", LoadNames.short("Abcdefghijklmnopqrstuvwxyzabc")) // no word end: hard cut
        val chip = LoadNames.chip(LoadNames.groups(listOf(LoadEntry(LoadWhy.POINT, "Berggasthof Gampenalm am Hochplateau"))))
        assertEquals("Berggasthof Gampenalm…", chip.first)
    }

    @Test fun phaseFollowsTheAreaState() {
        assertEquals(LoadPhase.LOADING, LoadNames.phaseOf(CellState.Downloading(1, 2)))
        assertEquals(LoadPhase.PREPARING, LoadNames.phaseOf(CellState.Building("base", 0.5, 30)))
        assertEquals(LoadPhase.PREPARING, LoadNames.phaseOf(CellState.Queued(2, 60)))
        assertEquals(LoadPhase.WAITING, LoadNames.phaseOf(CellState.Requested))
        assertEquals(LoadPhase.FINISHING, LoadNames.phaseOf(CellState.Installing))
    }

    /** Route-planning data is part of "the map" (owner) – never a line or a word of its own. */
    @Test fun routePlanningDataIsNeverNamed() {
        assertTrue(LoadNames.groups(listOf(LoadEntry(null, paths = true, bytes = 9 * mb))).isEmpty()) // only in the total
        val g = LoadNames.groups(listOf(LoadEntry(null, paths = true, bytes = 2 * mb), LoadEntry(LoadWhy.POINT, "Bad Waldsee", bytes = 5 * mb)))
        assertEquals(listOf("Around Bad Waldsee · 7 MB · loading"), g.map { it.line })
        for (l in g.map { it.line }) assertTrue(l, listOf("routing", "route planning", "paths").none { it in l.lowercase() })
    }

    /** Names are never technical (CLAUDE.md: no "cells" / "routing data" toward the user). */
    @Test fun noTechnicalWords() {
        val all = LoadNames.groups(listOf(LoadEntry(null), LoadEntry(null, paths = true), LoadEntry(LoadWhy.AHEAD),
            LoadEntry(LoadWhy.REFRESH), LoadEntry(LoadWhy.ROUTE), LoadEntry(LoadWhy.POINT)))
        for (g in all) assertTrue(g.line, listOf("cell", "routing", "tile").none { it in g.line.lowercase() })
    }

    // ---- from MapLoadCancelTest.kt ----
    // Modal → Cancel = stop (owner).

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

    // ---- from MapLoadWaitTest.kt ----
    // Field bug 2026-11-08 (owner, fresh install): "Wenn man die App zum ersten Mal installiert und den Standort freigibt,
    // dann beginnt sie sofort noch mitten im Onboarding die Karte herunterzuladen. Da muss man warten …" – the first fix
    // after the location step loaded the map for here (T3) and, the empty world map counting as "view without detailed
    // map", opened the loading modal (a Dialog window) over the phone setup guide.

    /** What loadHere hands over: the T3 job, its own cell cleared ("no loading window – the user only opened the app"). */
    private val here = CellJob(listOf(own, east), null, CellWhy.HERE)

    @Test fun theMapForHereNeverOpensTheModal_evenOnAnEmptyWorldMap() {
        assertFalse("fresh install: the world map has no detailed map", MapLoadWait.waits(here, viewWithoutDetail = true))
        assertFalse(MapLoadWait.waits(here, viewWithoutDetail = false))
    }

    @Test fun theUsersOwnPointSearchOrTapStillWaits() {
        assertTrue(MapLoadWait.waits(CellJob(listOf(own), own, CellWhy.PLANNED), viewWithoutDetail = false))
        assertTrue(MapLoadWait.waits(CellJob(listOf(own), own, CellWhy.SEARCHED), viewWithoutDetail = false))
        assertTrue(MapLoadWait.waits(CellJob(listOf(own), own, CellWhy.TAPPED), viewWithoutDetail = false))
    }

    @Test fun aRouteComputedWhileTheViewHasNoDetailedMapStillWaits() {
        val route = CellJob(listOf(own, east), null, CellWhy.PLANNED)
        assertTrue(MapLoadWait.waits(route, viewWithoutDetail = true))
        assertFalse("a route with the map in view loads quietly", MapLoadWait.waits(route, viewWithoutDetail = false))
    }

    @Test fun noModalOverThePhoneSetup() {
        val ui = MapLoadUi(MapLoadUi.Kind.LOADING, 40, 12, regions = 2, bytes = 18L * 1_048_576)
        assertNull(MapLoadWait.shown(ui, setupOpen = true))
        assertEquals(ui, MapLoadWait.shown(ui, setupOpen = false))
        assertNull(MapLoadWait.shown(null, setupOpen = false))
    }

    /** The rule is what the ViewModel and the screen use (Compose / ViewModel wiring is not run on the JVM). */
    @Test fun wiring() {
        val flow = File("src/main/java/com/onpath/app/ui/MapDataFlow.kt").readText()
        val show = flow.substringAfter("private fun showCellJob(").substringBefore("\n    }\n")
        assertTrue(show, show.contains("val waits = MapLoadWait.waits(j, viewWithoutDetail)"))
        assertTrue(show, show.contains("routingForCells(j.cells, wait = waits)"))
        assertTrue(show, show.contains("if (waits) {"))
        assertFalse("no second, looser wait rule", show.contains("viewWithoutDetail) {"))
        // loadHere: the T3 job without its own cell, routing data without the modal
        val here = flow.substringAfter("private fun loadHere(").substringBefore("\n    }\n")
        assertTrue(here, here.contains("?.copy(own = null)"))
        assertTrue(here, here.contains("routingFor(listOf(at), wait = false)"))
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(map.contains("MapLoadModal(MapLoadWait.shown(loadUi, vm.setup.setupOpen),"))
        // one notice (owner 2026-11-27): while the window shows, the quiet pill, the failure box and the overview pill stay away
        assertTrue(map.contains("onShown = { vm.mapData.loadWindowShown = it }"))
        assertTrue(map.contains("firstCard == null && !vm.mapData.loadWindowShown }"))
        assertTrue(map.contains("it.kind != MessageKind.FRAGE && !vm.mapData.loadWindowShown"))
        assertTrue(map.contains("overviewView && !viewComing && !vm.mapData.loadWindowShown"))
    }

    // ---- from MapNoticeTest.kt ----
    // Only what needs the user goes to the top (box or pill); plain progress and "ready" do not.
    private fun working(q: MobileQuestion? = null, wifi: String? = null) =
        CellCardUi.Working("Getting the detailed map", null, emptyList(), null, 0.4f, q, wifi)

    @Test fun questionAndWaitingGoToTheStrip() {
        val q = working(MobileQuestion(3, 25L * 1_048_576))
        assertEquals(q, mapNotice(q))
        val w = working(wifi = "3 areas wait for Wi-Fi (≈ 25 MB)")
        assertEquals(w, mapNotice(w))
    }

    @Test fun plainProgressAndReadyDoNot() {
        assertNull(mapNotice(working()))
        assertNull(mapNotice(CellCardUi.Done(3, 30L * 1_048_576)))
        assertNull(mapNotice(null))
    }

    /** A question = FRAGE, one way out = HINWEIS, a failure = ⚠ HINWEIS (amber), a state = the grey pill (no box). */
    @Test fun oneColourOneMeaning() {
        assertEquals(MessageKind.FRAGE, mapNoticeKind(working(MobileQuestion(3, null))))
        assertEquals(MessageKind.HINWEIS, mapNoticeKind(CellCardUi.NeedPosition(permission = false)))
        assertNull(mapNoticeKind(CellCardUi.NeedPosition(permission = true)))
        assertNull(mapNoticeKind(CellCardUi.Offline(CellCards.OFFLINE_TITLE, null)))
        assertEquals(MessageKind.HINWEIS, mapNoticeKind(CellCardUi.NotAvailable))
        assertEquals(MessageKind.HINWEIS_WARN, mapNoticeKind(CellCardUi.Failed("Download damaged – please try again later.", null, true)))
        assertEquals("Use mobile data? · 1 map area", MobileQuestion(1, null).text)
    }

    @Test fun problemsAndFirstStartDo() {
        val f = CellCardUi.Failed("This area could not be prepared", null, retryable = true)
        assertEquals(f, mapNotice(f))
        assertEquals(CellCardUi.NotAvailable, mapNotice(CellCardUi.NotAvailable))
        val n = CellCardUi.NeedPosition(permission = false)
        assertEquals(n, mapNotice(n))
    }

    // ---- from MapUpdatesTest.kt ----
    // Newer map of an installed area: veil while it loads, hint while built; routing data rules.
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

    // ---- from OfflineResultTest.kt ----
    // After Offline maps → Clean up no "37 map areas removed. OK" box on the map: the user confirmed the removal himself,
    // so the result is a plain line on the Offline maps page until it is left; only a failure keeps a persistent message.

    @Test fun line() {
        assertEquals("37 areas removed · 450 MB freed", OfflineResult.removed(37, 450 * mb, afterTour = false))
        assertEquals("1 area removed · 4.5 MB freed · the map changes after the tour",
            OfflineResult.removed(1, (4.5 * mb).toLong(), afterTour = true))
        assertEquals("No map areas to remove", OfflineResult.removed(0, 0, afterTour = false))
    }
}

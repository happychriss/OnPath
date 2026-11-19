package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellState
import com.onpath.app.cells.CellWhy
import com.onpath.app.cells.MapLoadService
import com.onpath.app.cells.NetworkStatus
import com.onpath.app.data.RoutingDataLoader
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.WaitBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Owner 2026-11-18: „erst ganz schnell und bleibt dann bei 98 % stehen – da denkt jeder, es hängt sich auf“. Every
 * percent the phone shows, with a last phase that takes its time and reports nothing (the map is joined in, the routing
 * file is rewritten, the router finishes): no number may stand near the end – each phase shows its own progress, and a
 * phase without a measurable one says what happens and lets its bar run. (Red before the fix: the loading window stood
 * at 98–99 %, the chip, the first-map card and the notification at 99 %, the route bar at 98 % / full.)
 */
class PercentStallTest {
    private val mb = 1_048_576L
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)

    /** One value per second; null = no number on screen. No number ≥ 90 % may stand for more than [maxS] s. */
    private fun assertNoFrozenNumber(what: String, shown: List<Int?>, maxS: Int = 10) {
        var run = 0
        for (i in 1 until shown.size) {
            val v = shown[i]
            run = if (v != null && v >= 90 && v == shown[i - 1]) run + 1 else 0
            assertTrue("$what: $v % stands for more than $maxS s (second $i): $shown", run <= maxS)
        }
    }

    /** 30 s download, then the map is joined in for 60 s (no progress events). */
    private fun cell(t: Int): CellState = if (t < 30) CellState.Downloading(t * mb, 30 * mb) else CellState.Installing

    /** The loading window as the view model builds it ([MapDataFlow.mapLoadUi]). */
    private class Window {
        private val progress = mutableMapOf<CellId, MapLoadProgress>()
        private var held = 0
        private var combined = 0
        fun at(focus: List<CellId>, states: Map<CellId, CellState>, t: Int, routing: RoutingDataLoader.Status? = null): MapLoadUi {
            val map = MapLoads.aggregate(focus, states, emptyMap(), progress, held, t * 1000L)?.also { held = it.percent }
            return RoutingLoads.combine(map, routing, combined)!!.also { combined = it.percent }
        }
    }

    @Test fun loadingWindow_installingAfterTheDownload() {
        val w = Window()
        val uis = (0 until 90).map { t -> w.at(listOf(a), mapOf(a to cell(t)), t) }
        assertNoFrozenNumber("loading window", uis.map { it.percent.takeIf { _ -> it.hasPercent } })
        // the download: the real bytes with their number
        assertEquals(MapLoadUi.Kind.LOADING, uis[15].kind)
        assertEquals(50, uis[15].percent)
        assertTrue(uis[15].hasPercent)
        // joined in: says so, the bar runs, no number
        assertEquals(MapLoadUi.Kind.FINISHING, uis[45].kind)
        assertFalse(uis[45].hasPercent)
        assertEquals("The map for your area is almost ready", uis[45].title)
        assertNull("no time left to promise", uis[45].line)
    }

    /** Not „erst ganz schnell“ either: the server prepares for minutes – no number races ahead meanwhile. */
    @Test fun loadingWindow_preparingHasNoNumber() {
        val w = Window()
        val uis = (0 until 300).map { t -> w.at(listOf(a), mapOf(a to CellState.Building("base", 0.3, 300)), t) }
        assertTrue(uis.all { it.kind == MapLoadUi.Kind.PREPARING && !it.hasPercent })
        assertEquals("Preparing the map for your area", uis[10].title)
        assertEquals("About 5 minutes left", uis[10].line)
    }

    @Test fun loadingWindow_severalAreasCountUp() {
        val w = Window()
        val first = w.at(listOf(a, b), mapOf(a to CellState.Downloading(5 * mb, 10 * mb), b to CellState.Queued(1, 60)), 0)
        assertEquals("2 map areas · about 19 MB", first.totals)
        assertEquals(26, first.percent) // 5 of about 19 MB
        // the first is there, the second still prepared: the count says how far, the bar runs
        val second = w.at(listOf(a, b), mapOf(a to CellState.Ready("v"), b to CellState.Building("base", 0.5, 30)), 20)
        assertEquals(MapLoadUi.Kind.PREPARING, second.kind)
        assertFalse(second.hasPercent)
        assertEquals("1 of 2 map areas · about 18 MB", second.totals)
    }

    @Test fun loadingWindow_routingFileRewrittenAfterTheDownload() {
        // all bytes of the routing part are here; the rd5 is rewritten (up to minutes on a big file)
        val writing = RoutingDataLoader.Status(listOf("E10_N45.rd5:E11_N46"), RoutingDataLoader.Phase.LOADING, 40 * mb, 40 * mb, 1.0 * mb, writing = true)
        val w = Window()
        val uis = (0 until 60).map { t -> w.at(emptyList(), emptyMap(), t, writing) }
        assertNoFrozenNumber("loading window, routing data", uis.map { it.percent.takeIf { _ -> it.hasPercent } })
        assertEquals(MapLoadUi.Kind.FINISHING, uis[5].kind)
        // while its bytes flow: their real share
        val half = Window().at(emptyList(), emptyMap(), 0, writing.copy(doneBytes = 20 * mb, writing = false))
        assertEquals(MapLoadUi.Kind.LOADING, half.kind)
        assertEquals(50, half.percent)
    }

    /** The loader itself tells the write phase: every byte reported, the download call not back yet. */
    @Test fun routingLoader_tellsWhenTheFileIsWritten() {
        val dir = Files.createTempDirectory("seg").toFile()
        val net = object : NetworkStatus {
            override fun connected() = true
            override fun unmetered() = true
        }
        val seen = mutableListOf<RoutingDataLoader.Status>()
        lateinit var l: RoutingDataLoader
        l = RoutingDataLoader(dir, net, { false }, launch = { it() }, remoteSize = { 40 * mb },
            download = { _, _, progress, _ ->
                progress(20 * mb, 40 * mb); seen += l.status!!
                progress(40 * mb, 40 * mb); seen += l.status!! // … the routing file is written now, nothing is reported
            })
        l.ensure(listOf("E10_N45.rd5"))
        assertEquals(50, seen[0].percent)
        assertFalse(seen[0].writing)
        assertTrue("downloaded, written now: no percent to show", seen[1].writing)
        dir.deleteRecursively()
    }

    @Test fun backgroundChip_installing() {
        val p = BackgroundProgress()
        val uis = (0 until 90).map { t -> p.update(BackgroundProgress.cellItems(mapOf(a to cell(t)), emptySet()) { 30 * mb }, 1, 0)!! }
        assertNoFrozenNumber("download chip", uis.map { it.percent.takeIf { _ -> it.hasPercent } })
        assertTrue(uis[15].hasPercent)
        assertEquals(50, uis[15].percent)
        assertFalse("joined in: the ring spins, no number", uis[45].hasPercent)
        assertEquals(LoadPhase.FINISHING, uis[45].phase)
        assertEquals("almost done", uis[45].doneText)
        assertEquals("50 % done", uis[15].doneText)
    }

    /** The server prepares an area: no time-based guess in the chip – it spins and the details say "being prepared". */
    @Test fun backgroundChip_preparing() {
        val ui = BackgroundProgress().update(BackgroundProgress.cellItems(mapOf(a to CellState.Building("base", 0.9, 5)), emptySet()) { 30 * mb }, 1, 0)!!
        assertFalse(ui.hasPercent)
        assertEquals("being prepared", ui.doneText)
    }

    @Test fun firstMapCard_installing() {
        val job = CellJob(listOf(a), null, CellWhy.HERE)
        val uis = (0 until 90).map { t ->
            FirstMapCard.of(job, mapOf(a to cell(t)), emptyMap(), viewWithoutDetail = true, setupOpen = false, plan = true,
                available = true) as FirstMapCard.Ui.Loading
        }
        assertNoFrozenNumber("first-map card", uis.map { it.percent })
        assertEquals(50, uis[15].percent)
        assertNull(uis[45].percent)
        assertEquals(LoadPhase.FINISHING, uis[45].phase)
    }

    @Test fun notification_installing() {
        assertNoFrozenNumber("notification", (0 until 90).map { t -> MapLoadService.percent(listOf(cell(t))) })
        assertEquals("50 % – you can put the phone aside", MapLoadService.text(listOf(cell(15))))
        assertEquals("Almost ready – you can put the phone aside", MapLoadService.text(listOf(cell(45))))
        assertEquals("Preparing – you can put the phone aside", MapLoadService.text(listOf(CellState.Building("base", 0.9, 5))))
    }

    /** The router's search is at the goal after 5 s, then works on for 60 s (a retry, the track is built): the bar. */
    @Test fun routeBar_searchAtTheGoalButNotDone() {
        val bar = WaitBar()
        val shares = (0 until 65 * 4).map { i -> val t = i / 4.0; bar.next(0, 1, if (t < 5) t / 5.0 * 0.98 else 0.98, i * 250L) }
        assertNoFrozenNumber("route bar (share of the bar in %)", (0 until 65).map { s -> shares[s * 4]?.let { (it * 100).toInt() } })
        assertTrue("moves with the search first", shares[8]!! in 0.3f..0.5f)
        assertNull("stands for ${WaitBar.STALL_MS} ms: the bar runs", shares[(5 + 4) * 4])
    }

    /** A second search in the same leg starts from the beginning: no bar held at the first one's 98 %. */
    @Test fun routeBar_retrySearchRunsThenMovesAgain() {
        val bar = WaitBar()
        var i = 0
        fun tick(leg: Double?) = bar.next(0, 1, leg, (i++) * 250L)
        repeat(8) { tick(0.9) }
        var last: Float? = 0f
        repeat(20) { k -> last = tick(k / 20.0 * 0.5) } // the retry, 5 s, still behind the first
        assertNull(last)
        assertEquals(0.95f, tick(0.95)!!, 0.0001f) // past the first search: a share again
    }

    /** All legs are routed; warnings, times and the reach search follow: the bar must not stand full. */
    @Test fun routeBar_allLegsDone() {
        val bar = WaitBar()
        assertNoFrozenNumber("route bar after the last leg", (0 until 30).map { t -> bar.next(3, 3, null, t * 1000L)?.let { (it * 100).toInt() } })
        assertNull(bar.next(3, 3, null, 0))
        assertEquals("Checking the route…", PlanStatus.calculating(3 to 3))
        assertEquals("Checking the route…", PlanStatus.calculating(1 to 1))
        assertEquals("Calculating section 3 of 3…", PlanStatus.calculating(2 to 3))
    }
}

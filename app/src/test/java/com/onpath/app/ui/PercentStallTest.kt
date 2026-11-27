package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import com.onpath.app.cells.MapCells
import com.onpath.app.cells.MapLoadService
import com.onpath.app.cells.NetworkStatus
import com.onpath.app.data.ProgressMemory
import com.onpath.app.data.RoutingDataLoader
import com.onpath.app.domain.ActionPercent
import com.onpath.app.domain.LoadStep
import com.onpath.app.domain.StepShares
import com.onpath.app.domain.WaitBar
import com.onpath.app.domain.WaitStepState
import com.onpath.app.ui.progress.RouteWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Owner 2026-11-18: „erst ganz schnell und bleibt dann bei 98 % stehen – da denkt jeder, es hängt sich auf“; 2026-11-19:
 * „wir brauchen einen realistischeren Fortschritt“ – ONE bar 0–100 over all steps, each step its real share, never back,
 * never standing. Every percent the phone shows, through every step of a load: the server prepares (its time left), the
 * bytes download, the map is joined in (the pieces in – before, it reported nothing and the number stood there), the
 * routing file is rewritten (the bytes written), the router's legs and the check. What it guards against (the field and
 * the interim forms before this design): the window stood at 98–99 % while the map was joined in, then showed no number
 * at all; the preparing step had no number; the route bar stood at 98 % / full. Written for the new design – it was
 * not run against the code before it (the cases a review found later: [WaitPromiseTest], red on fcc6cdbf1).
 */
class PercentStallTest {
    private val mb = 1_048_576L
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)

    /** One value per second. No number may stand longer than [maxS] s, and none may go back. */
    private fun assertMovesOn(what: String, shown: List<Int>, maxS: Int = 10) {
        var run = 0
        for (i in 1 until shown.size) {
            assertTrue("$what: back from ${shown[i - 1]} to ${shown[i]} (second $i): $shown", shown[i] >= shown[i - 1])
            run = if (shown[i] == shown[i - 1] && shown[i] < 100) run + 1 else 0
            assertTrue("$what: ${shown[i]} % stands for more than $maxS s (second $i): $shown", run <= maxS)
        }
    }

    /**
     * A first map of 30 MB: the server prepares it for 60 s (its estimate 60 s), it downloads for 30 s, it is joined in for
     * 30 s – the store reporting the pieces in; the routing data (10 MB) loads beside it and its file is rewritten.
     */
    private fun cell(t: Int): CellState = when {
        t < 10 -> CellState.Queued(1, 60)
        t < 60 -> CellState.Building("base", (t - 10) / 50.0, 60 - t)
        t < 90 -> CellState.Downloading((t - 60) * mb, 30 * mb)
        t < 120 -> CellState.Installing
        else -> CellState.Ready("v")
    }
    private fun joining(t: Int): MapCells.Joining? = if (t in 92 until 120) MapCells.Joining(setOf(a), (t - 92) * 10 * mb, 280 * mb) else null
    private fun routing(t: Int): RoutingDataLoader.Status? = when {
        t < 20 -> RoutingDataLoader.Status(listOf("E10_N45.rd5:E11_N46"), RoutingDataLoader.Phase.LOADING, (t / 2) * mb, 10 * mb, mb / 2.0)
        t < 26 -> RoutingDataLoader.Status(listOf("E10_N45.rd5:E11_N46"), RoutingDataLoader.Phase.LOADING, 10 * mb, 10 * mb, mb / 2.0,
            writing = true, written = (t - 20) / 6.0)
        else -> null
    }

    @Test fun waitWindow_everyStepMovesOn() {
        val w = MapWait()
        val uis = (0 until 120).map { t -> w.update(listOf(a), mapOf(a to cell(t)), emptyMap(), routing(t), joining(t), t * 1000L)!! }
        assertMovesOn("wait window", uis.map { it.percent })
        // each step its own share (prepare 30 / download 50 / join 20) and its own rows
        assertEquals(MapLoadUi.Kind.PREPARING, uis[30].kind)
        // the routing data (part of the loading step) came in beside it: its bytes count – the map is still prepared
        assertTrue("preparing, the routing data in: ${uis[30].percent}", uis[30].percent in 30..60)
        assertEquals(WaitStepState.NOW, uis[30].steps.first().state)
        // half the map's bytes; the routing data is in already (its download and its file's rewrite)
        assertEquals(MapLoadUi.Kind.LOADING, uis[75].kind)
        assertTrue("half the bytes: ${uis[75].percent}", uis[75].percent in 65..80)
        assertEquals(MapLoadUi.Kind.FINISHING, uis[105].kind)
        assertTrue("joined in, the pieces counted: ${uis[105].percent}", uis[105].percent in 85..99)
        assertTrue("never 100 before it is there", uis.all { it.percent <= 99 })
        assertEquals(listOf(WaitStepState.DONE, WaitStepState.DONE, WaitStepState.NOW), uis[105].steps.map { it.state })
        assertEquals("a number all the way", true, uis.all { it.percent >= 0 })
        assertEquals("there: one last picture at 100 %", 100, w.update(listOf(a), mapOf(a to cell(120)), emptyMap(), null, null, 120_000)!!.percent)
        assertNull("then the window goes", w.update(listOf(a), mapOf(a to cell(121)), emptyMap(), null, null, 121_000))
        // the times each step took, for the learned shares
        val d = w.durations()
        assertEquals(setOf(LoadStep.PREPARE, LoadStep.DOWNLOAD, LoadStep.JOIN), d.keys)
    }

    /**
     * The join cannot report (a batch waiting for its others before the store is written): its time estimated from the
     * map's size (the 400 MB store is rewritten with the new area) at this phone's measured join speed, kept inside its
     * share, still moving.
     */
    @Test fun waitWindow_joinWithoutReportsIsEstimatedInsideItsShare() {
        MapWait.joinBytesPerS = 20.0 * mb
        val w = MapWait()
        val installed = mapOf(b to 400 * mb)
        val uis = (0 until 45).map { t ->
            val s = if (t < 20) CellState.Downloading(t * mb, 20 * mb) else CellState.Installing
            w.update(listOf(a), mapOf(a to s), installed, null, null, t * 1000L)!!
        }
        assertMovesOn("wait window, join not reported", uis.map { it.percent })
        assertTrue(uis.all { it.percent <= 99 })
        assertTrue("in the join's share: ${uis[40].percent}", uis[40].percent in 80..99)
    }

    /** A step that goes back – a damaged download loaded again, a new server estimate – never moves the bar back. */
    @Test fun waitWindow_aStepThatGoesBackDoesNotMoveTheBar() {
        val w = MapWait()
        val states = listOf(CellState.Building("base", 0.9, 5), CellState.Downloading(8 * mb, 10 * mb), CellState.Downloading(0, 10 * mb),
            CellState.Queued(2, 300), CellState.Downloading(2 * mb, 10 * mb))
        val shown = states.mapIndexed { i, s -> w.update(listOf(a), mapOf(a to s), emptyMap(), null, null, i * 1000L)!!.percent }
        for (i in 1 until shown.size) assertTrue("never back: $shown", shown[i] >= shown[i - 1])
    }

    /** Several regions: one bar over all of them; one there counts in full, the others by their steps. */
    @Test fun waitWindow_severalRegionsOneBar() {
        val w = MapWait()
        val first = w.update(listOf(a, b), mapOf(a to CellState.Downloading(5 * mb, 10 * mb), b to CellState.Queued(1, 60)), emptyMap(), null, null, 0)!!
        assertEquals(2, first.regions)
        val second = w.update(listOf(a, b), mapOf(a to CellState.Ready("v"), b to CellState.Building("base", 0.5, 30)), emptyMap(), null, null, 20_000)!!
        assertTrue(second.percent >= first.percent)
        assertEquals(1, second.regionsDone)
        assertEquals(MapLoadUi.Kind.PREPARING, second.kind)
    }

    /** Only routing data missing: its bytes, then its file's rewrite – the bytes written, two steps, never a 4th row. */
    @Test fun waitWindow_routingRewriteReportsBytesWritten() {
        val w = MapWait()
        val shown = (0 until 40).map { t -> w.update(emptyList(), emptyMap(), emptyMap(), routing(t / 2), null, t * 500L)!!.percent }
        assertMovesOn("routing data", shown.chunked(2).map { it.last() })
        val writing = w.update(emptyList(), emptyMap(), emptyMap(), routing(23), null, 25_000)!!
        assertEquals(MapLoadUi.Kind.FINISHING, writing.kind)
        assertEquals(2, writing.steps.size)
    }

    /** The loader tells the write phase: every byte reported, the download call not back yet. */
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
                progress(40 * mb, 40 * mb); seen += l.status!! // … the routing file is written now
            })
        l.ensure(listOf("E10_N45.rd5"))
        assertEquals(50, seen[0].percent)
        assertFalse(seen[0].writing)
        assertTrue("downloaded, written now: the join step", seen[1].writing)
        dir.deleteRecursively()
    }

    /** Form B: one number over several loads (a multi-day tour's areas + routing data), never back; the notification says it. */
    @Test fun besideSign_oneNumberOverTheWholeAction() {
        val action = ActionPercent()
        val p = BackgroundProgress(action)
        val shares = StepShares.DEFAULT
        fun at(t: Int): List<ActionPercent.Item> {
            val st = mutableMapOf<CellId, CellState>()
            if (t < 120) st[a] = cell(t)
            if (t in 30 until 150) st[b] = cell(t - 30)
            return BackgroundProgress.cellItems(st, emptySet(), shares = shares, joining = joining(t)) { 30 * mb } +
                listOfNotNull(routing(t)?.let { BackgroundProgress.routingItem(it, shares) })
        }
        val shown = (0 until 149).map { t -> p.update(at(t), 2, 1)!!.percent }
        for (i in 1 until shown.size) assertTrue("never back: $shown", shown[i] >= shown[i - 1])
        assertTrue(shown.last() in 90..99)
        assertNull("all there: the sign goes", p.update(emptyList(), 0, 0))
        assertEquals("loading 34 %", MapLoadService.text(34))
        assertEquals("Preparing", MapLoadService.text(null))
    }

    /**
     * The router's search is at the goal after 5 s, then works on for 60 s (a retry, the track is built): the bar moves on
     * – a whole percent now and then once the leg is over its share (every fifth of the wait so far), the line says so.
     */
    @Test fun routeWindow_searchAtTheGoalButNotDone() {
        val rw = RouteWait(ProgressMemory.InMemory())
        val bar = WaitBar()
        val uis = (0 until 65).map { t ->
            val share = bar.next(0, 2, if (t < 5) t / 5.0 * 0.98 else 0.98)
            rw.update(true, 0 to 2, share, null, t * 1000L, gen = 1)!!
        }
        val shown = uis.map { it.percent }
        assertMovesOn("route window", shown, maxS = 15)
        assertTrue("a leg that overruns borrows slowly from the rest: ${shown.last()}", shown.last() < 70)
        assertEquals("taking a bit longer…", uis.last().window.line)
    }

    /** Every leg routed; the times, warnings and the reach search follow: „prüfen“ is its own step – no bar standing full. */
    @Test fun routeWindow_allLegsDoneThenTheCheck() {
        val memory = ProgressMemory.InMemory()
        val rw = RouteWait(memory)
        // 3 legs in 18 s, then the check for 3 s
        val ui = (0 until 21).map { t -> rw.update(true, if (t < 18) (t / 6) to 3 else 3 to 3, if (t < 18) (t / 18f) else null, null, t * 1000L, gen = 1)!! }
        assertMovesOn("route window, legs and check", ui.map { it.percent })
        assertEquals(WaitStepState.NOW, ui[19].steps.last().state)
        assertTrue(ui.all { it.percent <= 99 })
        assertTrue("the check's part: ${ui[19].percent}", ui[19].percent >= 89)
        assertEquals("3 legs + the check, 3 rows shown at most", 4, ui[19].steps.size)
        // the run ends: the check took longer than its share – the share grows for the next run
        assertNull(rw.update(false, null, null, null, 21_000, gen = 1))
        assertTrue("learned: ${memory.routeCheck}", memory.routeCheck > com.onpath.app.domain.RouteShares.DEFAULT_CHECK)
    }

    /** A second search in the same leg starts from the beginning: the legs' share never goes back. */
    @Test fun routeBar_retrySearchNeverBack() {
        val bar = WaitBar()
        repeat(8) { bar.next(0, 1, 0.9) }
        assertEquals(0.9f, bar.next(0, 1, 0.2)!!, 0.0001f)
        assertEquals(0.95f, bar.next(0, 1, 0.95)!!, 0.0001f)
        assertEquals(1f, bar.next(1, 1, null)!!, 0.0001f)
        assertNull("no legs to route", WaitBar().next(0, 0, 0.5))
    }
}

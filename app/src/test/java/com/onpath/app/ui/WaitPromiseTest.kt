package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import com.onpath.app.data.ProgressMemory
import com.onpath.app.domain.LoadStep
import com.onpath.app.domain.RouteShares
import com.onpath.app.domain.StepClock
import com.onpath.app.ui.progress.RouteWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The owner's promise for the wait window (2026-11-18/19: never back, never standing), the cases a review of the first
 * build found broken (red on fcc6cdbf1, the cases marked so in the commit).
 */
class WaitPromiseTest {
    private val mb = 1_048_576L
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)

    /** A recalc while a run is on (an edit, an activity change, routing data arriving): the bar and rows start afresh. */
    @Test fun routeWindow_aNewRunStartsAfresh() {
        val memory = ProgressMemory.InMemory()
        val rw = RouteWait(memory)
        for (t in 0 until 10) rw.update(true, if (t < 5) 0 to 1 else 1 to 1, if (t < 5) t / 5f else null, null, t * 1000L, gen = 1)
        // the plan was changed: run 2 of 4 legs, routing stays on
        val again = rw.update(true, 0 to 4, 0.05f, null, 10_000, gen = 2)!!
        assertTrue("starts afresh: ${again.percent}", again.percent < 20)
        assertEquals("the first leg runs", 0, again.steps.indexOfFirst { it.state == com.onpath.app.domain.WaitStepState.NOW })
        for (t in 11 until 40) rw.update(true, 0 to 4, 0.05f + t / 100f, null, t * 1000L, gen = 2)
        rw.update(false, null, null, null, 40_000, gen = 2)
        assertTrue("run 2 had no check: nothing wrong learned – ${memory.routeCheck}", memory.routeCheck <= RouteShares.DEFAULT_CHECK + 1e-9)
    }

    /** A check far longer than its share: whole percents keep coming, and the time line says it takes longer. */
    @Test fun routeWindow_aLongCheckKeepsMovingAndSaysSo() {
        val rw = RouteWait(ProgressMemory.InMemory())
        val uis = (0 until 300).map { t -> rw.update(true, if (t < 6) 0 to 1 else 1 to 1, if (t < 6) t / 6f else null, null, t * 1000L, gen = 1)!! }
        val shown = uis.map { it.percent }
        for (i in 1 until shown.size) assertTrue("never back: $shown", shown[i] >= shown[i - 1])
        assertStandsAtMost("route window, long check", shown.take(120), 30)
        assertTrue(shown.all { it <= 99 })
        assertEquals("the window shows how long it runs, no number (owner 2026-11-27)", "running for 3:20", uis[200].window.line)
        // the step bar (owner 2026-11-27 evening): pieces, still no number
        assertTrue(uis[200].window.bar && uis[200].window.pieces >= 1)
    }

    /** Owner 2026-11-27: the run's estimate on this phone beside the time it runs – only for its own run and above 5 s. */
    @Test fun routeWindow_showsTheRunsEstimate() {
        val est = com.onpath.app.ui.progress.RouteEstimate
        try {
            est.set(7, 70.0)
            val rw = RouteWait(ProgressMemory.InMemory())
            rw.update(true, 0 to 1, null, null, 0L, gen = 7)
            assertEquals("running for 0:42 · about 1–2 min", rw.update(true, 0 to 1, null, null, 42_000L, gen = 7)!!.window.line)
            assertEquals("another run: no estimate", "running for 0:00", RouteWait(ProgressMemory.InMemory()).update(true, 0 to 1, null, null, 0L, gen = 8)!!.window.line)
            est.set(9, 4.0)
            assertEquals("5 s or less: none", "running for 0:00", RouteWait(ProgressMemory.InMemory()).update(true, 0 to 1, null, null, 0L, gen = 9)!!.window.line)
        } finally { est.set(-1, null) }
    }

    /**
     * Owner 2026-11-19 (in the planner, München → Hilden): „noch etwa 5 Sekunden“, then minutes. The same rule on the
     * phone: a leg the router reports nothing of promises no time – the bar creeps, the time left is not read off the creep.
     */
    @Test fun routeWindow_aLegWithoutAnyProgressPromisesNoTime() {
        val rw = RouteWait(ProgressMemory.InMemory())
        val lines = (0 until 600).map { t -> rw.update(true, 0 to 1, null, null, t * 1000L, gen = 1)!!.window.line }.toSet()
        assertTrue("no time is promised: $lines", lines.all { it?.startsWith("running for ") == true })
    }

    /** A quick first leg, then a long one the router reports nothing of: no „10 seconds“ counting up or down – no time. */
    @Test fun routeWindow_aLongLegAfterAQuickOneWithoutProgressPromisesNoTime() {
        val rw = RouteWait(ProgressMemory.InMemory())
        val lines = (0 until 300).map { t -> rw.update(true, (if (t < 3) 0 else 1) to 2, null, null, t * 1000L, gen = 1)!!.window.line }.toSet()
        assertTrue("no time is promised: $lines", lines.all { it?.startsWith("running for ") == true })
    }

    /** The router reported 30 % in 10 s and then nothing for minutes: the time left never counts down while nothing happens. */
    @Test fun routeWindow_aLegThatStopsReportingNeverCountsDownByItself() {
        val rw = RouteWait(ProgressMemory.InMemory())
        val uis = (0 until 300).map { t -> rw.update(true, 0 to 1, minOf(t, 10) * 0.03f, null, t * 1000L, gen = 1)!! }
        val left = uis.drop(11).filter { !it.late }.mapNotNull { it.secondsLeft }
        assertTrue("some time was said", left.isNotEmpty())
        assertTrue("never less while nothing is reported: $left", left.zipWithNext().all { (a, b) -> b >= a })
    }

    /** A join estimated at 20 s that takes 300 s without reporting: no 99 % for minutes, no frozen "5 seconds". */
    @Test fun waitWindow_aLongLastStepKeepsMovingAndSaysSo() {
        MapWait.joinBytesPerS = 20.0 * mb
        val w = MapWait()
        val uis = (0 until 330).map { t ->
            val s = if (t < 30) CellState.Downloading(t * mb, 30 * mb) else CellState.Installing
            w.update(listOf(a), mapOf(a to s), mapOf(b to 370 * mb), null, null, t * 1000L)!!
        }
        val shown = uis.map { it.percent }
        for (i in 1 until shown.size) assertTrue("never back: $shown", shown[i] >= shown[i - 1])
        assertTrue("not at 99 after 2 minutes of joining: ${shown[150]}", shown[150] < 99)
        assertStandsAtMost("long join", shown.take(200), 45)
        assertEquals("taking a bit longer…", uis[200].line)
    }

    /** Ten minutes offline count into no step: no "about 25 minutes left" afterwards, nothing learned from it. */
    @Test fun pausedTimeIsNoStepTime() {
        val c = StepClock()
        c.at(LoadStep.PREPARE, 0); c.at(LoadStep.PREPARE, 1_000); c.at(LoadStep.PREPARE, 601_000); c.at(LoadStep.DOWNLOAD, 602_000)
        assertTrue("the gap is no prepare time: ${c.durations()}", c.durations().getValue(LoadStep.PREPARE) < 5_000)
        val w = MapWait()
        w.update(listOf(a), mapOf(a to CellState.Downloading(2 * mb, 20 * mb)), emptyMap(), null, null, 0)
        w.update(listOf(a), mapOf(a to CellState.Downloading(4 * mb, 20 * mb)), emptyMap(), null, null, 2_000)
        for (t in 3..600) w.update(listOf(a), mapOf(a to CellState.Offline("x")), emptyMap(), null, null, t * 1000L)
        w.update(listOf(a), mapOf(a to CellState.Downloading(5 * mb, 20 * mb)), emptyMap(), null, null, 601_000)
        val back = w.update(listOf(a), mapOf(a to CellState.Downloading(6 * mb, 20 * mb)), emptyMap(), null, null, 603_000)!!
        assertTrue("time left after the offline time: ${back.secondsLeft}", (back.secondsLeft ?: 0) < 120)
    }

    /** Everything arrived: the window ends on 100 %, not on the 97–99 % it lingers with. */
    @Test fun theWindowEndsAt100() {
        val w = MapWait()
        w.update(listOf(a), mapOf(a to CellState.Downloading(5 * mb, 10 * mb)), emptyMap(), null, null, 0)
        w.update(listOf(a), mapOf(a to CellState.Installing), emptyMap(), null, null, 1_000)
        val last = w.update(listOf(a), mapOf(a to CellState.Ready("v")), emptyMap(), null, null, 2_000)
        assertNotNull("one last picture at 100 %", last)
        assertEquals(100, last!!.percent)
    }

    /** The window goes while its loads go on (A turns into B): the sign goes on from the window's percent, never below. */
    @Test fun handoverToTheSignNeverGoesBack() {
        val action = com.onpath.app.domain.ActionPercent()
        val waits = MapWaits({ com.onpath.app.domain.StepShares.DEFAULT }, learn = {}, action = action)
        var shown = 0
        for (t in 0 until 40) shown = waits.tick("modal", t * 1000L) { w ->
            w.update(listOf(a), mapOf(a to CellState.Downloading(t * mb, 40 * mb)), emptyMap(), null, null, t * 1000L) }!!.percent
        // the sign's loads: this area and a big one beside it – by bytes only 27 %
        fun items(f: Double) = listOf(com.onpath.app.domain.ActionPercent.Item("cell:a", 40 * mb, f),
            com.onpath.app.domain.ActionPercent.Item("cell:b", 100 * mb, 0.1))
        action.update(items(0.6))
        waits.hide(40_000)
        val sign = (0 until 10).map { i -> action.update(items(0.6 + i * 0.04))!! }
        assertTrue("the sign starts at the window's $shown: $sign", sign.first() >= shown)
        for (i in 1 until sign.size) assertTrue("and moves on, never back: $sign", sign[i] >= sign[i - 1])
        assertTrue(sign.last() > sign.first())
    }

    /** The first map's wait goes away for an offline blip and comes back for the same job: its bar where it was, not 0. */
    @Test fun theSameJobComesBackWhereItWas() {
        val waits = MapWaits({ com.onpath.app.domain.StepShares.DEFAULT }, learn = {}, action = com.onpath.app.domain.ActionPercent())
        val job = "first" to setOf(a)
        var before = 0
        for (t in 0 until 60) before = waits.tick(job, t * 1000L) { w ->
            w.update(listOf(a), mapOf(a to CellState.Queued(1, 120)), emptyMap(), null, null, t * 1000L) }!!.percent
        for (t in 60 until 70) waits.tick(null, t * 1000L) { null } // offline: the window is away, the card says so
        val after = waits.tick(job, 70_000) { w -> w.update(listOf(a), mapOf(a to CellState.Queued(1, 60)), emptyMap(), null, null, 70_000) }!!
        assertTrue("$before → ${after.percent}", after.percent >= before && before > 5)
    }

    /** A failing update: the last picture stays (the map still loads), nothing is learned. */
    @Test fun aFailingUpdateKeepsTheWindow() {
        var learned = false
        val waits = MapWaits({ com.onpath.app.domain.StepShares.DEFAULT }, learn = { learned = true }, action = com.onpath.app.domain.ActionPercent())
        val ok = waits.tick("modal", 0) { w -> w.update(listOf(a), mapOf(a to CellState.Downloading(mb, 10 * mb)), emptyMap(), null, null, 0) }
        val broken = waits.tick("modal", 1_000) { throw IllegalStateException("states unreadable") }
        assertEquals(ok, broken)
        assertTrue(!learned)
    }

    /** A wait that paused teaches nothing (its times are no measure of the steps). */
    @Test fun aPausedWaitTeachesNothing() {
        val w = MapWait()
        w.update(listOf(a), mapOf(a to CellState.Downloading(mb, 10 * mb)), emptyMap(), null, null, 0)
        w.update(listOf(a), mapOf(a to CellState.Offline("x")), emptyMap(), null, null, 1_000)
        w.update(listOf(a), mapOf(a to CellState.Downloading(5 * mb, 10 * mb)), emptyMap(), null, null, 2_000)
        assertTrue(!w.learnable)
    }

    private fun assertStandsAtMost(what: String, shown: List<Int>, maxS: Int) {
        var run = 0
        for (i in 1 until shown.size) {
            run = if (shown[i] == shown[i - 1] && shown[i] < 99) run + 1 else 0
            assertTrue("$what: ${shown[i]} % stands for more than $maxS s (second $i): $shown", run <= maxS)
        }
    }

    /**
     * Owner's Pixel 2026-11-27 (Hilden → München): the map window ended, the route's opened at 0, its time limit asked
     * „Weiter warten?“ and a third window began at 6 %. Now: „Karte geladen ✓“ first, and a second try within a minute
     * goes on with the same time.
     */
    @Test fun routeWindow_followsTheMapAndASecondTryGoesOn() {
        com.onpath.app.ui.progress.WaitChain.mapDoneAt = 100_000L
        val rw = RouteWait(ProgressMemory.InMemory())
        val first = rw.update(true, 0 to 1, null, null, 101_000L, gen = 1)!!
        assertTrue("the map was just loaded", first.afterMap)
        assertEquals("Map loaded", first.window.steps.first().label)
        val at45 = rw.update(true, 0 to 1, null, null, 146_000L, gen = 1)!!
        assertEquals("running for 0:45", at45.window.line)
        assertNull(rw.update(false, null, null, null, 147_000L, gen = 1)) // the time limit: the run ends, the question asks
        val again = rw.update(true, 0 to 1, null, null, 157_000L, gen = 2)!!
        assertEquals("one wait: the time goes on", "running for 0:56", again.window.line)
        assertTrue(again.afterMap)
        assertNull(rw.update(false, null, null, null, 160_000L, gen = 2))
        val later = rw.update(true, 0 to 1, null, null, 400_000L, gen = 3)!!
        assertEquals("a later route is a new wait", "running for 0:00", later.window.line)
        assertFalse(later.afterMap)
        com.onpath.app.ui.progress.WaitChain.mapDoneAt = -1L
    }

    /** Captain / VISION 2026-11-27: finished pieces are real legs; the running one fills by the router's share, else no creep. */
    @Test fun stepBarNeverMakesUpProgress() {
        val rw = RouteWait(ProgressMemory.InMemory())
        val quiet = rw.update(true, 3 to 13, null, null, 10_000L, gen = 1)!!.window
        assertEquals(13, quiet.shownPieces)
        assertEquals("only the finished legs are filled", 3f, quiet.filledPieces, 0f)
        assertEquals("the running piece: Android's indeterminate bar", 3, quiet.runningPiece)
        val told = rw.update(true, 3 to 13, 0.3f, null, 11_000L, gen = 1)!!.window
        assertEquals(null, told.runningPiece)
        assertTrue("the router's share fills the running piece", told.filledPieces > 3f)
        val many = RouteWait(ProgressMemory.InMemory()).update(true, 30 to 40, null, null, 0L, gen = 2)!!.window
        assertEquals(20, many.shownPieces); assertEquals(15, many.doneShown)
    }

    /** Review step 6, finding 1: the filled pieces never run behind the finished legs, and „prüfen“ shows them all full. */
    @Test fun filledPiecesAreRealLegsAndAllFullWhileChecking() {
        val mem = ProgressMemory.InMemory().apply { routeCheck = 0.3 }
        val rw = RouteWait(mem)
        for ((t, share) in listOf(1_000L to 0.05f, 2_000L to 0.4f, 3_000L to 0.62f, 4_000L to 0.9f)) {
            val done = (share * 10).toInt()
            val w = rw.update(true, done to 10, share, null, t, gen = 3)!!.window
            assertTrue("t=$t: ${w.filledPieces} ≥ ${w.doneShown}", w.filledPieces >= w.doneShown)
            assertTrue("t=$t: never past the router's own share", w.filledPieces <= share * 10 + 1e-3f)
        }
        val checking = rw.update(true, 10 to 10, null, null, 5_000L, gen = 3)!!.window
        assertEquals("prüfen: every leg is routed – all pieces full", checking.shownPieces.toFloat(), checking.filledPieces, 0f)
    }
}

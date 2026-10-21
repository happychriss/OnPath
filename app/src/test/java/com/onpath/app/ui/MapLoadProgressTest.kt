package com.onpath.app.ui

import com.onpath.app.cells.CellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One overall % for the map of the user's area (owner 2026-10-21): 0–80 preparing, 80–99 download, 100 done. */
class MapLoadProgressTest {
    private val mb = 1_048_576L

    @Test fun preparingMovesOnByElapsedTimeBetweenPolls() {
        val p = MapLoadProgress()
        val b = CellState.Building("base", 0.0, 100)
        assertEquals(0, p.update(b, 0).percent)
        val at25 = p.update(b, 25_000)
        assertEquals(20, at25.percent)                  // a quarter of the ETA → a quarter of 0–80
        assertEquals(75, at25.secondsLeft)
        assertEquals(40, p.update(b, 50_000).percent)
        assertEquals(MapLoadUi.Kind.PREPARING, at25.kind)
    }

    @Test fun neverReachesTheEndOfPreparingByItself() {
        val p = MapLoadProgress()
        val b = CellState.Building("base", 0.0, 60)
        p.update(b, 0)
        val late = p.update(b, 3_600_000)
        assertTrue(late.percent < 80)
        assertEquals("Less than a minute left", late.line)
    }

    @Test fun serverProgressAndNewEtaReAnchorWithoutGoingBack() {
        val p = MapLoadProgress()
        p.update(CellState.Queued(2, 200), 0)
        val before = p.update(CellState.Queued(2, 200), 50_000).percent   // 20
        // the server says building 10 % (= 8 overall), lower than shown: the bar does not go back
        val after = p.update(CellState.Building("base", 0.1, 100), 51_000).percent
        assertTrue(after >= before)
        // and moves on from there towards 80 with the new ETA
        assertTrue(p.update(CellState.Building("base", 0.1, 100), 101_000).percent > after)
        // a server progress above the shown value jumps forward
        assertEquals(64, p.update(CellState.Building("tiles", 0.8, 20), 102_000).percent)
    }

    @Test fun downloadIsEightyToNinetyNineByBytes() {
        val p = MapLoadProgress()
        p.update(CellState.Building("base", 0.5, 30), 0)
        assertEquals(80, p.update(CellState.Downloading(0, 0), 1_000).percent)
        assertEquals(80, p.update(CellState.Downloading(0, 20 * mb), 2_000).percent)
        val half = p.update(CellState.Downloading(10 * mb, 20 * mb), 7_000)
        assertEquals(89, half.percent)
        assertEquals(MapLoadUi.Kind.LOADING, half.kind)
        assertEquals(6, half.secondsLeft)                // 10 MB in 6 s (since the first byte count) → 10 MB more ≈ 6 s
        assertEquals(99, p.update(CellState.Downloading(20 * mb, 20 * mb), 8_000).percent)
        assertEquals(99, p.update(CellState.Installing, 9_000).percent)
        assertEquals(100, p.update(CellState.Ready("v"), 10_000).percent)
    }

    @Test fun monotonicOverAWholeRun() {
        val p = MapLoadProgress()
        val seq = listOf(CellState.Requested, CellState.Queued(3, null), CellState.Queued(1, 120), CellState.Building("base", 0.2, 90),
            CellState.Building("base", 0.1, 200), CellState.Offline("No connection"), CellState.Building("tiles", 0.9, 5),
            CellState.Downloading(0, 0), CellState.Downloading(5 * mb, 10 * mb), CellState.Offline("x"),
            CellState.Downloading(5 * mb, 10 * mb), CellState.Installing)
        var t = 0L; var last = -1
        for (s in seq) for (k in 0 until 5) {
            val pc = p.update(s, t).percent
            assertTrue("$s at $t: $pc < $last", pc >= last)
            assertTrue(pc < 100)
            last = pc; t += 7_000
        }
    }

    @Test fun offlinePausesAndSaysWhy() {
        val p = MapLoadProgress()
        p.update(CellState.Building("base", 0.5, 30), 0)
        val off = p.update(CellState.Offline("No connection"), 10_000)
        assertEquals(MapLoadUi.Kind.OFFLINE, off.kind)
        assertEquals(40, off.percent)
        assertEquals(40, p.update(CellState.Offline("No connection"), 60_000).percent)
        assertEquals(MapLoadUi.OFFLINE_TEXT, off.line)
        assertTrue(off.paused)
    }

    @Test fun failureIsPlain() {
        val f = MapLoadProgress().update(CellState.Failed("This area could not be prepared (tippecanoe exit 1).", true), 0)
        assertEquals(MapLoadUi.Kind.FAILED, f.kind)
        assertTrue(f.retryable)
        assertTrue(!f.line!!.contains("tippecanoe"))
    }

    @Test fun timeLeftWords() {
        assertEquals("Less than a minute left", MapLoadUi.timeLeft(40))
        assertEquals("About 1 minute left", MapLoadUi.timeLeft(70))
        assertEquals("About 6 minutes left", MapLoadUi.timeLeft(350))
        assertNull(MapLoadUi(MapLoadUi.Kind.LOADING, 80, null).line)
    }
}

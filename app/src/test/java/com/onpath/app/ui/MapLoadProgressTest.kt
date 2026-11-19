package com.onpath.app.ui

import com.onpath.app.cells.CellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map of the user's area on its way, each phase its own progress (owner 2026-11-18, [PercentStallTest]): preparing =
 * the server's time left, no %; download = the real bytes; joined in = neither.
 */
class MapLoadProgressTest {
    private val mb = 1_048_576L

    @Test fun preparingCountsTheServersTimeDownAndHasNoPercent() {
        val p = MapLoadProgress()
        val b = CellState.Building("base", 0.0, 100)
        val start = p.update(b, 0)
        assertEquals(MapLoadUi.Kind.PREPARING, start.kind)
        assertFalse(start.hasPercent)
        assertEquals(100, start.secondsLeft)
        assertEquals(75, p.update(b, 25_000).secondsLeft)
        // the server is slower than it said: the line stays at "less than a minute", nothing is made up
        assertEquals("Less than a minute left", p.update(b, 3_600_000).line)
        // a new estimate from the server counts from now
        assertEquals(200, p.update(CellState.Building("tiles", 0.5, 200), 3_601_000).secondsLeft)
    }

    @Test fun downloadIsTheRealBytes() {
        val p = MapLoadProgress()
        p.update(CellState.Building("base", 0.5, 30), 0)
        assertEquals(0, p.update(CellState.Downloading(0, 0), 1_000).percent)
        assertEquals(0, p.update(CellState.Downloading(0, 20 * mb), 2_000).percent)
        val half = p.update(CellState.Downloading(10 * mb, 20 * mb), 7_000)
        assertEquals(50, half.percent)
        assertEquals(MapLoadUi.Kind.LOADING, half.kind)
        assertTrue(half.hasPercent)
        assertEquals(6, half.secondsLeft)                // 10 MB in 6 s (since the first byte count) → 10 MB more ≈ 6 s
        assertEquals(100, p.update(CellState.Downloading(20 * mb, 20 * mb), 8_000).percent)
        // joined into the map: no number, no time left
        val joining = p.update(CellState.Installing, 9_000)
        assertEquals(MapLoadUi.Kind.FINISHING, joining.kind)
        assertFalse(joining.hasPercent)
        assertNull(joining.line)
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
            last = pc; t += 7_000
        }
    }

    @Test fun offlinePausesAndSaysWhy() {
        val p = MapLoadProgress()
        p.update(CellState.Downloading(4 * mb, 10 * mb), 0)
        val off = p.update(CellState.Offline("No connection"), 10_000)
        assertEquals(MapLoadUi.Kind.OFFLINE, off.kind)
        assertEquals(40, off.percent)
        assertEquals(40, p.update(CellState.Offline("No connection"), 60_000).percent)
        assertEquals(MapLoadUi.OFFLINE_TEXT, off.line)
        assertTrue(off.paused)
        assertTrue("the bar stands, grey, with its number – the line says why", off.hasPercent)
    }

    @Test fun failureIsPlain() {
        val f = MapLoadProgress().update(CellState.Failed("This area could not be prepared (tippecanoe exit 1).", true), 0)
        assertEquals(MapLoadUi.Kind.FAILED, f.kind)
        assertTrue(f.retryable)
        assertTrue(!f.line!!.contains("tippecanoe"))
    }

    // ------------------------------------------------------------------- several regions (owner: "N regions · about X MB")

    private val a = com.onpath.app.cells.CellId(543, 361)
    private val b = com.onpath.app.cells.CellId(544, 361)

    @Test fun regionsAndSizeOverAll() {
        val progress = mutableMapOf<com.onpath.app.cells.CellId, MapLoadProgress>()
        val ui = MapLoads.aggregate(listOf(a, b), mapOf(a to CellState.Downloading(6 * mb, 12 * mb), b to CellState.Building("base", 0.0, 60)),
            emptyMap(), progress, 0, 0)!!
        assertEquals(2, ui.regions)
        assertEquals(12 * mb + MapLoads.ESTIMATED_REGION_BYTES, ui.bytes)
        assertEquals("2 map areas · about 21 MB", ui.totals)
        assertEquals(MapLoadUi.Kind.LOADING, ui.kind)       // something is downloading
        assertEquals(28, ui.percent)                         // 6 of about 21 MB – the real bytes
        assertEquals("1 map area · ≈ 9 MB", MapLoads.askText(1, MapLoads.ESTIMATED_REGION_BYTES))
    }

    @Test fun oneRegionThereCountsInFullAndAllThereEndsIt() {
        val progress = mutableMapOf<com.onpath.app.cells.CellId, MapLoadProgress>()
        val ui = MapLoads.aggregate(listOf(a, b), mapOf(a to CellState.Ready("v"), b to CellState.Downloading(0, 0)),
            mapOf(a to 10 * mb), progress, 0, 0)!!
        assertEquals(52, ui.percent)                         // 10 of about 19 MB
        assertEquals(1, ui.regionsDone)
        assertEquals("1 of 2 map areas · about 19 MB", ui.totals)
        assertEquals(10 * mb + MapLoads.ESTIMATED_REGION_BYTES, ui.bytes)
        assertNull(MapLoads.aggregate(listOf(a, b), mapOf(a to CellState.Ready("v"), b to CellState.Ready("v")), emptyMap(), progress, 90, 1))
    }

    /** All downloaded, joined into the map: the window says "almost ready" – never a 99 % that stands. */
    @Test fun allDownloadedIsFinishing() {
        val progress = mutableMapOf<com.onpath.app.cells.CellId, MapLoadProgress>()
        val ui = MapLoads.aggregate(listOf(a, b), mapOf(a to CellState.Ready("v"), b to CellState.Installing), mapOf(a to 10 * mb), progress, 0, 0)!!
        assertEquals(MapLoadUi.Kind.FINISHING, ui.kind)
        assertFalse(ui.hasPercent)
        // one still prepared on the server: that is what the window waits for
        val waits = MapLoads.aggregate(listOf(a, b), mapOf(a to CellState.Installing, b to CellState.Queued(1, 60)), emptyMap(), progress, 0, 0)!!
        assertEquals(MapLoadUi.Kind.PREPARING, waits.kind)
    }

    @Test fun neverBackwardsWhenARegionJoins() {
        val progress = mutableMapOf<com.onpath.app.cells.CellId, MapLoadProgress>()
        val one = MapLoads.aggregate(listOf(a), mapOf(a to CellState.Downloading(5 * mb, 10 * mb)), emptyMap(), progress, 0, 0)!!
        val two = MapLoads.aggregate(listOf(a, b), mapOf(a to CellState.Downloading(5 * mb, 10 * mb)), emptyMap(), progress, one.percent, 1000)!!
        assertEquals(one.percent, two.percent)
        assertTrue(two.percent < 100)
    }

    @Test fun failedOnlyWhenNothingElseIsGoingOn() {
        val progress = mutableMapOf<com.onpath.app.cells.CellId, MapLoadProgress>()
        val going = MapLoads.aggregate(listOf(a, b), mapOf(a to CellState.Failed("x"), b to CellState.Queued(1, 60)), emptyMap(), progress, 0, 0)!!
        assertEquals(MapLoadUi.Kind.PREPARING, going.kind)
        val failed = MapLoads.aggregate(listOf(a, b), mapOf(a to CellState.Failed("x"), b to CellState.Ready("v")), emptyMap(), progress, 0, 0)!!
        assertEquals(MapLoadUi.Kind.FAILED, failed.kind)
        assertTrue(failed.retryable)
        val offline = MapLoads.aggregate(listOf(a, b), mapOf(a to CellState.Offline("x"), b to CellState.Queued(1, 60)), emptyMap(), progress, 0, 0)!!
        assertEquals(MapLoadUi.Kind.OFFLINE, offline.kind)
    }

    @Test fun timeLeftWords() {
        assertEquals("Less than a minute left", MapLoadUi.timeLeft(40))
        assertEquals("About 1 minute left", MapLoadUi.timeLeft(70))
        assertEquals("About 6 minutes left", MapLoadUi.timeLeft(350))
        assertNull(MapLoadUi(MapLoadUi.Kind.LOADING, 80, null).line)
    }
}

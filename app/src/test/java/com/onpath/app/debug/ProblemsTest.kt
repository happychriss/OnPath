package com.onpath.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProblemsTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("problems").toFile()
        Problems.init(dir)
        Problems.clear()
    }

    @Test fun repeatsAreOneEntryWithCount() {
        repeat(5) { Problems.warn("map", "tile 14/8710/$it failed") }
        val e = Problems.snapshot().single()
        assertEquals(5, e.count)
        assertEquals("map", e.area)
    }

    @Test fun seriousIsAnnouncedOncePerRun() {
        Problems.error("db", "write failed")
        assertNotNull(Problems.newSerious.value)
        Problems.announcedSerious()
        Problems.error("db", "write failed")
        assertNull(Problems.newSerious.value)
        Problems.warn("map", "fallback used")
        assertNull(Problems.newSerious.value)
    }

    @Test fun levelOnlyGoesUp() {
        Problems.error("x", "boom")
        Problems.info("x", "boom")
        assertEquals(Problems.Level.ERROR, Problems.snapshot().single().level)
    }

    @Test fun cancellationIsNotAProblem() {
        Problems.warn("x", "cancelled", kotlinx.coroutines.CancellationException("job"))
        assertTrue(Problems.snapshot().isEmpty())
    }

    @Test fun crashIsPersistedAndReloaded() {
        Problems.report("app", "Crash in main", IllegalStateException("bad"), Problems.Level.CRASH)
        Problems.init(dir) // a new run reads the file
        val c = Problems.lastCrash(0)
        assertNotNull(c)
        assertTrue(c!!.detail!!.contains("IllegalStateException"))
    }

    /** Different "Hold: …" lines are never merged into one entry (first text, × 21) – details would be lost. */
    @Test fun traceLinesAreNeverMerged() {
        repeat(21) { Problems.trace("map", "Hold: nearest point 2 at ${10 + it} dp") }
        val ev = Problems.events()
        assertEquals(21, ev.size)
        assertEquals("Hold: nearest point 2 at 30 dp", ev.first().message) // newest first
        assertEquals("Hold: nearest point 2 at 10 dp", ev.last().message)
        assertTrue("traces are not problems", Problems.snapshot().isEmpty())
    }

    @Test fun traceKeepsTheLastFewInMemoryWithTheirOwnTime() {
        repeat(Problems.MAX_EVENTS + 20) { Problems.trace("map", "event $it") }
        val ev = Problems.events()
        assertEquals(Problems.MAX_EVENTS, ev.size)
        assertEquals("event ${Problems.MAX_EVENTS + 19}", ev.first().message)
        assertEquals("event 20", ev.last().message)
        assertTrue(ev.all { it.time > 0 })
    }

    /**
     * Owner: keep far more events, by size and age. 50 000 events are cheap for the caller (queued, written
     * off-thread), the files stay ≤ 5 MB, and the export has the newest ones.
     */
    @Test fun fiftyThousandEventsAreCheapCappedAndExported() {
        val text = "y".repeat(200)
        val t0 = System.nanoTime()
        repeat(50_000) { Problems.trace("gps", "event $it $text") }
        val callerMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("50 000 trace calls took $callerMs ms on the calling thread", callerMs < 3_000)
        Problems.flushForTest()
        val total = EventLog.files().sumOf { it.length() }
        assertTrue("event files are $total bytes", total in 1..EventLog.DEFAULT_MAX_BYTES + 128 * 1024)
        assertEquals(2, EventLog.files().size) // rotated into two files
        val out = java.io.ByteArrayOutputStream()
        EventLog.copyTo(out)
        val lines = out.toString().lines().filter { it.isNotEmpty() }
        assertTrue(lines.last().contains("event 49999 "))
        assertTrue("export has ${lines.size} lines", lines.size > 15_000)
        assertEquals("event 49999 $text", EventLog.parse(lines.last())!!.message)
    }

    @Test fun eventTextRoundTripsTabsAndLineBreaks() {
        Problems.trace("plan", "a\tb\nc\\d")
        Problems.trace("plan", "two\nlines\twith tab")
        Problems.flushForTest()
        Problems.init(dir)
        assertEquals("two\nlines\twith tab", Problems.events().first().message)
        assertEquals("a\tb\nc\\d", Problems.events()[1].message)
    }

    @Test fun eventFilesOlderThan14DaysAreDropped() {
        Problems.trace("map", "old")
        Problems.flushForTest()
        File(dir, EventLog.CURRENT).setLastModified(System.currentTimeMillis() - 15L * 24 * 3600 * 1000)
        Problems.init(dir)
        assertTrue(EventLog.files().isEmpty())
    }

    @Test fun traceIsExportedAndSurvivesARestart() {
        Problems.trace("plan", "Point menu: Delete tapped, plan 8 → 7")
        val json = Problems.toJson()
        assertEquals("Point menu: Delete tapped, plan 8 → 7", json.getJSONArray("events").getJSONObject(0).getString("message"))
        Problems.flushForTest()
        Problems.init(dir)
        assertEquals("Point menu: Delete tapped, plan 8 → 7", Problems.events().first().message)
    }
}

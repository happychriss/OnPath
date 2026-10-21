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

    /** Field 2026-10-21: 21 different "Hold: …" lines were merged into one entry (first text, × 21) – details lost. */
    @Test fun traceLinesAreNeverMerged() {
        repeat(21) { Problems.trace("map", "Hold: nearest point 2 at ${10 + it} dp") }
        val ev = Problems.events()
        assertEquals(21, ev.size)
        assertEquals("Hold: nearest point 2 at 30 dp", ev.first().message) // newest first
        assertEquals("Hold: nearest point 2 at 10 dp", ev.last().message)
        assertTrue("traces are not problems", Problems.snapshot().isEmpty())
    }

    @Test fun traceKeepsTheLast50WithTheirOwnTime() {
        repeat(70) { Problems.trace("map", "event $it") }
        val ev = Problems.events()
        assertEquals(Problems.MAX_EVENTS, ev.size)
        assertEquals("event 69", ev.first().message)
        assertEquals("event 20", ev.last().message)
        assertTrue(ev.all { it.time > 0 })
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

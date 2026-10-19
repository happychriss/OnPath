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
}

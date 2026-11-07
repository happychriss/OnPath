package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The trip file off the main thread (field 2026-11-07: "Main thread blocked 695 ms" in the recording tick, which wrote
 * the trip file with an fsync on the main thread every 30 s).
 */
class LatestFileWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Runs tasks only when told: shows what the caller's thread does itself. */
    private class Manual : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private val errors = mutableListOf<Exception>()

    @Test fun `write does no disk work on the caller's thread`() {
        val f = tmp.root.resolve("trip.json")
        val exec = Manual()
        val w = LatestFileWriter(f, exec) { errors += it }
        w.write("one")
        assertFalse("written on the caller's thread", f.exists())
        exec.runAll()
        assertEquals("one", f.readText())
        assertTrue(errors.isEmpty())
    }

    @Test fun `the newest text wins and is written once`() {
        val f = tmp.root.resolve("trip.json")
        val exec = Manual()
        val w = LatestFileWriter(f, exec) { errors += it }
        w.write("one"); w.write("two"); w.write("three")
        assertEquals(1, exec.tasks.size)
        exec.runAll()
        assertEquals("three", f.readText())
        w.write("four"); exec.runAll()
        assertEquals("four", f.readText())
    }

    @Test fun `delete comes after the writes asked for before it`() {
        val f = tmp.root.resolve("trip.json")
        f.writeText("old tour")
        val exec = Manual()
        val w = LatestFileWriter(f, exec) { errors += it }
        w.write("last state")
        w.delete() // tour end: this tour's pace ends with it
        exec.runAll()
        assertFalse(f.exists())
        w.write("next tour"); exec.runAll()
        assertEquals("next tour", f.readText())
    }

    @Test fun `writeAndWait is on disk when it returns`() {
        val f = tmp.root.resolve("trip.json")
        val exec = Executors.newSingleThreadExecutor()
        try {
            val w = LatestFileWriter(f, exec) { errors += it }
            w.write("older")
            assertTrue(w.writeAndWait("final"))
            assertEquals("final", f.readText())
            exec.submit {}.get() // a queued older write must not come after it
            assertEquals("final", f.readText())
        } finally { exec.shutdown() }
    }

    @Test fun `a stopped executor still writes, and says so`() {
        val f = tmp.root.resolve("trip.json")
        val w = LatestFileWriter(f, { throw RejectedExecutionException("shut down") }) { errors += it }
        w.write("kept")
        assertEquals("kept", f.readText())
        assertEquals(1, errors.size)
    }

    @Test fun `a failed write is reported, never thrown`() {
        val dirInTheWay = tmp.newFolder("trip.json").also { it.resolve("inside").writeText("x") } // not replaceable
        val exec = Manual()
        val w = LatestFileWriter(dirInTheWay, exec) { errors += it }
        w.write("x"); exec.runAll()
        assertEquals(1, errors.size)
    }
}

package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AtomicFilesTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun writesANewFileAndReplacesAnOldOne() {
        val f = File(dir.root, "sub/state.json")
        AtomicFiles.writeText(f, "one")
        assertEquals("one", f.readText())
        AtomicFiles.writeText(f, "two")
        assertEquals("two", f.readText())
        assertFalse("temp file left over", AtomicFiles.tmpFor(f).exists())
        AtomicFiles.writeBytes(f, byteArrayOf(1, 2, 3))
        assertEquals(listOf<Byte>(1, 2, 3), f.readBytes().toList())
    }

    /** JDK 25's File.renameTo unlinks the target before the rename: a reader found the file missing (CellDownloadTest). */
    @Test fun aReaderNeverFindsTheFileMissingOrHalfWritten() {
        val f = File(dir.root, "requests.json")
        AtomicFiles.writeText(f, content(0))
        val done = AtomicBoolean(false)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        var last = 0
        val writer = Thread {
            // keeps rewriting until the reader has looked often enough (with File.renameTo on JDK 25: ~3000 of 20000 missing)
            val end = System.currentTimeMillis() + 20_000
            try {
                while (reads.get() < 20_000 && System.currentTimeMillis() < end) AtomicFiles.writeText(f, content(++last), sync = false)
            } finally { done.set(true) }
        }.apply { start() }
        var missing = 0; var broken = 0
        while (!done.get()) {
            // expected if the replace were not atomic: the file gone for a moment – counted, asserted below
            try { if (!isContent(f.readText())) broken++; reads.incrementAndGet() } catch (e: java.io.FileNotFoundException) { missing++ }
        }
        writer.join()
        assertEquals("file missing in $missing of ${reads.get() + missing} reads", 0, missing)
        assertEquals("half-written content in $broken reads", 0, broken)
        assertEquals(content(last), f.readText())
    }

    @Test fun aFailedReplaceKeepsTheTempFileAndTheTarget() {
        // a non-empty folder in the target's place: rename(2) refuses it
        val target = File(dir.root, "plan.json").apply { mkdirs() }
        File(target, "keep").writeText("old")
        val tmp = File(dir.root, ".plan.json.tmp").apply { writeText("new") }
        try {
            AtomicFiles.replace(tmp, target)
            fail("replace over a non-empty folder should throw")
        } catch (e: IOException) {
            // expected: the caller reports it
        }
        assertEquals("the new content stays in the temp file", "new", tmp.readText())
        assertEquals("the target is untouched", "old", File(target, "keep").readText())
    }

    @Test fun aFailedWriteKeepsTheOldFile() {
        val f = File(dir.root, "trip.json").apply { writeText("old") }
        try {
            AtomicFiles.write(f) { it.write("ne".toByteArray()); throw IOException("disk full") }
            fail("write should rethrow")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertEquals("old", f.readText())
        assertFalse("half-written temp file deleted", AtomicFiles.tmpFor(f).exists())
    }

    @Test fun aGivenTempFileIsUsed() {
        val f = File(dir.root, "tour-times.json")
        val tmp = File(f.path + ".tmp")
        AtomicFiles.writeText(f, "x", tmp)
        assertEquals("x", f.readText())
        assertFalse(tmp.exists())
        assertTrue(f.isFile)
    }

    private fun content(i: Int) = "{\"v\":$i,\"pad\":\"" + "x".repeat(4_000) + "\"}"
    private fun isContent(s: String) = s.startsWith("{\"v\":") && s.endsWith("\"}") && s.length > 4_000
}

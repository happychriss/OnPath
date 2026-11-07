package com.onpath.app.data

import com.onpath.app.plansync.SyncFiles
import com.onpath.app.plansync.SyncLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Field bug 2026-11-07 (Pixel, problems.json): "Sync files could not be written" – NoSuchFileException
 * links.txt.tmp -> links.txt. Two writers of the same file used the same temp file: the first moved it, the second
 * found nothing (and could have moved the other's half-written content). Two app screens (two view models, two
 * PlanSync workers) write files/sync/ at the same time. Many writers of one file: never an exception, never a
 * partial file – the file always holds one writer's complete content.
 */
class AtomicFilesConcurrencyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun hammer(writers: Int, rounds: Int, write: (writer: Int, round: Int) -> Unit): List<Throwable> {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val pool = Executors.newFixedThreadPool(writers)
        val start = CountDownLatch(1)
        repeat(writers) { w ->
            pool.execute {
                start.await()
                repeat(rounds) { r -> try { write(w, r) } catch (e: Throwable) { errors += e } }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("writers finished", pool.awaitTermination(60, TimeUnit.SECONDS))
        return errors
    }

    /** A content that shows whether it is whole: a header line, a long body, the same footer as the header. */
    private fun content(w: Int, r: Int) = "start $w $r\n" + "x".repeat(20_000 + w * 37 + r) + "\nend $w $r\n"

    private fun assertWhole(text: String) {
        val first = text.lineSequence().first()
        val last = text.trimEnd('\n').lineSequence().last()
        assertTrue("whole file, got start '$first' end '$last'", first.startsWith("start ") &&
            last == first.replace("start", "end"))
        val (w, r) = first.removePrefix("start ").split(' ').map { it.toInt() }
        assertEquals(content(w, r), text)
    }

    @Test fun manyWritersOfOneFileThroughTheOldFixedTempNameNeverFailOrLeaveHalfAFile() {
        val target = File(tmp.root, "links.txt")
        val fixed = File(tmp.root, "links.txt.tmp") // what SyncFiles passed before the fix
        val errors = hammer(writers = 8, rounds = 40) { w, r -> AtomicFiles.writeText(target, content(w, r), fixed, sync = false) }
        assertEquals("no writer failed: ${errors.firstOrNull()}", emptyList<Throwable>(), errors)
        assertWhole(target.readText())
    }

    @Test fun manyWritersOfOneFileThroughTheDefaultTempNeverFailOrLeaveHalfAFile() {
        val target = File(tmp.root, "ledger.txt")
        val errors = hammer(writers = 8, rounds = 40) { w, r -> AtomicFiles.writeText(target, content(w, r), sync = false) }
        assertEquals("no writer failed: ${errors.firstOrNull()}", emptyList<Throwable>(), errors)
        assertWhole(target.readText())
        assertEquals("no temp file left", listOf("ledger.txt"), tmp.root.list()!!.sorted())
    }

    @Test fun twoSyncFilesOnOneFolderWritingTheLinksAtOnceNeverFail() {
        val dir = File(tmp.root, SyncFiles.DIR)
        val a = SyncFiles(dir)
        val b = SyncFiles(dir) // a second screen's PlanSync
        val errors = hammer(writers = 6, rounds = 30) { w, r ->
            val l = SyncLinks.empty()
            repeat(200) { i -> l.link(com.onpath.app.sync.SyncFormat.newId(ByteArray(16) { b -> (b * 31 + i + w * 7).toByte() }), "tour-$w-$r-$i") }
            (if (w % 2 == 0) a else b).saveLinks(l)
        }
        assertEquals("no writer failed: ${errors.firstOrNull()}", emptyList<Throwable>(), errors)
        val links = a.links()
        assertEquals("one writer's complete links", 200, links.all.size)
        assertEquals("one writer's links only", 1, links.all.values.map { it.substringBeforeLast('-') }.toSet().size)
        assertEquals("no temp file left", listOf("links.txt"), dir.list()!!.sorted())
    }

    /** Two screens' sync workers: a whole pass (read links, change, write) never interleaves with the other's. */
    @Test fun twoSyncFilesOnOneFolderNeverLoseAPassOfTheOther() {
        val dir = File(tmp.root, SyncFiles.DIR)
        val a = SyncFiles(dir)
        val b = SyncFiles(dir)
        val passes = 6 * 25
        val errors = hammer(writers = 6, rounds = 25) { w, r ->
            val f = if (w % 2 == 0) a else b
            f.exclusive {
                val l = f.links()
                Thread.yield()
                l.link(com.onpath.app.sync.SyncFormat.newId(ByteArray(16) { i -> (i + w * 31 + r * 7).toByte() }), "tour-$w-$r")
                f.saveLinks(l)
            }
        }
        assertEquals(emptyList<Throwable>(), errors)
        assertEquals("every pass's link kept", passes, a.links().all.size)
    }

    @Test fun staleTempFilesOfACrashAreCleanedButFreshOnesAndOtherFilesStay() {
        val dir = tmp.newFolder("sync")
        val old = File(dir, ".links.txt.1234.tmp").apply { writeText("half"); setLastModified(System.currentTimeMillis() - 2 * 3_600_000) }
        val oldFixed = File(dir, "links.txt.tmp").apply { writeText("half"); setLastModified(System.currentTimeMillis() - 2 * 3_600_000) }
        val fresh = File(dir, ".ledger.txt.99.tmp").apply { writeText("being written") }
        val keep = File(dir, "links.txt").apply { writeText("data") }
        val removed = AtomicFiles.cleanStaleTemps(dir)
        assertEquals(2, removed)
        assertTrue(!old.exists() && !oldFixed.exists())
        assertTrue(fresh.exists() && keep.exists())
    }
}

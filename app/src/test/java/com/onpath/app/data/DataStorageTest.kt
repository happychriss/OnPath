package com.onpath.app.data

import com.onpath.app.cells.NetworkStatus
import com.onpath.app.debug.Problems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CORE FEATURE C6 – field bug 2026-11-08 (Galaxy S10, Android 12, the app started right after a phone restart): for
 * about a minute Android gave no external files folder (`Failed to ensure /dev/null/Android/data/com.onpath.app/files`,
 * `getExternalFilesDir` = null). The data folder then fell back to the app's internal folder for the whole process:
 * an empty map store, routing parts and map areas loaded again into the wrong place. Now the data folder is the
 * remembered / standard one, "not ready" is told apart from "missing", and the work waits until the storage is there.
 */
class DataStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Mem(var path: String? = null) : DataStorage.Remembered {
        var writes = 0
        override fun read() = path
        override fun write(path: String) { this.path = path; writes++ }
    }

    private var clock = 1_000_000L
    private lateinit var ext: File
    private lateinit var internal: File

    @Before fun setUp() {
        Problems.clear()
        ext = File(tmp.root, "storage/emulated/0/Android/data/com.onpath.app/files")
        internal = tmp.newFolder("internal")
    }

    private fun storage(external: () -> File?, remembered: Mem = Mem(), standard: () -> File? = { null }) =
        DataStorage(external, remembered, standard, internal, scope = null, warnAfterMs = 60_000L, now = { clock })

    @Test fun readyStorageIsUsedAndRemembered() {
        ext.mkdirs()
        val mem = Mem()
        val s = storage({ ext }, mem)
        assertEquals(File(ext, "onpath"), s.dir)
        assertTrue(s.ready())
        assertTrue(File(ext, "onpath").isDirectory)
        assertEquals(File(ext, "onpath").absolutePath, mem.path)
        assertFalse(s.chosenWhileNotReady)
    }

    @Test fun nullFilesDirUsesTheRememberedFolderNeverTheInternalOne() {
        val remembered = File(ext, "onpath")
        var external: File? = null
        val s = storage({ external }, Mem(remembered.absolutePath))
        assertEquals(remembered, s.dir)
        assertFalse(s.dir.path.startsWith(internal.path))
        assertTrue(s.chosenWhileNotReady)
        assertFalse(s.ready())
        assertTrue(Problems.snapshot().any { it.level == Problems.Level.INFO && it.message.startsWith("Storage not ready yet") })

        // a minute later Android gives the folder: ready, same path as before (every store of the process keeps it)
        clock += 50_000
        ext.mkdirs(); external = ext
        assertTrue(s.ready())
        assertEquals(remembered, s.dir)
        assertTrue(Problems.snapshot().any { it.level == Problems.Level.INFO && it.message == "Storage ready after 50 s; data loads normally" })
        assertTrue(Problems.snapshot().none { it.level == Problems.Level.WARN })
    }

    @Test fun devNullFilesDirIsNotReady() {
        val devNull = File("/dev/null/Android/data/com.onpath.app/files")
        assertFalse(DataStorage.usable(devNull))
        assertFalse(DataStorage.usable(null))
        assertTrue(DataStorage.usable(ext))
        val s = storage({ devNull }, Mem(File(ext, "onpath").absolutePath))
        assertEquals(File(ext, "onpath"), s.dir)
        assertFalse(s.ready())
    }

    @Test fun withoutARememberedFolderTheStandardOneIsUsed() {
        val s = storage({ null }, Mem(), standard = { ext })
        assertEquals(File(ext, "onpath"), s.dir)
        assertFalse(s.ready())
        // the standard path under /dev/null too: only then the internal folder (no external storage at all)
        val last = storage({ null }, Mem(), standard = { File("/dev/null/Android/data/x/files") })
        assertEquals(File(internal, "onpath"), last.dir)
        assertTrue(last.ready())
    }

    /** The folder Android gives but cannot be read yet (here: blocked by a file of that name) is "not ready" too. */
    @Test fun unreadableFolderIsNotReadyUntilItCanBeListed() {
        ext.mkdirs()
        val blocker = File(ext, "onpath").apply { writeText("not a folder") }
        val s = storage({ ext })
        assertFalse(s.ready())
        blocker.delete()
        assertTrue(s.ready())
    }

    @Test fun waitingWorkRunsOnceWhenReadyAndAWarningOnlyWhenItLasts() {
        var external: File? = null
        val s = storage({ external }, Mem(File(ext, "onpath").absolutePath))
        var ran = 0
        s.whenReady { ran++ }
        s.whenReady { ran++ }
        assertEquals(0, ran)
        assertFalse(s.poll())
        clock += 30_000; assertFalse(s.poll())
        assertTrue(Problems.snapshot().none { it.level == Problems.Level.WARN })
        clock += 31_000; assertFalse(s.poll())
        assertEquals(1, Problems.snapshot().count { it.level == Problems.Level.WARN && it.message.startsWith("Storage still not ready after 61 s") })
        ext.mkdirs(); external = ext
        assertTrue(s.poll())
        assertEquals(2, ran)
        assertTrue(s.poll())
        assertEquals(2, ran) // once
        s.whenReady { ran++ }
        assertEquals(3, ran) // ready: at once
    }

    // ---------------------------------------------------------------- the required-data check

    @Test fun dataCheckSaysNothingMissingWhileTheStorageIsNotReady() {
        var external: File? = null
        val df = DataFiles(storage({ external }, Mem(File(ext, "onpath").absolutePath)))
        assertEquals(emptyList<DataFiles.Check>(), df.missing()) // not "maps missing": only not readable yet
        assertFalse(df.ready())
        ext.mkdirs(); external = ext
        // ready, an empty folder: now the files are really missing
        assertTrue(df.missing().any { it.name == "base.pmtiles" })
        File(ext, "onpath/base.pmtiles").writeText("x")
        assertTrue(df.missing().none { it.name == "base.pmtiles" })
    }

    // ---------------------------------------------------------------- routing data

    private class Net : NetworkStatus {
        override fun connected() = true
        override fun unmetered() = true
    }

    private class Gate(var open: Boolean = false) : StorageGate {
        private val blocks = ArrayList<() -> Unit>()
        override fun ready() = open
        override fun whenReady(block: () -> Unit) { if (open) block() else blocks += block }
        fun openNow() { open = true; blocks.toList().also { blocks.clear() }.forEach { it() } }
    }

    /**
     * Routing parts that only look missing (the segments folder is not readable yet) are never downloaded; once the
     * storage is there the ones on the phone are reported (the plan is computed again) and only the really missing load.
     */
    @Test fun routingDataWaitsForTheStorageAndLoadsOnlyWhatIsReallyMissing() {
        val seg = tmp.newFolder("onpath", "segments")
        File(seg, "E5_N45.rd5").writeText("routing data on the phone")
        val away = File(tmp.root, "unmounted")
        assertTrue(seg.renameTo(away)) // the storage is not there: the folder cannot be seen
        val gate = Gate()
        val calls = mutableListOf<String>()
        val installed = mutableListOf<String>()
        val l = RoutingDataLoader(seg, Net(), { false }, launch = { it() }, onInstalled = { installed += it },
            download = { names, d, _, _ -> calls += names; names.forEach { File(d, it).writeText("downloaded") } },
            storage = gate)
        assertEquals(listOf("E5_N45.rd5", "E10_N45.rd5"), l.ensure(listOf("E5_N45.rd5", "E10_N45.rd5")))
        assertEquals(emptyList<String>(), calls) // nothing downloaded while not ready
        assertTrue(Problems.snapshot().any { it.message.startsWith("Routing data waits for the storage") })

        assertTrue(away.renameTo(seg))
        gate.openNow()
        assertEquals(listOf("E10_N45.rd5"), calls) // only the really missing part
        assertEquals("routing data on the phone", File(seg, "E5_N45.rd5").readText()) // never rewritten
        assertEquals(setOf("E5_N45.rd5", "E10_N45.rd5"), installed.toSet())
    }
}

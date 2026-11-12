package com.onpath.app.cells

import com.onpath.app.debug.Problems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Safety net for the map-cell files the app reads at start: a downloaded cell's `manifest.json`, a store
 * generation's `store.json`, and MapCells' `requests.json` / `usage.json`. Garbage, a cut file, the wrong type or a
 * huge file never throw and never touch an installed generation; only an unreadable DOWNLOAD is removed (it loads
 * again), never the installed map.
 */
class BrokenCellFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)

    private class Net : NetworkStatus {
        override fun connected() = false
        override fun unmetered() = false
    }

    @Before fun setUp() { Problems.init(tmp.newFolder("problems")); Problems.clear() }
    @After fun tearDown() { scope.cancel() }

    private val goodManifest get() = CellFixtures.cell(tmp.newFolder(), a, "v1").json

    private fun cases(good: String) = listOf(
        "empty" to "",
        "garbage" to "\u0000ÿ{{garbage",
        "truncated" to good.take(good.length / 2),
        "an array at the top" to "[1]",
        "fields of the wrong type" to """{"cell":5,"version":[],"files":"x","generation":"g","cells":"c"}""",
        "entries of the wrong type" to """{"cell":"10-543-361","version":"v1","files":[1,2],"generation":1,"cells":[5]}""",
        "entries without the needed fields" to """{"cell":"10-543-361","version":"v1","files":[{"name":"base.pmtiles"}],"generation":1,"cells":[{"id":"nope"}]}""",
        "very large" to buildString { append("{\"cells\":["); repeat(50_000) { append("{\"id\":\"10-$it-1\",\"reason\":\"NEAR\",\"at\":1},") }; append("{}]}") },
    )

    @Test fun aBrokenDownloadIsDroppedAndLoadsAgain() {
        for ((name, text) in cases(goodManifest)) {
            val store = CellStore(tmp.newFolder())
            val dir = File(File(store.downloadDir, b.id), "v1").apply { mkdirs() }
            File(dir, CellStore.MANIFEST).writeText(text)
            val goodDir = File(File(store.downloadDir, a.id), "v1")
            CellFixtures.cell(goodDir, a, "v1")
            val pending = store.pendingDownloads() // never throws
            assertEquals(name, listOf(a), pending.map { it.manifest.cell })
            assertFalse(name, dir.exists()) // the broken download goes; the good one stays
            assertTrue(name, goodDir.isDirectory)
        }
        val store = CellStore(tmp.newFolder())
        File(File(store.downloadDir, b.id), "v1").mkdirs() // a folder without a manifest at all
        assertTrue(store.pendingDownloads().isEmpty())
    }

    @Test fun aBrokenGenerationLeavesTheInstalledMapAlone() {
        for ((name, text) in cases("""{"generation":2,"cells":[]}""")) {
            Problems.clear()
            val data = tmp.newFolder()
            val store = CellStore(data)
            val dir = File(File(store.downloadDir, a.id), "v1")
            val g1 = store.apply(add = listOf(CellStore.Download(CellFixtures.cell(dir, a, "v1"), dir)))
            val broken = File(store.storeDir, "g000002").apply { mkdirs(); File(this, CellStore.STORE_JSON).writeText(text) }
            val fresh = CellStore(data)
            assertEquals(name, g1.number, fresh.current()!!.number)
            assertEquals(name, listOf(a), fresh.current()!!.cells.map { it.id })
            assertTrue(name, broken.isDirectory) // reading never deletes
            assertTrue(name, Problems.snapshot().any { it.area == "cells" && it.message.contains("g000002 unreadable") })
        }
    }

    @Test fun brokenRequestAndUsageFilesNeverStopTheStart() {
        for ((name, text) in cases("""{"cells":[{"id":"10-543-361","reason":"NEAR","own":true,"at":1,"mobileOk":false}]}""")) {
            val store = CellStore(tmp.newFolder())
            store.root.mkdirs()
            File(store.root, "requests.json").writeText(text)
            File(store.root, "usage.json").writeText(if (name == "very large") "{\"10-543-361\":\"x\"}" else text)
            val mc = MapCells(store, null, Net(), scope, "0.8.0", regionPackages = false)
            mc.start() // never throws
            assertTrue(name, mc.installed.value.isEmpty())
        }
    }
}

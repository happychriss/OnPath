package com.onpath.app.cells

import com.onpath.app.data.StorageGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CORE FEATURES C6 / C14 – field bug 2026-11-08 (Galaxy S10 right after a phone restart): the data folder could not be
 * read for about a minute, so the map store looked empty. Never delete, rewrite or load again what only looks missing:
 * the store is read once the storage is there, requests made meanwhile are asked then, Clean up / Clear all remove
 * nothing meanwhile, and a store generation is never written over an unreadable folder.
 */
class StorageNotReadyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)
    private val c = CellId(545, 361)

    @After fun tearDown() { scope.cancel() }

    private class Offline : NetworkStatus {
        override fun connected() = false
        override fun unmetered() = false
    }

    private class Gate(@Volatile var open: Boolean = false) : StorageGate {
        private val blocks = ArrayList<() -> Unit>()
        override fun ready() = open
        override fun whenReady(block: () -> Unit) { if (open) block() else synchronized(blocks) { blocks += block } }
        fun openNow() { open = true; synchronized(blocks) { blocks.toList().also { blocks.clear() } }.forEach { it() } }
    }

    private fun install(data: File, vararg ids: CellId): CellStore.Generation {
        val store = CellStore(data)
        val downloads = ids.map { id ->
            val dir = File(File(store.downloadDir, id.id), "v1")
            CellStore.Download(CellFixtures.cell(dir, id, "v1"), dir)
        }
        return store.apply(add = downloads).also { ids.forEach { store.deleteDownloads(it) } }
    }

    /** A store generation is never written while the data folder cannot be read; the real one is intact afterwards. */
    @Test fun storeIsNotWrittenOverAnUnreadableFolder() {
        val data = tmp.newFolder("data")
        val g1 = install(data, a)
        val away = File(tmp.root, "unmounted")
        assertTrue(data.renameTo(away)) // the storage is gone for a minute: the folder cannot be seen
        CellStore.forget(File(data, "cells/store"))
        val store = CellStore(data)
        assertFalse(store.readable())
        assertNull(store.current()) // looks empty …
        val dir = File(File(away, "cells/download/${b.id}"), "v1")
        val dl = CellStore.Download(CellFixtures.cell(dir, b, "v1"), dir)
        try { store.apply(add = listOf(dl)); fail("expected a refusal") } catch (e: CellStore.StoreException) {
            assertTrue(e.cause!!.message!!.contains("not readable"))
        }
        try { store.apply(remove = setOf(a)); fail("expected a refusal") } catch (e: CellStore.StoreException) { /* expected: refused */ }
        assertFalse(data.exists()) // … and nothing was created at its path
        assertTrue(away.renameTo(data))
        val back = CellStore(data).current()!!
        assertEquals(g1.number, back.number)
        assertEquals(listOf(a), back.cells.map { it.id })
    }

    @Test fun mapCellsWaitForTheStorageAndThenLoadNormally() {
        val data = tmp.newFolder("data")
        val g1 = install(data, a)
        // a request saved by the last run
        val queueFile = File(data, "cells/requests.json")
        val saved = """{"cells":[{"id":"${b.id}","reason":"POINT","own":true,"at":${System.currentTimeMillis()},"mobileOk":false}]}"""
        queueFile.writeText(saved)
        CellStore.forget(File(data, "cells/store"))

        val gate = Gate()
        val mc = MapCells(CellStore(data), CellApi("http://127.0.0.1:9/v1", "k", "0.8.0", 500, 500), Offline(), scope, "0.8.0", regionPackages = false, storage = gate)
        mc.start()
        // not ready: the store is not read, nothing is offered or asked for, nothing removed
        assertEquals(emptyList<CellStore.InstalledCell>(), mc.installed.value)
        assertEquals(0, mc.generation.value)
        assertEquals(emptyList<CellId>(), mc.missing(listOf(a, c)))
        assertEquals(emptyList<CellId>(), mc.request(listOf(a), CellReason.POINT, ownPoint = true))
        assertEquals(emptyList<CellId>(), mc.request(listOf(c), CellReason.NEAR, ownPoint = false))
        assertEquals(CellState.None, mc.state(c))
        assertEquals(emptyList<CellId>(), mc.clearAll())
        mc.delete(listOf(a))
        // Settings → Clean up: nothing installed is known, so nothing goes
        assertTrue(MapCleanup.plan(mc.installed.value.map { it.id to it.totalBytes }, MapCleanup.Inputs()).nothingToDo)
        assertEquals(saved, queueFile.readText()) // the saved requests are not overwritten

        gate.openNow()
        assertEquals(listOf(a), mc.installed.value.map { it.id })
        assertEquals(g1.number, mc.generation.value)
        assertEquals(CellState.Ready("v1"), mc.state(a))
        // the saved request and the one asked while waiting are in the queue now (offline here: they wait for a connection)
        assertTrue(mc.state(b) != CellState.None && mc.state(b) !is CellState.Ready)
        assertTrue(mc.state(c) != CellState.None && mc.state(c) !is CellState.Ready)
        val queued = org.json.JSONObject(queueFile.readText()).getJSONArray("cells").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.toSet()
        }
        assertEquals(setOf(b.id, c.id), queued) // the installed one is not loaded again
        // the refused delete / Clear all removed nothing, also after the worker ran
        Thread.sleep(300)
        assertEquals(listOf(a), mc.installed.value.map { it.id })
        assertEquals(listOf(a), CellStore(data).installed().map { it.id })
        // Clean up now works on what is really there: a kept around the position, nothing else installed
        val plan = MapCleanup.plan(mc.installed.value.map { it.id to it.totalBytes }, MapCleanup.Inputs(position = a.center))
        assertTrue(plan.nothingToDo)
    }
}

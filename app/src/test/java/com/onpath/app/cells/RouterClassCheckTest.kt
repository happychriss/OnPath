package com.onpath.app.cells

import com.onpath.app.debug.Problems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The cells' router classes (manifest "wayclass") vs the app's routing profiles (backlog "Router classes"): a cell built
 * with other profiles is reported once and asked for again; equal or unknown hashes stay quiet.
 */
class RouterClassCheckTest {
    @get:Rule val tmp = TemporaryFolder()
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)
    private val app = "2-0123456789abcdef"
    private val old = "2-fedcba9876543210"
    /** Format 1 (raw bytes, cells built before comments stopped counting): never comparable. */
    private val v1 = "704c9982b445dbe2"

    @Before fun setUp() = Problems.clear()

    private fun cell(id: CellId, version: String, wayclass: String?) =
        CellStore.InstalledCell(id, version, null, 2, emptyMap(), 0L, 0L, wayclass)

    private fun warnings() = Problems.snapshot().filter { it.area == "cells" && it.message.contains("router classes") }

    @Test fun matchingHashIsQuiet() {
        val check = RouterClassCheck { app }
        assertEquals(emptyList<CellId>(), check.check(listOf(cell(a, "2026-10-20-s7", app), cell(b, "2026-10-20-s7", app)), true))
        assertEquals(0, warnings().size)
    }

    @Test fun otherProfilesWarnOnceAndAskForTheCell() {
        val check = RouterClassCheck { app }
        val inst = listOf(cell(a, "2026-10-20-s7", old), cell(b, "2026-10-20-s7", app))
        assertEquals(listOf(a), check.check(inst, true))
        val w = warnings().single()
        assertEquals(Problems.Level.WARN, w.level)
        assertTrue(w.message, w.message.contains(a.id) && w.message.contains(old) && w.message.contains(app) && !w.message.contains(b.id))
        // checked again (next generation, app start of the worker): the same version is not asked for or reported again
        assertEquals(emptyList<CellId>(), check.check(inst, true))
        assertEquals(1, warnings().single().count)
        // the server answered with a newer build that still has the old classes: once more, then quiet
        val newer = listOf(cell(a, "2026-11-20-s7", old), cell(b, "2026-10-20-s7", app))
        assertEquals(listOf(a), check.check(newer, true))
        assertEquals(emptyList<CellId>(), check.check(newer, true))
    }

    @Test fun unknownHashesAreQuiet() {
        // cells built before the field, a store written before it, region packages (no classes)
        val check = RouterClassCheck { app }
        val inst = listOf(cell(a, "2026-10-20-s3", null), cell(CellId(66, 42, 7), "2026-10-20-p1", old))
        assertEquals(emptyList<CellId>(), check.check(inst, true))
        // the app's own hash unreadable → nothing compared
        assertEquals(emptyList<CellId>(), RouterClassCheck { null }.check(listOf(cell(a, "2026-10-20-s7", old)), true))
        assertEquals(0, warnings().size)
    }

    /** Day one: every cell on the server has a format-1 hash – unknown, nothing logged, nothing asked for. */
    @Test fun formatOneHashIsUnknown() {
        val check = RouterClassCheck { app }
        assertEquals(emptyList<CellId>(), check.check(listOf(cell(a, "2026-10-20-s7", v1), cell(b, "2026-10-20-s7", "2-short")), true))
        assertEquals(0, warnings().size)
        // a v1 app hash (cannot happen, but never "different") → nothing
        assertEquals(emptyList<CellId>(), RouterClassCheck { v1 }.check(listOf(cell(a, "2026-10-20-s7", old)), true))
        assertEquals(0, warnings().size)
        // the cell's monthly rebuild brings a format-2 hash: from then on compared
        assertEquals(listOf(a), check.check(listOf(cell(a, "2026-11-20-s7", old)), true))
        assertEquals(1, warnings().size)
    }

    @Test fun developerCellsAndNoServerAreOnlyReported() {
        val dev = cell(a, "2026-10-25-dev10261201-s7", old)
        assertEquals(emptyList<CellId>(), RouterClassCheck { app }.check(listOf(dev), true))
        assertTrue(warnings().single().message.contains("developer cells kept"))
        Problems.clear()
        assertEquals(emptyList<CellId>(), RouterClassCheck { app }.check(listOf(cell(b, "2026-10-20-s7", old)), false))
        assertTrue(warnings().single().message.contains("no map server"))
    }

    @Test fun manifestHashIsReadAndKeptInTheStore() {
        val data = tmp.newFolder("data")
        val store = CellStore(data)
        val dir = File(File(store.downloadDir, a.id), "v1")
        val m = CellFixtures.cell(dir, a, "v1", wayclass = old)
        assertEquals(old, m.wayclass)
        store.apply(add = listOf(CellStore.Download(m, dir)))
        assertEquals(old, CellStore(data).installed().single().wayclass) // read back from store.json
        // a kept cell keeps its hash when a neighbour joins
        val dirB = File(File(store.downloadDir, b.id), "v1")
        store.apply(add = listOf(CellStore.Download(CellFixtures.cell(dirB, b, "v1"), dirB)))
        val inst = CellStore(data).installed().associateBy { it.id }
        assertEquals(old, inst.getValue(a).wayclass)
        assertNull(inst.getValue(b).wayclass)
        // a format-1 hash is kept as it is (the check calls it unknown); not a hash → null, never a reason to refuse the cell
        val v1json = org.json.JSONObject(File(dir, CellStore.MANIFEST).readText()).put("wayclass", v1).toString()
        assertEquals(v1, CellManifest.parse(v1json).wayclass)
        val json = org.json.JSONObject(File(dir, CellStore.MANIFEST).readText()).put("wayclass", "../x").toString()
        assertNull(CellManifest.parse(json).wayclass)
    }

    // ------------------------------------------------------------------------------------------- with MapCells

    private lateinit var server: FakeDoorman
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() { scope.cancel(); if (::server.isInitialized) server.close() }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timeout waiting for $what")
            Thread.sleep(20)
        }
    }

    private class Net : NetworkStatus {
        override fun connected() = true
        override fun unmetered() = true
    }

    @Test fun cellWithOtherProfilesIsRefreshedByTheNormalPath() {
        server = FakeDoorman(tmp.newFolder("server"))
        server.publish(a, "2026-10-20-s6", wayclass = old)
        server.updating += a.id // the doorman rebuilds it: a refresh keeps asking until the new build is there
        val mc = MapCells(CellStore(tmp.newFolder("data")), CellApi(server.url, server.key, "0.8.0", 2000, 2000), Net(), scope, "0.8.0",
            pollDelayMs = { 30L }, backgroundPollMs = 30L, regionPackages = false, routerClassHash = { app }).also { it.start() }
        mc.request(listOf(a), CellReason.POINT)
        waitFor("old cell") { mc.state(a) == CellState.Ready("2026-10-20-s6") }
        // installed → the check asks for it again (a second request call naming a), the old map stays meanwhile
        waitFor("refresh asked") { server.requestCalls.count { a.id in it } >= 2 }
        assertEquals(1, warnings().size)
        // the server has a build with this app's profiles: it replaces the old one, nothing more is reported
        server.publish(a, "2026-10-21-s7", wayclass = app)
        server.updating -= a.id
        waitFor("new cell") { mc.state(a) == CellState.Ready("2026-10-21-s7") }
        assertEquals(app, mc.installed.value.single().wayclass)
        Thread.sleep(200)
        assertEquals(1, warnings().size)
    }

    @Test fun matchingCellIsNotAskedForAgain() {
        server = FakeDoorman(tmp.newFolder("server"))
        server.publish(a, "2026-10-20-s7", wayclass = app)
        val mc = MapCells(CellStore(tmp.newFolder("data")), CellApi(server.url, server.key, "0.8.0", 2000, 2000), Net(), scope, "0.8.0",
            pollDelayMs = { 30L }, backgroundPollMs = 30L, regionPackages = false, routerClassHash = { app }).also { it.start() }
        mc.request(listOf(a), CellReason.POINT)
        waitFor("cell") { mc.state(a) == CellState.Ready("2026-10-20-s7") }
        Thread.sleep(300)
        assertEquals(1, server.requestCalls.count { a.id in it })
        assertEquals(0, warnings().size)
    }
}

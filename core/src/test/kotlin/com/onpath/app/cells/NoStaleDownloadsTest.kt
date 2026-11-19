package com.onpath.app.cells

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CORE FEATURE "no stale or unexpected downloads": the corridor regions of a long route planned once must not stay in
 * cells/requests.json and download again after every app start. Corridor requests belong to the current plan; the queue is revalidated at start and right before each download;
 * finished cells are joined in one generation.
 */
class NoStaleDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: FakeDoorman
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)
    private val c = CellId(545, 361)
    private val d = CellId(546, 361)

    private class Net(@Volatile var connected: Boolean = true, @Volatile var unmetered: Boolean = true) : NetworkStatus {
        override fun connected() = connected
        override fun unmetered() = unmetered
    }

    @Before fun setUp() { server = FakeDoorman(tmp.newFolder("server")) }
    @After fun tearDown() { scope.cancel(); server.close() }

    private fun api() = CellApi(server.url, server.key, "0.8.0", 2000, 2000)

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timeout waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun cells(net: NetworkStatus = Net(), data: File = tmp.newFolder("data"), freshMs: Long = MapCells.FRESH_MS,
                      start: Boolean = true) =
        MapCells(CellStore(data), api(), net, scope, "0.8.0", pollDelayMs = { 30L }, backgroundPollMs = 30L, freshMs = freshMs, regionPackages = false)
            .also { if (start) it.start() }

    private fun queueFile(mc: MapCells) = File(mc.store.root, "requests.json")
    private fun queued(mc: MapCells): Set<String> = queueFile(mc).takeIf { it.isFile }?.let { f ->
        val arr = JSONObject(f.readText()).getJSONArray("cells"); (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.toSet()
    } ?: emptySet()

    private fun fetched(cell: CellId) = server.log.any { it.contains("/cells/${cell.path}/manifest") || it.contains("/files/cells/f2/${cell.path}/") }

    /** Plan changed (new route, undo): corridor regions the new route does not need are dropped – never downloaded. */
    @Test fun corridorFollowsThePlan() {
        listOf(a, b, c).forEach { server.publish(it, "v1") }; server.buildPolls = 1_000_000
        val mc = cells()
        mc.keepCorridor(listOf(a, b, c))
        assertEquals(listOf(a, b, c), mc.request(listOf(a, b, c), CellReason.CORRIDOR, ownPoint = false))
        waitFor("building") { mc.state(b) is CellState.Building }
        assertEquals(listOf(b, c), mc.keepCorridor(listOf(a))) // the new route passes only a
        assertEquals(setOf(a.id), queued(mc))
        assertEquals(CellState.None, mc.state(b))
        server.buildPolls = 0
        waitFor("a loaded") { mc.state(a) == CellState.Ready("v1") }
        Thread.sleep(200)
        assertFalse("b never downloaded", fetched(b)); assertFalse("c never downloaded", fetched(c))
        assertEquals(listOf(a), mc.installed.value.map { it.id })
    }

    /** Clear / long-route question / Cancel / not routed: no corridor at all; the user's point stays. */
    @Test fun planWithoutRouteDropsTheWholeCorridorButNotPoints() {
        server.buildPolls = 1_000_000
        val mc = cells(Net(connected = false))
        mc.request(listOf(d), CellReason.POINT)
        mc.request(listOf(a, b), CellReason.CORRIDOR, ownPoint = false)
        mc.request(listOf(b), CellReason.POINT) // b is also the user's point now: it stays
        assertEquals(listOf(a), mc.keepCorridor(emptyList()))
        assertEquals(setOf(b.id, d.id), queued(mc))
    }

    /** A running corridor download stops when the plan no longer needs it (installed cells stay). */
    @Test fun runningCorridorDownloadStops() {
        server.publish(a, "v1"); server.buildPolls = 0
        server.cutAfter = null
        val mc = cells()
        mc.keepCorridor(listOf(a))
        // slow the download: the server cuts it, the worker retries – meanwhile the plan changes
        server.cutAfter = 10
        mc.request(listOf(a), CellReason.CORRIDOR, ownPoint = false)
        waitFor("downloading") { server.log.any { it.contains("/files/") } }
        mc.keepCorridor(emptyList())
        server.cutAfter = null
        val n = server.log.size
        Thread.sleep(400)
        assertFalse("no more downloads of a", server.log.drop(n).any { it.contains("/files/") })
        assertTrue(mc.installed.value.isEmpty())
        assertTrue(queued(mc).isEmpty())
    }

    /**
     * BUG (check-then-act, found 2026-11-20 as the rare runner failure of [runningCorridorDownloadStops]): the worker
     * takes its list of requests, THEN the plan drops the corridor cell, THEN the worker starts the download from its
     * stale list. The progress check ([MapCells] `queued(c)`) fires only every 256 KB, so a small cell was downloaded
     * and installed although the plan no longer needed it. Forced without sleeps: the network status is asked by the
     * worker right after it took its list ([MapCells.allowed]) – there the "plan change" happens.
     */
    @Test fun planDropsTheCellBetweenTheWorkersListAndItsDownload() {
        server.publish(a, "v1"); server.publish(b, "v1"); server.buildPolls = 0
        val hookArmed = java.util.concurrent.atomic.AtomicBoolean(true)
        val asks = java.util.concurrent.atomic.AtomicInteger()
        lateinit var mc: MapCells
        val net = object : NetworkStatus {
            override fun connected() = true
            override fun unmetered(): Boolean {
                // the worker holds its list with a and is about to download it: only now the plan changes
                // 1st ask: the round that learned "ready" (anyReadyAllowed); 2nd: the next round's list → download
                if (mc.state(a) is CellState.Downloading && asks.incrementAndGet() == 2 && hookArmed.compareAndSet(true, false)) mc.keepCorridor(emptyList())
                return true
            }
        }
        mc = cells(net, start = false)
        mc.start()
        mc.keepCorridor(listOf(a))
        mc.request(listOf(a), CellReason.CORRIDOR, ownPoint = false)
        waitFor("the plan change happened inside the worker's round") { !hookArmed.get() }
        // b is a sentinel: the worker is serial, so b is ready only after its round with a is over
        mc.request(listOf(b), CellReason.POINT)
        waitFor("b loaded") { mc.state(b) == CellState.Ready("v1") }
        assertFalse("a never downloaded", server.log.any { it.contains("/files/cells/f2/${a.path}/") })
        assertEquals(CellState.None, mc.state(a))
        assertEquals(listOf(b), mc.installed.value.map { it.id })
        assertTrue(queued(mc).isEmpty())
    }

    /**
     * App start: corridor requests from an old plan wait for the restored plan's route – not sent, not
     * downloaded; dropped when the plan has no route. Point requests older than a day go; fresh ones and refreshes of
     * installed areas stay.
     */
    @Test fun restartRevalidatesTheSavedQueue() {
        listOf(a, b, c, d).forEach { server.publish(it, "v1") }; server.buildPolls = 0
        val data = tmp.newFolder("data")
        val root = File(data, "cells").apply { mkdirs() }
        val now = System.currentTimeMillis()
        fun e(id: CellId, reason: String, at: Long) = JSONObject().put("id", id.id).put("reason", reason).put("own", false).put("at", at).put("mobileOk", false)
        File(root, "requests.json").writeText(JSONObject().put("cells", JSONArray(listOf(
            e(a, "CORRIDOR", now - 3600_000), e(b, "CORRIDOR", now - 3600_000),
            e(c, "POINT", now - 25 * 3600_000L),   // yesterday's point: nobody waits
            e(d, "NEAR", now - 60_000),             // a few minutes ago: still loads
        ))).toString())
        val mc = cells(data = data)
        waitFor("fresh near request loads") { mc.state(d) == CellState.Ready("v1") }
        Thread.sleep(300)
        assertFalse("corridor not sent before the plan says so", server.log.any { it.startsWith("POST") && (it.contains(a.id) || it.contains(b.id)) })
        listOf(a, b, c).forEach { assertFalse("$it not downloaded", fetched(it)) }
        assertFalse(c.id in queued(mc))
        assertEquals(CellState.None, mc.state(a))
        // the restored plan's route passes a only (or has no route → both go)
        assertEquals(listOf(b), mc.keepCorridor(listOf(a)))
        waitFor("a loads for the restored plan") { mc.state(a) == CellState.Ready("v1") }
        assertFalse(fetched(b))
        assertTrue(queued(mc).isEmpty())
    }

    /** Long route, Cancel, restart → nothing loads, the queue is empty. */
    @Test fun longRouteCancelledThenRestartLoadsNothing() {
        listOf(a, b, c).forEach { server.publish(it, "v1") }; server.buildPolls = 1_000_000
        val data = tmp.newFolder("data")
        val first = cells(Net(connected = false), data)
        first.keepCorridor(listOf(a, b, c))
        first.request(listOf(a, b, c), CellReason.CORRIDOR, ownPoint = false)
        assertEquals(3, queued(first).size)
        // restart: the restored plan is too long → the question → no route → empty corridor
        val second = cells(Net(), data)
        Thread.sleep(200)
        assertFalse(server.log.any { it.startsWith("POST") })
        second.keepCorridor(emptyList())
        assertTrue(queued(second).isEmpty())
        server.buildPolls = 0
        Thread.sleep(300)
        listOf(a, b, c).forEach { assertFalse(fetched(it)) }
    }

    /** Right before each download: a point request that no longer belongs to a point / search / position is dropped. */
    @Test fun pointNoLongerWantedIsNotDownloaded() {
        server.publish(a, "v1"); server.publish(b, "v1"); server.buildPolls = 0
        val mc = cells(freshMs = 0, start = false)
        mc.relevance = object : MapCells.Relevance {
            override fun waiting() = setOf(b)
            override fun stillNear(c: CellId) = false
        }
        mc.start()
        mc.request(listOf(a), CellReason.POINT)
        mc.request(listOf(b), CellReason.NEAR, ownPoint = false)
        waitFor("b, waited for, loads") { mc.state(b) == CellState.Ready("v1") }
        Thread.sleep(200)
        assertFalse(fetched(a))
        assertEquals(CellState.None, mc.state(a))
        assertTrue(queued(mc).isEmpty())
    }

    /** A refresh of an area deleted meanwhile is dropped. */
    @Test fun refreshOfAnAreaNotOnThePhoneIsDropped() {
        server.publish(a, "v1"); server.buildPolls = 0
        val mc = cells()
        mc.request(listOf(a), CellReason.REFRESH, ownPoint = false)
        waitFor("dropped") { queued(mc).isEmpty() }
        Thread.sleep(200)
        assertFalse(fetched(a))
    }

    /** Few generations: several cells finishing together are joined in ONE store generation. */
    @Test fun cellsFinishingTogetherMakeOneGeneration() {
        listOf(a, b, c).forEach { server.publish(it, "v1") }; server.buildPolls = 1
        val mc = cells()
        val gen0 = mc.generation.value
        mc.request(listOf(a, b, c), CellReason.NEAR, ownPoint = false)
        waitFor("all ready") { listOf(a, b, c).all { mc.state(it) == CellState.Ready("v1") } }
        assertEquals(gen0 + 1, mc.generation.value)
        assertEquals(setOf(a, b, c), mc.installed.value.map { it.id }.toSet())
    }

    /** Never while the user drags the map / sheet: the finished cell is joined when the finger leaves. */
    @Test fun noJoinWhileTheUserDrags() {
        server.publish(a, "v1"); server.buildPolls = 0
        val mc = cells()
        val gen0 = mc.generation.value
        mc.userBusy = true
        mc.request(listOf(a), CellReason.POINT)
        waitFor("downloaded") { mc.state(a) == CellState.Installing && !queued(mc).contains(a.id) }
        Thread.sleep(300)
        assertEquals(gen0, mc.generation.value)
        assertEquals(emptyList<CellId>(), mc.missing(listOf(a))) // not asked again meanwhile
        mc.userBusy = false
        waitFor("joined") { mc.state(a) == CellState.Ready("v1") }
        assertEquals(gen0 + 1, mc.generation.value)
    }

    /**
     * Multi-day tours (docs/design/multi-day-tours.md §7): the areas the user said "load" to for a tour stay asked for
     * when the plan's corridor changes (a single day planned, another route); asked on mobile data they wait for Wi-Fi –
     * also without the "Wi-Fi only" setting – unless a point / the position asks for the same area; on Wi-Fi they load.
     */
    @Test fun aMultiDayTourKeepsItsAreasAndWaitsForWifi() {
        listOf(a, b, c).forEach { server.publish(it, "v1") }; server.buildPolls = 0
        val net = Net(unmetered = false)
        val mc = cells(net)
        mc.tourCells = setOf(a, b); mc.tourWifiCells = setOf(a, b)
        assertEquals(listOf(a, b), mc.request(listOf(a, b), CellReason.CORRIDOR, ownPoint = false))
        mc.request(listOf(c), CellReason.CORRIDOR, ownPoint = false)
        // the plan is now one day that needs none of them: c (the old plan's corridor) goes, the tour's stay
        assertEquals(listOf(c), mc.keepCorridor(emptyList()))
        assertEquals(setOf(a.id, b.id), queued(mc))
        waitFor("waiting for Wi-Fi") { mc.state(a) is CellState.WaitingForWifi && mc.state(b) is CellState.WaitingForWifi }
        // on the way: the position needs b – it loads on mobile data (the own-point rule)
        mc.request(listOf(b), CellReason.NEAR, ownPoint = true)
        waitFor("b loaded on mobile data") { mc.state(b) == CellState.Ready("v1") }
        assertTrue(mc.state(a) is CellState.WaitingForWifi)
        net.unmetered = true; mc.wifiOnly = false // any change wakes the worker
        waitFor("a loaded on Wi-Fi") { mc.state(a) == CellState.Ready("v1") }
        assertEquals(setOf(a, b), mc.notOnPhone(listOf(a, b, c)).let { setOf(a, b, c) - it.toSet() })
    }

    /**
     * BUG (lost wake-up, found 2026-11-19 as the flake of [aMultiDayTourKeepsItsAreasAndWaitsForWifi]): [MapCells.request]
     * for a cell already in the queue upgrades the entry (reason NEAR, ownPoint) but wakes the worker only when it ADDED a
     * cell. A tour cell waiting for Wi-Fi that the position now needs stays waiting until the worker's next wake-up
     * (IDLE_MS = 15 min, or any network / foreground change). Fix: wake() when an existing entry changed.
     */
    @Test fun positionNeedingAWaitingTourCellWakesTheWorker() {
        server.publish(a, "v1"); server.buildPolls = 0
        val mc = cells(Net(unmetered = false))
        mc.tourCells = setOf(a); mc.tourWifiCells = setOf(a)
        mc.request(listOf(a), CellReason.CORRIDOR, ownPoint = false)
        waitFor("a waits for Wi-Fi") { mc.state(a) is CellState.WaitingForWifi }
        Thread.sleep(500) // the worker is idle now: every earlier wake-up is consumed
        assertTrue(mc.request(listOf(a), CellReason.NEAR, ownPoint = true).isEmpty()) // already queued: upgraded, not added
        waitFor("a loads on mobile data after the position needs it", 5_000) { mc.state(a) == CellState.Ready("v1") }
    }

    /** Settings → Offline maps: what waits is visible; "Cancel all" drops the queue, installed maps stay. */
    @Test fun waitingIsVisibleAndCancelAllDropsIt() {
        server.publish(a, "v1"); server.publish(b, "v1"); server.publish(c, "v1"); server.buildPolls = 0
        val mc = cells()
        mc.request(listOf(c), CellReason.POINT)
        waitFor("c installed") { mc.state(c) == CellState.Ready("v1") }
        val net = Net(unmetered = false)
        val mc2 = cells(net, mc.store.dataDir)
        mc2.wifiOnly = true
        mc2.request(listOf(a, b), CellReason.NEAR, ownPoint = false)
        waitFor("waiting for wifi") { mc2.state(a) is CellState.WaitingForWifi && mc2.state(b) is CellState.WaitingForWifi }
        val w = mc2.waiting()
        assertEquals(2, w.regions); assertEquals(2000L, w.knownBytes)
        assertEquals(setOf(a, b), mc2.cancelAll().toSet())
        assertEquals(0, mc2.waiting().regions)
        assertTrue(queued(mc2).isEmpty())
        assertEquals(listOf(c), mc2.installed.value.map { it.id })
    }
}

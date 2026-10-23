package com.onpath.app.cells

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Download client and the request → poll → download → join flow against a fake doorman. */
class CellDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: FakeDoorman
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)

    private class Net(@Volatile var connected: Boolean = true, @Volatile var unmetered: Boolean = true) : NetworkStatus {
        override fun connected() = connected
        override fun unmetered() = unmetered
    }

    @Before fun setUp() { server = FakeDoorman(tmp.newFolder("server")) }
    @After fun tearDown() { scope.cancel(); server.close() }

    private fun api(key: String = server.key) = CellApi(server.url, key, "0.8.0", 2000, 2000)

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timeout waiting for $what")
            Thread.sleep(20)
        }
    }

    // ------------------------------------------------------------------------------------------- CellApi

    @Test fun requestStatusManifest() {
        server.publish(a, "2026-10-19")
        val api = api()
        val r = api.request(listOf(a.id, b.id), "point")
        assertEquals("building", r.cells.first { it.id == a.id }.state)
        assertEquals("none", r.cells.first { it.id == b.id }.state)
        assertEquals(15, r.pollS)
        assertEquals("ready", api.status(listOf(a.id)).cells.single().state)
        val m = api.manifest(a)
        assertEquals(a, m.cell); assertEquals("2026-10-19", m.version); assertEquals(6, m.files.size)
        try { api.manifest(b); fail() } catch (e: CellApi.ApiException) { assertEquals(CellApi.Failure.NOT_READY, e.failure) }
        assertTrue(server.log.any { it.startsWith("POST /v1/cells/request") })
    }

    @Test fun wrongKeyAndOffline() {
        try { api("wrong").status(listOf(a.id)); fail() } catch (e: CellApi.ApiException) {
            assertEquals(CellApi.Failure.REFUSED, e.failure)
            assertEquals("Map server refused the request – update the app.", e.message)
        }
        val port = java.net.ServerSocket(0).use { it.localPort } // nothing listens there
        try { CellApi("http://127.0.0.1:$port/v1", "k", "0.8.0", 2000, 2000).status(listOf(a.id)); fail() }
        catch (e: CellApi.ApiException) { assertEquals(CellApi.Failure.UNREACHABLE, e.failure) }
        try { CellApi("http://no-such-host.invalid/v1", "k", "0.8.0", 2000, 2000).status(listOf(a.id)); fail() }
        catch (e: CellApi.ApiException) { assertTrue(e.failure == CellApi.Failure.OFFLINE || e.failure == CellApi.Failure.UNREACHABLE) }
    }

    @Test fun downloadResumesWithRange() {
        val m = server.publish(a, "v1")
        val f = m.files.first { it.name == "base.pmtiles" }
        val part = File(tmp.newFolder("dl"), "base.pmtiles.part")
        server.cutAfter = f.size / 3
        try { api().download(f, part); fail("expected a broken download") } catch (e: CellApi.ApiException) {
            assertEquals(CellApi.Failure.UNREACHABLE, e.failure)
        }
        assertTrue(part.length() in 1 until f.size)
        server.cutAfter = null
        api().download(f, part)
        assertEquals(f.size, part.length())
        assertEquals(f.sha256, CellApi.sha256(part))
        assertTrue(server.log.any { it.contains("Range:bytes=") })
    }

    @Test fun damagedDownloadIsDeleted() {
        val m = server.publish(a, "v1")
        val f = m.files.first { it.name == "overlays.pmtiles" }
        val part = File(tmp.newFolder("dl"), "x.part")
        server.corruptNext = true
        try { api().download(f, part); fail() } catch (e: CellApi.ChecksumException) { assertTrue(e.message!!.startsWith("Map download damaged")) }
        assertFalse(part.exists())
        api().download(f, part)
        assertEquals(f.sha256, CellApi.sha256(part))
    }

    // ------------------------------------------------------------------------------------------- MapCells

    private fun cells(net: NetworkStatus, data: File = tmp.newFolder("data")) =
        MapCells(CellStore(data), api(), net, scope, "0.8.0", pollDelayMs = { 30L }, backgroundPollMs = 30L).also { it.start() }

    @Test fun requestPollDownloadInstall() {
        server.publish(a, "2026-10-19"); server.buildPolls = 2
        val mc = cells(Net())
        val gen0 = mc.generation.value
        assertEquals(listOf(a), mc.request(listOf(a), CellReason.POINT))
        assertEquals(emptyList<CellId>(), mc.request(listOf(a), CellReason.POINT)) // already queued
        waitFor("cell ready") { mc.state(a) is CellState.Ready }
        assertEquals(CellState.Ready("2026-10-19"), mc.state(a))
        assertEquals(listOf(a), mc.installed.value.map { it.id })
        assertTrue(mc.generation.value > gen0)
        val dir = mc.mapDir()!!
        assertTrue(File(dir, "base.pmtiles").isFile)
        assertTrue(File(mc.store.dataDir, "addresses/${a.id}.sqlite").isFile)
        assertFalse(File(mc.store.downloadDir, a.id).exists())
        assertTrue(server.log.any { it.startsWith("GET /v1/cells/status") })
        assertEquals(emptyList<CellId>(), mc.missing(listOf(a)))
        assertEquals(emptyList<CellId>(), mc.request(listOf(a), CellReason.NEAR)) // installed
    }

    /** Owner 2026-10-21: mobile data by default – own point, neighbours and refreshes; no question. */
    @Test fun mobileDataRule() {
        server.publish(a, "v1"); server.publish(b, "v1"); server.buildPolls = 0
        val net = Net(unmetered = false)
        val mc = cells(net)
        mc.request(listOf(a), CellReason.POINT)
        mc.request(listOf(b), CellReason.NEAR, ownPoint = false)
        waitFor("own cell on mobile data") { mc.state(a) is CellState.Ready }
        waitFor("neighbour on mobile data") { mc.state(b) is CellState.Ready }
        assertEquals(emptyList<CellId>(), mc.waitingForMobileApproval().map { it.first })
        server.publish(a, "v2")
        mc.request(listOf(a), CellReason.REFRESH, ownPoint = false)
        waitFor("refresh on mobile data") { mc.state(a) == CellState.Ready("v2") }
    }

    /** "Maps only on Wi-Fi": neighbours wait, the user's yes loads them. */
    @Test fun wifiOnlyWaitsUnlessApproved() {
        server.publish(b, "v1"); server.buildPolls = 0
        val mc = cells(Net(unmetered = false))
        mc.wifiOnly = true
        mc.request(listOf(b), CellReason.NEAR, ownPoint = false)
        waitFor("neighbour waits") { mc.state(b) is CellState.WaitingForWifi }
        mc.approveMobile(listOf(b))
        waitFor("neighbour after yes") { mc.state(b) is CellState.Ready }
    }

    /** A tour runs: the newer version of an installed area waits; back in Plan it loads (no switch mid-tour). */
    /**
     * A tour runs (owner 2026-10-22): areas still download – a new one at the edge (T5) and the newer version of an
     * installed one – but the map does not switch; both are joined after the tour.
     */
    @Test fun downloadsDuringATourAreJoinedAfterIt() {
        server.publish(a, "v1"); server.publish(b, "v1"); server.buildPolls = 0
        val mc = cells(Net())
        mc.request(listOf(a), CellReason.POINT)
        waitFor("installed") { mc.state(a) == CellState.Ready("v1") }
        val gen = mc.generation.value
        mc.holdUpdates = true
        server.publish(a, "v2")
        mc.request(listOf(a), CellReason.REFRESH, ownPoint = false)
        mc.request(listOf(b), CellReason.NEAR) // T5: a new area at the edge
        waitFor("new area downloaded during the tour") { mc.state(b) == CellState.Downloaded }
        waitFor("update downloaded during the tour") { mc.state(a) == CellState.Downloaded }
        assertEquals("the map does not switch during the tour", gen, mc.generation.value)
        assertEquals(listOf(a), mc.installed.value.map { it.id })
        mc.holdUpdates = false
        waitFor("joined after the tour") { mc.state(a) == CellState.Ready("v2") && mc.state(b) == CellState.Ready("v1") }
        assertTrue(mc.generation.value > gen)
        assertEquals(setOf(a, b), mc.installed.value.map { it.id }.toSet())
    }

    /** The app is not on screen: only a tour (recording service) sends and polls requests. */
    @Test fun inTheBackgroundOnlyATourAsks() {
        server.publish(a, "v1"); server.buildPolls = 1
        val mc = cells(Net())
        mc.setForeground(false)
        mc.request(listOf(a), CellReason.NEAR, ownPoint = true)
        Thread.sleep(600)
        assertFalse("no request without a tour", server.log.any { it.startsWith("POST /v1/cells/request") })
        mc.tourInBackground = true
        waitFor("built and loaded in the background") { mc.state(a) == CellState.Ready("v1") }
    }

    /** Owner 2026-10-23: phone put aside while the map loads – MapLoadService keeps the polling going. */
    @Test fun loadingServiceKeepsItGoingInTheBackground() {
        server.publish(a, "v1"); server.buildPolls = 1
        val mc = cells(Net())
        mc.setForeground(false)
        mc.request(listOf(a), CellReason.POINT, ownPoint = true)
        Thread.sleep(600)
        assertFalse("nothing asked while the app is away", server.log.any { it.startsWith("POST /v1/cells/request") })
        mc.loadInBackground = true
        waitFor("built and loaded with the screen off") { mc.state(a) == CellState.Ready("v1") }
    }

    @Test fun wifiOnlyWaitsUntilWifi() {
        server.publish(a, "v1"); server.buildPolls = 0
        val net = Net(unmetered = false)
        val mc = cells(net)
        mc.wifiOnly = true
        mc.request(listOf(a), CellReason.POINT)
        waitFor("waiting for wifi") { mc.state(a) is CellState.WaitingForWifi }
        net.unmetered = true; mc.networkChanged()
        waitFor("loaded on wifi") { mc.state(a) is CellState.Ready }
    }

    @Test fun offlineThenOnline() {
        server.publish(a, "v1"); server.buildPolls = 0
        val net = Net(connected = false)
        val mc = cells(net)
        mc.request(listOf(a), CellReason.POINT)
        waitFor("offline state") { mc.state(a) is CellState.Offline }
        net.connected = true; mc.networkChanged()
        waitFor("loaded when online") { mc.state(a) is CellState.Ready }
    }

    @Test fun serverFailureIsShownPlainly() {
        server.failing[a.id] = "Source data not available for this area yet"
        val mc = cells(Net())
        mc.request(listOf(a), CellReason.POINT)
        waitFor("failed") { mc.state(a) is CellState.Failed }
        // the server's reason goes to Diagnostics only; the user reads one plain sentence (text review 2026-10-23)
        assertEquals("This map area could not be prepared. Routes there are straight lines for now.",
            (mc.state(a) as CellState.Failed).message)
    }

    /** A refresh downloads only a newer version and replaces the cell. */
    @Test fun refreshReplacesWithNewerVersion() {
        server.publish(a, "v1"); server.buildPolls = 0
        val mc = cells(Net())
        mc.request(listOf(a), CellReason.POINT)
        waitFor("v1") { mc.state(a) == CellState.Ready("v1") }
        // same version on the server: refresh ends without download
        mc.request(listOf(a), CellReason.REFRESH)
        waitFor("refresh checked") { mc.state(a) == CellState.Ready("v1") && !File(mc.store.root, "requests.json").readText().contains(a.id) }
        server.publish(a, "v2")
        mc.request(listOf(a), CellReason.REFRESH)
        waitFor("v2") { mc.state(a) == CellState.Ready("v2") }
        assertEquals("v2", mc.installed.value.single().version)
    }

    /**
     * Field report 2026-10-21: cells built before the "streets" overlay (content schema 1, version = OSM date) are found
     * as outdated and loaded again as soon as the server has the schema-2 build – also on mobile data.
     */
    @Test fun outdatedSchemaIsLoadedAgain() {
        assertEquals(1, CellApi.schemaOf("2026-10-20")); assertEquals(2, CellApi.schemaOf("2026-10-20-s2"))
        assertEquals(3, CellApi.CELL_SCHEMA)
        server.publish(a, "2026-10-20"); server.buildPolls = 0
        val mc = cells(Net(unmetered = false))
        mc.request(listOf(a), CellReason.POINT)   // own point: allowed on mobile data
        waitFor("old") { mc.state(a) == CellState.Ready("2026-10-20") }
        assertEquals(listOf(a), mc.outdated())
        server.publish(a, "2026-10-20-s3")
        mc.request(mc.outdated(), CellReason.UPDATE, ownPoint = true)
        waitFor("schema 3") { mc.state(a) == CellState.Ready("2026-10-20-s3") }
        assertEquals(emptyList<CellId>(), mc.outdated())
    }

    /**
     * Fresh-install simulation (owner 2026-10-21): empty phone → own point → the server builds → download over mobile
     * data (own point) → map installed; "Clear all" deletes everything → asked again → installed again, same version.
     */
    @Test fun freshInstallAndResetLoadTheMapAgain() {
        server.publish(a, "2026-10-21-s3"); server.buildPolls = 2
        val mc = cells(Net(unmetered = false))
        assertEquals(null, mc.mapDir())
        mc.request(listOf(a), CellReason.POINT)
        waitFor("built + downloaded") { mc.state(a) == CellState.Ready("2026-10-21-s3") }
        assertTrue(File(mc.mapDir()!!, "base.pmtiles").isFile)
        assertEquals(emptyList<CellId>(), mc.outdated())
        // Settings → Offline maps → Clear all (replaced Diagnostics → Reset maps, owner 2026-10-22)
        assertEquals(listOf(a), mc.clearAll())
        waitFor("deleted") { mc.installed.value.isEmpty() }
        assertEquals(listOf(a), mc.missing(listOf(a)))
        mc.request(listOf(a), CellReason.POINT)
        waitFor("loaded again") { mc.state(a) == CellState.Ready("2026-10-21-s3") && mc.installed.value.size == 1 }
    }

    /**
     * Modal → Cancel (owner 2026-10-21: "Cancel = stop"): a cell still being built is dropped – no more polling, no
     * download, no state – even when the server finishes it; asking again (a point / search there) loads it.
     */
    @Test fun cancelStopsLoadingAndAskingAgainLoads() {
        server.publish(a, "v1"); server.buildPolls = 1_000_000
        val mc = cells(Net())
        mc.request(listOf(a), CellReason.POINT)
        waitFor("building") { mc.state(a) is CellState.Building }
        mc.cancel(listOf(a))
        assertEquals(CellState.None, mc.state(a))
        server.buildPolls = 0 // the server finishes its build anyway
        val polls = server.log.size
        Thread.sleep(400)
        assertEquals(CellState.None, mc.state(a))
        assertTrue(mc.installed.value.isEmpty())
        assertFalse(server.log.drop(polls).any { it.contains("/files/") || it.contains("/manifest") })
        assertEquals(listOf(a), mc.missing(listOf(a)))
        assertEquals(listOf(a), mc.request(listOf(a), CellReason.POINT))
        waitFor("loaded after asking again") { mc.state(a) == CellState.Ready("v1") }
    }

    /** Requests survive a restart; a complete download not yet joined is joined at start. */
    @Test fun resumeAfterRestart() {
        server.publish(a, "v1"); server.buildPolls = 0
        val data = tmp.newFolder("data2")
        // run 1: offline, the request is saved
        val first = MapCells(CellStore(data), api(), Net(connected = false), scope, "0.8.0", pollDelayMs = { 30L }).also { it.start() }
        first.request(listOf(a), CellReason.POINT)
        waitFor("offline") { first.state(a) is CellState.Offline }
        // a verified download of b lies on disk (the app died before joining it)
        val store = CellStore(data)
        CellFixtures.cell(File(File(store.downloadDir, b.id), "v1"), b, "v1")
        // run 2: online
        val second = MapCells(CellStore(data), api(), Net(), scope, "0.8.0", pollDelayMs = { 30L }).also { it.start() }
        waitFor("b joined at start") { second.installed.value.any { it.id == b } }
        waitFor("a loaded after restart") { second.state(a) is CellState.Ready }
        assertNotNull(second.mapDir())
    }

    @Test fun deleteCell() {
        server.publish(a, "v1"); server.buildPolls = 0
        val mc = cells(Net())
        mc.request(listOf(a), CellReason.POINT)
        waitFor("ready") { mc.state(a) is CellState.Ready }
        mc.delete(listOf(a))
        waitFor("deleted") { mc.installed.value.isEmpty() }
        assertEquals(CellState.None, mc.state(a))
        assertEquals(null, mc.mapDir())
    }

    /**
     * Clean up / Clear all (owner 2026-10-22): removal goes through the store generation path – batched into one
     * generation, not while the map is dragged, not during a tour (the map does not switch under the user's feet).
     */
    @Test fun removalWaitsForTheTourAndTheGestureAndIsBatched() {
        server.publish(a, "v1"); server.publish(b, "v1"); server.buildPolls = 0
        val mc = cells(Net())
        mc.request(listOf(a, b), CellReason.POINT)
        waitFor("both installed") { mc.installed.value.size == 2 }
        val gen = mc.generation.value
        mc.holdUpdates = true
        mc.delete(listOf(a), "clean up")
        Thread.sleep(300)
        assertEquals("nothing removed during the tour", gen, mc.generation.value)
        assertEquals(setOf(a), mc.removing())
        mc.userBusy = true
        mc.delete(listOf(b), "clean up")
        mc.holdUpdates = false
        Thread.sleep(300)
        assertEquals("nothing removed while dragging", gen, mc.generation.value)
        mc.userBusy = false
        waitFor("removed") { mc.installed.value.isEmpty() }
        assertEquals("one generation for both", gen + 1, mc.generation.value)
        assertEquals(emptySet<CellId>(), mc.removing())
    }

    @Test fun clearAllRemovesEveryAreaAndEveryRequest() {
        server.publish(a, "v1"); server.publish(b, "v1"); server.buildPolls = 0
        val mc = cells(Net())
        mc.request(listOf(a), CellReason.POINT)
        waitFor("installed") { mc.installed.value.size == 1 }
        server.buildPolls = 1_000_000
        mc.request(listOf(b), CellReason.NEAR)
        waitFor("b asked") { mc.state(b) != CellState.None }
        assertEquals(listOf(a), mc.clearAll())
        waitFor("cleared") { mc.installed.value.isEmpty() }
        assertEquals(CellState.None, mc.state(a)); assertEquals(CellState.None, mc.state(b))
        assertEquals(0, mc.waiting().regions)
        assertEquals(null, mc.mapDir())
        assertEquals(listOf(a), mc.missing(listOf(a))) // loads again when needed
    }
}

package com.onpath.app.ui

import com.onpath.app.cells.CellApi
import com.onpath.app.cells.CellFixtures
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellJob
import com.onpath.app.cells.CellReason
import com.onpath.app.cells.CellState
import com.onpath.app.cells.CellStore
import com.onpath.app.cells.CellWhy
import com.onpath.app.cells.FakeDoorman
import com.onpath.app.cells.MapCells
import com.onpath.app.cells.NetworkStatus
import com.onpath.app.cells.PmTiles
import com.onpath.app.net.HttpDownload
import com.onpath.app.testkit.FlakyLink
import com.onpath.app.testkit.ProblemsRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The network goes away in the middle of a map-area download, as the user sees it (hard cases,
 * docs/team/items/2026-11-24-hard-case-test-coverage.md #5; C6 / C13). The real download path: [MapCells] with its
 * worker, [CellApi] and [HttpDownload] over a real socket to [FakeDoorman]; between them a [FlakyLink] the test breaks
 * mid-file (cut, then refused) or lets go silent. What the user is told is the card the map shows ([CellCards] from
 * the cell states). The phone's own network callback is [Net] + [MapCells.networkChanged], as AppCells wires it.
 *
 * Not proven here: the screen itself, the notification of MapLoadService, the background sign (app: BackgroundDownloads).
 */
class MapAreaNetworkLostFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val doorman by lazy { FakeDoorman(tmp.newFolder("server"), buildPolls = 0) }
    private val link by lazy { FlakyLink(doorman.url.substringAfterLast(':').substringBefore('/').toInt()) }
    private val net = Net()
    /** Installed first: the map the user already has. */
    private val home = CellId(543, 361)
    /** The area the user asks for while the network goes. */
    private val area = CellId(544, 361)

    private class Net(@Volatile var connected: Boolean = true) : NetworkStatus {
        override fun connected() = connected
        override fun unmetered() = true
    }

    @After fun tearDown() { scope.cancel(); link.close(); doorman.close() }

    private fun cells(readTimeoutMs: Int) = MapCells(CellStore(tmp.newFolder("data")),
        CellApi("http://127.0.0.1:${link.port}/v1", doorman.key, "0.8.0", 2000, readTimeoutMs), net, scope, "0.8.0",
        pollDelayMs = { 30L }, backgroundPollMs = 30L, regionPackages = false).also { it.start() }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timeout waiting for $what")
            Thread.sleep(10)
        }
    }

    /** The card the map shows for the area the user asked for. */
    private fun card(mc: MapCells) = CellCards.build(CellJob(listOf(area), area, CellWhy.TAPPED), mc.states.value,
        mc.installed.value.associate { it.id to it.totalBytes }, available = true, wifiOnly = false, mobileDeclined = false)

    /** Every state the area had, with whether the network was back by then. */
    private val seen = CopyOnWriteArrayList<Pair<Boolean, CellState?>>()
    @Volatile private var back = false
    private fun watch(mc: MapCells) {
        scope.launch { mc.states.collect { seen += back to it[area] } }
        scope.launch { mc.installed.collect { l -> if (l.any { it.id == area }) seen += back to CellState.Ready("installed") } }
    }

    private fun fileLines(c: CellId, name: String) = doorman.log.filter { it.startsWith("GET /v1/files/cells/f2/${c.path}/") && it.contains("/$name") }

    /** The tile of [c] at z10 in the map the user sees, as the server built it. */
    private fun assertTileOnTheMap(mc: MapCells, c: CellId, version: String) {
        PmTiles.Reader(File(mc.mapDir()!!, "terrain.pmtiles")).use { r ->
            assertArrayEquals("$c on the map", CellFixtures.content("$c@$version", "terrain.pmtiles", 10, c.x, c.y), r.tile(10, c.x, c.y))
        }
    }

    private fun leftovers(mc: MapCells) = mc.store.root.walk().filter { it.name.endsWith(".part") }.map { it.path }.toList()

    @Test fun cutMidFileThenNoNetworkThenBack_theOldMapStaysAndTheAreaResumes() {
        doorman.publish(home, "v1")
        val m = doorman.publish(area, "v1")
        val mc = cells(readTimeoutMs = 10_000)
        mc.request(listOf(home), CellReason.POINT)
        waitFor("the map the user has") { mc.state(home) == CellState.Ready("v1") }
        val gen = mc.generation.value
        watch(mc)

        // the user asks for the area; the link breaks in the middle of its 4th file (terrain) and is gone after it
        val terrain = m.files.first { it.name == "terrain.pmtiles" }
        val firstThree = m.files.takeWhile { it.name != "terrain.pmtiles" }.sumOf { it.size }
        val total = m.totalSize
        link.hold = CountDownLatch(1)
        link.breakNext(FlakyLink.Cut.HALF) { it.contains("/${area.path}/v1/terrain.pmtiles") }
        mc.request(listOf(area), CellReason.POINT)
        assertTrue("the cut came", link.halfSent.await(15, TimeUnit.SECONDS))

        // progress shows while the bytes stop coming: three files in, the card counts them
        waitFor("progress of the three files") { (mc.state(area) as? CellState.Downloading)?.doneBytes == firstThree }
        val working = card(mc) as CellCardUi.Working
        assertEquals("Downloading 1 of 1 · 1 MB", working.title) // a few KB here: at least 1 MB is shown
        assertEquals(firstThree.toFloat() / total, working.progress!!, 0.001f)
        link.hold!!.countDown() // the connection drops

        // told plainly, at once (the stream ended early: an IOException on the JVM as on Android's HTTP stack)
        waitFor("told") { mc.state(area) is CellState.Offline }
        val stopped = card(mc) as CellCardUi.Offline
        assertEquals("Connection to the map server broke – trying again.", stopped.title)
        // what is on the phone: the three files whole, half of terrain as a part to resume from, nothing installed
        val part = File(File(mc.store.downloadDir, area.id), "v1.part")
        val half = File(part, "terrain.pmtiles.part")
        assertEquals(terrain.size / 2 + 0L, half.length()) // FlakyLink sends half of the body
        assertEquals(listOf("base.pmtiles", "contours.pmtiles", "overlays.pmtiles"),
            part.listFiles()!!.filter { it.isFile && !it.name.endsWith(".part") }.map { it.name }.sorted())
        assertFalse(File(File(mc.store.downloadDir, area.id), "v1").exists()) // never a complete folder from a part

        // the phone still says "connected" (the radio has not noticed): the next try is refused – told again, no hang
        mc.networkChanged()
        waitFor("refused told") { (mc.state(area) as? CellState.Offline)?.message == "Map server not reachable – trying again." }
        assertEquals("Map server not reachable – trying again.", card(mc)!!.title)

        // then the network is gone: one plain line
        net.connected = false; mc.networkChanged()
        waitFor("no connection") { (mc.state(area) as? CellState.Offline)?.message?.startsWith("No connection") == true }
        assertEquals(CellCards.OFFLINE_TITLE, card(mc)!!.title)

        // all that time the map the user had works and is the only one: same generation, the area nowhere
        assertEquals(CellState.Ready("v1"), mc.state(home))
        assertEquals(listOf(home), mc.installed.value.map { it.id })
        assertEquals(gen, mc.generation.value)
        assertEquals(setOf(home), mc.store.current()!!.cellIds)
        assertTileOnTheMap(mc, home, "v1")
        assertTrue("nothing of the area shown as ready while broken: $seen", seen.none { (b, s) -> !b && (s is CellState.Ready || s == CellState.Installing) })

        // the network comes back
        back = true
        link.up(); net.connected = true; mc.networkChanged()
        waitFor("the area ready") { mc.state(area) == CellState.Ready("v1") }

        // resumed where it stopped: terrain from its half (Range), the three whole files not loaded again
        assertEquals(listOf("GET /v1/files/cells/f2/${area.path}/v1/terrain.pmtiles Range:bytes=${terrain.size / 2}-"),
            fileLines(area, "terrain.pmtiles").drop(1))
        for (f in listOf("base.pmtiles", "overlays.pmtiles", "contours.pmtiles")) assertEquals(f, 1, fileLines(area, f).size)
        // complete and verified, in one new map generation, the old area unchanged beside it
        assertEquals(setOf(home, area), mc.installed.value.map { it.id }.toSet())
        assertEquals(gen + 1, mc.generation.value)
        assertTileOnTheMap(mc, area, "v1")
        assertTileOnTheMap(mc, home, "v1")
        assertTrue(card(mc) is CellCardUi.Done)
        // nothing stale left: no download folder, no part, no request
        assertFalse(File(mc.store.downloadDir, area.id).exists())
        assertEquals(emptyList<String>(), leftovers(mc))
        assertFalse(File(mc.store.root, "requests.json").readText().contains(area.id))

        // the problem log: the stop and the refused retry as INFO, nothing worse; start twice, finished once
        val said = problems.said("cells")
        assertTrue(said.toString(), "Map cell ${area.id} download stopped (UNREACHABLE)" in said)
        assertTrue(said.toString(), "Map server not reachable (UNREACHABLE); retrying" in said)
        assertTrue(problems.said("cells", com.onpath.app.debug.Problems.Level.WARN).toString(), problems.none("cells"))
        val trace = problems.traced("cells")
        assertEquals(trace.toString(), 2, trace.count { it.startsWith("Download start: ${area.id}") })
        assertEquals(trace.toString(), 1, trace.count { it.startsWith("Download finished: ${area.id}") })
    }

    /**
     * The link goes silent in the middle of a newer version of the installed area (no reset, no answer – a phone in a
     * dead spot): the client gives up after its read timeout (here 2 s; 30 s on the phone, CellApi) and the user is told –
     * the card never shows "downloading" forever. The old version stays on the map until the new one is complete.
     */
    @Test fun aSilentLinkIsToldNotAHang_theOldVersionStaysUntilTheNewIsComplete() {
        doorman.publish(home, "v1")
        val mc = cells(readTimeoutMs = 2_000)
        mc.request(listOf(home), CellReason.POINT)
        waitFor("v1 on the map") { mc.state(home) == CellState.Ready("v1") }
        val gen = mc.generation.value

        doorman.publish(home, "v2")
        link.hold = CountDownLatch(1) // never let go: silent until the client gives up
        link.breakNext(FlakyLink.Cut.HALF, thenDown = false) { it.contains("/${home.path}/v2/base.pmtiles") }
        mc.request(listOf(home), CellReason.REFRESH, ownPoint = false)
        assertTrue(link.halfSent.await(15, TimeUnit.SECONDS))
        val silentAt = System.currentTimeMillis()

        // told after the read timeout, not left "downloading"; the installed version untouched meanwhile
        waitFor("told", 10_000) { mc.state(home) is CellState.Offline }
        val waited = System.currentTimeMillis() - silentAt
        assertTrue("gave up after ${waited} ms", waited in 1_500..8_000)
        assertEquals("Map server not reachable – trying again.", (mc.state(home) as CellState.Offline).message)
        assertEquals("v1", mc.installed.value.single().version)
        assertEquals(gen, mc.generation.value)
        assertTileOnTheMap(mc, home, "v1")
        link.hold!!.countDown()

        // the network is fine again: the next try (the phone's network callback) finishes v2 from where it stopped
        mc.networkChanged()
        waitFor("v2") { mc.state(home) == CellState.Ready("v2") }
        assertTrue(fileLines(home, "base.pmtiles").last().contains("Range:bytes="))
        assertEquals("v2", mc.installed.value.single().version)
        assertEquals(gen + 1, mc.generation.value)
        assertTileOnTheMap(mc, home, "v2")
        assertEquals(emptyList<String>(), leftovers(mc))
        assertFalse(File(mc.store.downloadDir, home.id).exists())
        assertTrue(problems.said("cells").toString(), "Map cell ${home.id} download stopped (UNREACHABLE)" in problems.said("cells"))
        assertTrue(problems.none("cells"))
    }
}

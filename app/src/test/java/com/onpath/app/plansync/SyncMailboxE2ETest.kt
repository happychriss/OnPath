package com.onpath.app.plansync

import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import com.onpath.app.sync.AesGcm
import com.onpath.app.sync.Mailbox
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncRound
import com.onpath.app.sync.TrackCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * End to end against the REAL mailbox: web/host/src/sync.js (the Cloudflare Worker's code) in Node with SQLite as D1
 * (tools/sync/mailbox-server.mjs), the app's HTTP client ([HttpMailbox]), the phone's pass with javax.crypto and a
 * "browser" with the shared ledger + round. Run it with `tools/sync/e2e.sh` (copies sync.js from the web planner
 * branch to app/build/sync-e2e/); without Node ≥ 22.5 or sync.js it is skipped (normal unit test runs).
 */
class SyncMailboxE2ETest {
    @get:Rule val tmp = TemporaryFolder()
    private var node: Process? = null
    private lateinit var base: String

    private val secret = ByteArray(32) { (it * 11 + 2).toByte() }
    private val keys = SyncKeys(secret)
    private var clock = 1_793_100_000_000L
    private val rnd = Random(7)

    @Before fun startMailbox() {
        val js = System.getenv("ONPATH_SYNC_JS")?.let(::File) ?: File("build/sync-e2e/src/sync.js")
        val migrations = File(js.absoluteFile.parentFile?.parentFile, "migrations")
        val server = File("../tools/sync/mailbox-server.mjs")
        assumeTrue("sync.js not here – run tools/sync/e2e.sh", js.isFile && migrations.isDirectory && server.isFile)
        val p = try {
            ProcessBuilder("node", server.path, js.path, migrations.path).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            assumeTrue("node not found: ${e.message}", false); return
        }
        node = p
        val reader = p.inputStream.bufferedReader()
        var port: Int? = null
        val deadline = System.currentTimeMillis() + 15_000
        while (port == null && System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            port = Regex("""listening (\d+)""").find(line)?.groupValues?.get(1)?.toInt()
        }
        assertNotNull("the Node mailbox did not start", port)
        // keep draining Node's output (warnings) so it never blocks
        Thread { try { reader.forEachLine { } } catch (_: java.io.IOException) { /* expected: process ended */ } }.apply { isDaemon = true }.start()
        base = "http://127.0.0.1:$port/"
    }

    @After fun stopMailbox() {
        node?.let { it.destroy(); it.waitFor(5, TimeUnit.SECONDS) }
    }

    private val a = GeoPoint(46.67171, 11.15003)
    private val b = GeoPoint(46.68512, 11.13470)
    private val c = GeoPoint(46.69020, 11.16110)

    @Test fun phoneAndBrowserSyncThroughTheRealMailbox() {
        val store = FakeTourStore()
        val files = SyncFiles(tmp.newFolder("sync")).also { it.savePairing(secret, clock) }
        val phone = PlanSyncPass(files, store, { HttpMailbox(base, it) }, { n -> rnd.nextBytes(n) }, { clock }, "Pixel 8",
            { if (it == SyncItem.TRACK) "Track" else "Route" }, JcaAead)
        fun phoneSync() = phone.run { false }!!.also { clock += 1000 }

        val web = SyncFormat.newDevice(ByteArray(8) { 4 })
        val browser = SyncLedger.empty()
        var n = 0
        val browserRound = SyncRound(keys, HttpMailbox(base, keys), { ByteArray(12) { (n++).toByte() } }, AesGcm)
        fun browserSync() = browserRound.run(browser).also { clock += 1000 }
        browser.hello(web, "Chrome on Linux", clock)

        // 1. the phone's saved route and imported track reach the browser (the first call claims the mailbox)
        store.savePlanned("Waalweg", listOf(a, b, c), Activity.HIKE, 3, names = mapOf(0 to "Algund"), pinned = setOf(0))
        val trackLine = List(200) { i -> TrackPoint((4_660_000 + i * 30) / 1e5, (1_110_000 + i * 20) / 1e5, 500.0 + i) }
        store.saveImported("Ultental GPX", listOf(trackLine), activity = Activity.MTB)
        val first = phoneSync()
        assertEquals(SyncRound.Outcome.Done(0, 3, 0, 0), first.outcome)
        assertTrue(browserSync() is SyncRound.Outcome.Done)
        assertEquals(listOf("Waalweg"), browser.plans().map { it.title })
        assertEquals(listOf(a, b, c), PlanCode.decode(browser.plans().single().plan!!)!!.points)
        assertEquals(trackLine.map { it.lat }, TrackCode.of(browser.tracks().single().track)!!.segments.single().map { it.lat })
        assertEquals("Pixel 8", browser.devices(web).single().name)
        // the server holds nothing readable
        val stored = URL(base + "_test/blobs").readText()
        assertFalse(stored.contains("Waalweg") || stored.contains("Ultental") || stored.contains("Pixel"))

        // 2. the browser renames the route and adds one → renamed and added on the phone (to be routed)
        val routeId = browser.plans().single().id
        browser.rename(routeId, "Algunder Waalweg", clock, web)
        val newId = SyncFormat.newId(ByteArray(16) { 3 })
        browser.save(newId, PlanCode.encode(SharedPlan(listOf(a, c), Activity.BIKE_TOURING)), "Meran → Lana", clock, web)
        browserSync()
        val second = phoneSync()
        assertEquals("Peer name from the hello", "Chrome on Linux", second.peer)
        assertEquals(setOf("Algunder Waalweg", "Ultental GPX", "Meran → Lana"), store.live().map { it.name }.toSet())
        val added = store.byName("Meran → Lana")
        assertEquals(TourKind.PLANNED, added.kind)
        assertEquals(listOf(added.id), second.toRoute)

        // 3. both change the route while apart: the newer wins, the other stays as a copy – on both sides
        store.update(store.byName("Algunder Waalweg")) { it.copy(name = "Phone name") }
        browser.rename(routeId, "Browser name", clock + 60_000, web)
        browserSync()
        phoneSync(); browserSync()
        val want = setOf("Browser name", "Phone name (copy)", "Meran → Lana")
        assertEquals(want, store.live().filter { it.kind == TourKind.PLANNED }.map { it.name }.toSet())
        assertEquals(want, browser.plans().map { it.title }.toSet())

        // 4. a 409 at the mailbox (the browser writes between the phone's read and write): read again, nothing lost
        val http = HttpMailbox(base, keys)
        var raced = false
        val racy = object : Mailbox by http {
            override fun write(id: String, base: Long, blob: ByteArray): Mailbox.Reply {
                if (!raced) { raced = true; browser.rename(newId, "Lana (browser)", clock + 120_000, web); browserSync() }
                return http.write(id, base, blob)
            }
        }
        store.update(added) { it.copy(name = "Lana (phone)") }
        val racedPass = PlanSyncPass(files, store, { racy }, { k -> rnd.nextBytes(k) }, { clock }, "Pixel 8", { "Route" }, JcaAead)
        val r4 = racedPass.run { false }!!
        assertTrue(raced)
        assertTrue(r4.outcome is SyncRound.Outcome.Done)
        browserSync()
        assertTrue(store.live().map { it.name }.containsAll(listOf("Lana (browser)", "Lana (phone) (copy)")))
        assertEquals(store.live().filter { it.kind == TourKind.PLANNED }.map { it.name }.toSet(), browser.plans().map { it.title }.toSet())
        // a stale write is refused with the current revision
        val stale = http.write(newId, 1, ByteArray(40))
        assertEquals(409, stale.status)
        assertTrue(stale.rev > 1)

        // 5. deleted in the browser → recycle bin on the phone
        browser.delete(browser.tracks().single().id, clock, web)
        browserSync(); phoneSync()
        assertNotNull(store.byName("Ultental GPX").deletedMs)

        // 6. unpair on the phone: the mailbox ends (410 for everyone), the routes stay on both sides
        assertEquals(200, phone.unpair())
        assertEquals(410, http.read(0).status)
        assertEquals(SyncRound.Outcome.Ended, browserSync())
        assertTrue(store.live().size >= 4)
        assertTrue(browser.plans().size >= 3)
        assertEquals(410, HttpMailbox(base, keys).end().status)
    }

    @Test fun offlineAndWrongTokenAnswers() {
        // nothing listens on this port: offline, never a crash
        val dead = HttpMailbox("http://127.0.0.1:1/", keys)
        assertEquals(Mailbox.Reply.OFFLINE, dead.read(0).status)
        // a mailbox claimed with one token refuses another holder of the same id (not possible with real codes;
        // the server's rule): reading an unknown mailbox is 404
        val other = HttpMailbox(base, SyncKeys(ByteArray(32) { 1 }))
        assertEquals(404, other.read(0).status)
        assertEquals(201, other.claim().status)
        assertEquals(200, other.claim().status)
        val page = other.read(0)
        assertEquals(200, page.status)
        assertEquals(0, page.page!!.items.size)
    }
}

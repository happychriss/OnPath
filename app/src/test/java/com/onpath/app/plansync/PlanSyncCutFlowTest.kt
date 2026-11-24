package com.onpath.app.plansync

import com.onpath.app.data.Tour
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import com.onpath.app.sync.AesGcm
import com.onpath.app.sync.Mailbox
import com.onpath.app.sync.MemoryServer
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncMerge
import com.onpath.app.sync.SyncRound
import com.onpath.app.testkit.FlakyLink
import com.onpath.app.testkit.ProblemsRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.random.Random

/**
 * Plan sync phone ⇄ browser when the network breaks in the middle of a pass (hard cases,
 * docs/team/items/2026-11-24-hard-case-test-coverage.md #8; C16). SyncRulesRandomTest takes whole rounds offline at
 * the mailbox's door; here the break is inside a round, on the wire: a pull page cut mid-body, the link refused, the
 * server busy on a later page, and a push the server stored whose answer never came. The phone is the real
 * [PlanSyncPass] with the real [HttpMailbox] over a socket; the mailbox has sync.js's rules ([MemoryServer], 2 items a
 * page) behind [WireMailbox] (sync.js's wire format); the break is a [FlakyLink]. The browser is the shared ledger +
 * round (the web planner runs the same Kotlin), straight at the mailbox. Back-off is what the app's driver feeds
 * [SyncPoll] from each pass ([SyncDriver.onReport]) and the busy pause [SyncRound.nextRoundMs] ([PlanSync] quiet time).
 *
 * Not proven here: PlanSync's thread and network callback (Android), the Settings line.
 */
class PlanSyncCutFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()

    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val keys = SyncKeys(secret)
    private val server = MemoryServer(page = 2)
    private val wire = WireMailbox(server.box(keys), keys)
    private val link = FlakyLink(wire.port)
    private val store = FakeTourStore()
    private var clock = 1_793_200_000_000L
    private val rnd = Random(5)
    private val files by lazy { SyncFiles(tmp.newFolder("sync")).also { it.savePairing(secret, clock) } }
    private val phone by lazy {
        PlanSyncPass(files, store, { HttpMailbox("http://127.0.0.1:${link.port}/", it) }, { n -> rnd.nextBytes(n) }, { clock },
            "Pixel 8", { if (it == SyncItem.TRACK) "Track" else "Route" }, AesGcm)
    }
    /** The app's poll rule, fed as SyncDriver.onReport feeds it. */
    private val poll = SyncPoll()
    private fun phoneSync(): SyncPassResult {
        val r = phone.run { false }!!
        poll.passEnded(clock, SyncPoll.ok(SyncReport(manual = false, result = r)))
        clock += 1000
        return r
    }

    private val web = SyncFormat.newDevice(ByteArray(8) { 9 })
    private val browser = SyncLedger.empty()
    private var n = 0
    private fun browserSync() = SyncRound(keys, server.box(keys), { ByteArray(12) { (n++).toByte() } }).run(browser).also { clock += 1000 }

    @After fun tearDown() { link.close(); wire.close() }

    private val a = GeoPoint(46.67171, 11.15003)
    private val b = GeoPoint(46.68512, 11.13470)
    private fun id(k: Int) = SyncFormat.newId(ByteArray(16) { (k + 40).toByte() })
    private fun points(k: Int) = listOf(GeoPoint(46.6 + k / 100.0, 11.1), GeoPoint(46.61 + k / 100.0, 11.12), GeoPoint(46.62 + k / 100.0, 11.11))
    private fun plan(p: List<GeoPoint>) = PlanCode.encode(SharedPlan(p, Activity.HIKE, 2))

    private fun pts(t: Tour) = t.waypoints.map { GeoPoint(it.lat, it.lon) }
    /** The browser's plans that reached the phone: each whole (every point as saved) and once. */
    private fun fromBrowser(): List<String> {
        val live = store.live().filter { it.name.startsWith("B") }
        assertEquals("no route twice: ${live.map { it.name }}", live.map { it.name }.distinct().size, live.size)
        for (t in live) assertEquals("${t.name} whole", PlanCode.decode(plan(points(t.name.drop(1).toInt())))!!.points, pts(t))
        return live.map { it.name }.sorted()
    }

    @Test fun pullCutMidPageThenRefusedThenBusyThenAnswerLost_nothingHalfNothingTwiceBothAgree() {
        // paired and in step: the phone's route is in the browser
        val own = store.savePlanned("Waalweg", listOf(a, b))
        assertTrue(phoneSync().outcome is SyncRound.Outcome.Done)
        browserSync()
        assertEquals(listOf("Waalweg"), browser.plans().map { it.title })
        assertEquals(SyncPoll.BASE_MS, poll.intervalMs)

        // the browser saves five plans (three pages for the phone); the user renames the route on the phone meanwhile
        for (k in 1..5) browser.save(id(k), plan(points(k)), "B$k", clock, web)
        browserSync()
        store.update(own) { it.copy(name = "Algunder Waalweg") }

        // 1. a page's body is cut in the middle, the link gone after it (page 1: the phone's own two items read back,
        // page 2: B1 B2, page 3: B3 B4 – cut, page 4: B5)
        link.breakNext(FlakyLink.Cut.HALF, nth = 3) { it.startsWith("GET /sync/v1/") }
        val cut = phoneSync()
        assertEquals(SyncRound.Outcome.Offline, cut.outcome)
        assertEquals(1, link.cuts.get())
        assertEquals("the first page came in, whole; nothing of the cut one", listOf("B1", "B2"), fromBrowser())
        assertEquals(listOf(a, b), pts(store.byName("Algunder Waalweg"))) // the local route as the user has it
        browserSync()
        assertEquals("the pull failed before the push: the rename waits", "Waalweg", browser.plans().single { !it.title!!.startsWith("B") }.title)
        assertEquals(2 * SyncPoll.BASE_MS, poll.intervalMs)
        assertTrue(problems.said("sync").toString(), problems.said("sync").any { it.startsWith("Sync server not reached (GET)") })

        // 2. nothing answers (refused): nothing changes, the poll waits longer. The JDK names it ConnectException, or
        // SocketException when it first tries a kept-alive socket the link has closed (timing; red on GitHub 2026-11-25)
        fun notReached() = problems.said("sync").count { it.startsWith("Sync server not reached (GET)") }
        val saidBefore = notReached(); val reachedBefore = link.requests.size
        val refused = phoneSync()
        assertEquals(SyncRound.Outcome.Offline, refused.outcome)
        assertEquals(listOf("B1", "B2"), fromBrowser())
        assertEquals(4 * SyncPoll.BASE_MS, poll.intervalMs)
        assertTrue(problems.said("sync").toString(), notReached() > saidBefore)
        assertEquals("refused: no request reached the link", reachedBefore, link.requests.size)

        // 3. back, but the server is busy on the second page of the pass: the page before it counts, a quiet pause
        link.up()
        wire.busyAt = wire.calls + 2
        val busy = phoneSync()
        val o = busy.outcome as SyncRound.Outcome.Refused
        assertEquals(503, o.status)
        assertTrue(SyncRound.busy(o.status))
        assertEquals(SyncRound.RETRY_MS, SyncRound.nextRoundMs(o, failures = 1))
        assertEquals(listOf("B1", "B2", "B3", "B4"), fromBrowser())
        assertEquals(8 * SyncPoll.BASE_MS, poll.intervalMs)

        // 4. the last page comes; the rename goes up and the server stores it – its answer is lost, the link gone
        link.breakNext(FlakyLink.Cut.DROP) { it.startsWith("PUT /sync/v1/") }
        val lost = phoneSync()
        assertEquals(SyncRound.Outcome.Offline, lost.outcome)
        assertEquals(listOf("B1", "B2", "B3", "B4", "B5"), fromBrowser())
        assertEquals("the rename still waits on the phone", 1, files.ledger().outbox().size)
        browserSync()
        assertTrue("the server did store it", browser.plans().any { it.title == "Algunder Waalweg" })
        assertEquals(16 * SyncPoll.BASE_MS, poll.intervalMs)

        // 5. the network is back: the phone reads its own stored rename back – no copy, nothing to send again
        link.up()
        val ok = phoneSync()
        val done = ok.outcome as SyncRound.Outcome.Done
        assertEquals(0, done.pushed)
        assertEquals(0, done.left)
        assertEquals(SyncPoll.BASE_MS, poll.intervalMs) // a good pass resets the back-off

        // both sides agree: the same six routes, each once, no copy, the phone's rename kept
        browserSync()
        phoneSync(); browserSync()
        val onPhone = store.live().associate { it.name to pts(it) }
        val inBrowser = browser.plans().associate { it.title!! to PlanCode.decode(it.plan!!)!!.points }
        assertEquals(inBrowser, onPhone)
        assertEquals(setOf("Algunder Waalweg", "B1", "B2", "B3", "B4", "B5"), onPhone.keys)
        assertTrue(onPhone.keys.none { it.endsWith(SyncMerge.COPY) })
        assertEquals("6 routes and the phone's hello", 7, server.itemCount(keys))
        assertTrue(files.ledger().outbox().isEmpty() && browser.outbox().isEmpty())
        assertTrue(problems.said("sync", com.onpath.app.debug.Problems.Level.WARN).toString(), problems.none("sync"))
    }

    /**
     * A push whose answer is lost, then the user edits the same route again before the next pass: the phone meets its
     * own earlier version in the mailbox. Nobody else changed the route – there is no conflict to keep.
     */
    @Test fun aLostAnswerThenAnotherEditMakesNoCopy() {
        val own = store.savePlanned("Waalweg", listOf(a, b))
        phoneSync(); browserSync()
        store.update(own) { it.copy(name = "Waalweg 2") }
        link.breakNext(FlakyLink.Cut.DROP) { it.startsWith("PUT /sync/v1/") }
        assertEquals(SyncRound.Outcome.Offline, phoneSync().outcome)
        store.update(own) { it.copy(name = "Waalweg 3") } // edited again while the answer was lost
        link.up()
        phoneSync(); browserSync(); phoneSync()
        assertEquals(listOf("Waalweg 3"), store.live().map { it.name })
        assertEquals(listOf("Waalweg 3"), browser.plans().map { it.title })
    }

    /**
     * The mailbox over HTTP as web/host/src/sync.js speaks it (GET → {rev, more, items:[{id, rev, blob}]}, PUT body =
     * blob with x-onpath-base, answers {rev} / {error}), its rules from [box]. [busyAt] = the request number that answers
     * 503 (the host under load). One request per connection.
     */
    private class WireMailbox(private val box: Mailbox, private val keys: SyncKeys) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        @Volatile var calls = 0
        @Volatile var busyAt = -1

        init {
            Thread {
                while (!socket.isClosed) {
                    val c = try { socket.accept() } catch (e: java.io.IOException) { break } // expected: closed at the end of a test
                    Thread { c.use { handle(it) } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = socket.close()

        @Synchronized private fun handle(s: Socket) {
            val inp = java.io.BufferedInputStream(s.getInputStream())
            fun line(): String { val b = java.io.ByteArrayOutputStream(); while (true) { val ch = inp.read(); if (ch < 0 || ch == '\n'.code) break; if (ch != '\r'.code) b.write(ch) }; return b.toString("UTF-8") }
            val req = line().split(" ")
            val headers = HashMap<String, String>()
            while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
            val len = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(len).also { var o = 0; while (o < len) { val r = inp.read(it, o, len - o); if (r < 0) break; o += r } }
            val path = req[1].substringBefore('?')
            val parts = path.removePrefix("/sync/v1/").split("/")
            calls++
            val reply = when {
                calls == busyAt -> Mailbox.Reply(503, error = "the sync server is busy – try again shortly")
                parts[0] != keys.mailbox || headers["authorization"] != "Bearer ${keys.token}" -> Mailbox.Reply(403)
                req[0] == "POST" -> box.claim()
                req[0] == "GET" -> box.read(req[1].substringAfter("since=").toLong())
                req[0] == "PUT" -> box.write(parts[1], headers["x-onpath-base"]!!.toLong(), body)
                req[0] == "DELETE" -> box.end()
                else -> Mailbox.Reply(404)
            }
            val j = JSONObject().put("rev", reply.rev)
            reply.error?.let { j.put("error", it) }
            reply.page?.let { p ->
                j.put("more", p.more).put("items", JSONArray(p.items.map { (id, rev, blob) ->
                    JSONObject().put("id", id).put("rev", rev).put("blob", SyncFormat.toText(blob)) }))
            }
            val out = j.toString().toByteArray()
            val head = "HTTP/1.1 ${reply.status} X\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(head.toByteArray()); write(out); flush() }
        }
    }
}

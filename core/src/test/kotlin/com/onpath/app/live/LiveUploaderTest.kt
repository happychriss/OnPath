package com.onpath.app.live

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.roundToLong

/**
 * The phone's uploader of a shared tour (docs/design/live-tracking.md §5, §7) against a small in-memory server, a
 * fake recording and a fake clock. The risks: the family sees a wrong way (a stretch missing or twice) after bad
 * network or a killed app; sharing that cannot be stopped; an upload problem that reaches the recording; and too many
 * requests (battery, the server's daily budget).
 */
class LiveUploaderTest {
    private val t0 = 1_800_000_000_000L

    // ---- the fakes ----

    /** What the fake server does with the next calls. */
    private enum class Net { OK, DOWN, LOST, BUSY, GONE, THROW }

    /** The server: one box, items by name; it checks what a real one would (tokens fixed at the claim, valid names). */
    private class Server {
        var net: (Int) -> Net = { Net.OK }
        var calls = 0
        var claims = 0; var pushes = 0; var deletes = 0
        var boxId: String? = null
        var readHash: String? = null; var writeHash: String? = null
        val items = LinkedHashMap<String, ByteArray>()
        val pushedNames = ArrayList<List<String>>()
        var ended = false
        var deleted = false
        var itemsAfterEnd = 0

        fun boxes() = LiveBoxes { id, token ->
            check(token.isNotEmpty())
            if (boxId == null) boxId = id else assertEquals("one box per tour", boxId, id)
            object : LiveBox {
                override fun claim(readHash: String, writeHash: String) = call { claims++
                    assertEquals(64, readHash.length); assertEquals(64, writeHash.length); assertNotEquals(readHash, writeHash)
                    if (this@Server.writeHash == null) { this@Server.readHash = readHash; this@Server.writeHash = writeHash }
                    assertEquals("the same tokens at every claim", this@Server.writeHash, writeHash)
                }
                override fun push(items: List<LivePush>, ended: Boolean) = call { pushes++
                    assertNotNull("a push only into a claimed box", this@Server.writeHash)
                    assertTrue(items.isNotEmpty())
                    for (i in items) {
                        assertTrue(i.name, LiveItem.isName(i.name))
                        assertTrue("an item within the server's size", LiveItem.toText(i.blob).length <= 16 * 1024)
                        if (this@Server.ended) itemsAfterEnd++
                        this@Server.items[i.name] = i.blob
                    }
                    assertEquals("a name once per push", items.size, items.map { it.name }.toSet().size)
                    assertTrue("at most three chunks a push", items.count { LiveItem.chunkNumber(it.name) != null } <= 3)
                    pushedNames += items.map { it.name }
                    if (ended) this@Server.ended = true
                }
                override fun delete() = call { deletes++; deleted = true; this@Server.items.clear() }
            }
        }

        private fun call(store: () -> Unit): LiveReply = when (net(calls++)) {
            Net.OK -> { store(); LiveReply(200) }
            Net.DOWN -> LiveReply(0)
            Net.LOST -> { store(); LiveReply(0) } // stored, but the answer never reached the phone
            Net.BUSY -> LiveReply(503, 60)
            Net.GONE -> LiveReply(410)
            Net.THROW -> throw IllegalStateException("the box implementation broke")
        }
    }

    /** The recording: points appear as time goes; only ever read. */
    private class Tour(val id: Long = 7) : LiveTrack {
        val points = ArrayList<LivePoint>()
        var state = LiveState.RECORDING
        var stillS: Int? = null
        var battery = 80
        var route: LiveRoute? = null
        var headKnown = true
        var broken = false
        var reads = 0

        private var seg = 0

        fun walk(n: Int, fromMs: Long, everyMs: Long = 4000, newSegment: Boolean = false) {
            if (newSegment && points.isNotEmpty()) seg++
            repeat(n) {
                val i = points.size
                points += LivePoint(seg, 46.5 + i * 0.00003, 11.1 + i * 0.00002, 1200 + i / 10, fromMs + it * everyMs)
            }
        }

        override fun pointsAfter(tourId: Long, seq: Int, max: Int): List<LiveTrackPoint> {
            reads++
            if (broken) throw IllegalStateException("the database is busy")
            assertEquals(id, tourId)
            return points.withIndex().filter { it.index > seq }.take(max).map { LiveTrackPoint(it.index, it.value) }
        }
        override fun head(tourId: Long): LiveHead? {
            if (broken) throw IllegalStateException("no state")
            if (!headKnown) return null
            val last = points.lastOrNull()
            return LiveHead(
                state, last?.let { LiveFix(it.lat, it.lon, it.eleM, it.timeMs, 5) }, distanceM = points.size * 4,
                stillS = stillS, batteryPct = battery, activity = "HIKE",
            )
        }
        override fun route(tourId: Long): LiveRoute? {
            if (broken) throw IllegalStateException("no route")
            return route
        }
    }

    private class Store : LiveStore {
        var text: String? = null
        var writes = 0
        var dropWrites = false
        var broken = false
        override fun read(): String? { if (broken) throw IllegalStateException("disk"); return text }
        override fun write(text: String?) {
            if (broken) throw IllegalStateException("disk")
            writes++
            if (!dropWrites) this.text = text
        }
    }

    private class World(seed: Long = 1) {
        val server = Server()
        val tour = Tour()
        val store = Store()
        val rnd = Random(seed)
        var now = 1_800_000_000_000L
        var uploader = fresh()
        var link = ""

        fun fresh() = LiveUploader(server.boxes(), tour, store, { n -> ByteArray(n).also(rnd::nextBytes) })
        /** The app was killed and started again: a new uploader that knows only the stored text. */
        fun restart() { uploader = fresh() }
        fun start(name: String = "Christian") { link = uploader.start(tour.id, name) }

        /** Time passes: every [tickMs] the lane looks at the uploader; a point is recorded every 4 s while [walking]. */
        fun run(ms: Long, walking: Boolean = true, tickMs: Long = 1000) {
            val end = now + ms
            while (now < end) {
                now += tickMs
                if (walking && now % 4000 < tickMs) tour.walk(1, now)
                uploader.step(now)
            }
        }

        // ---- what the family's page would show ----
        val keys get() = LiveKeys.of(link)!!
        fun open(name: String) = server.items[name]?.let { LiveItem.open(keys, name, it) }
        fun head(): LiveHead? = open(LiveItem.HEAD)?.let(LiveItem::readHead)
        fun way(): List<LivePoint> {
            val n = head()?.chunks ?: return emptyList()
            return (0 until n).flatMap { LiveItem.readChunk(open(LiveItem.chunkName(it)) ?: error("chunk $it missing"))!! }
        }
    }

    private fun key(p: LivePoint) = listOf(p.seg.toLong(), (p.lat * 1e5).roundToLong(), (p.lon * 1e5).roundToLong(), p.eleM?.toLong(), p.timeMs)
    private fun assertWay(w: World) = assertEquals("the page's way = the recorded way", w.tour.points.map(::key), w.way().map(::key))

    // ---- tests ----

    /**
     * Guards: tapping "Live teilen" works without network – the link is there at once and nothing waits for the server;
     * a second share of the same tour gives the same link (a second person sees the same tour).
     */
    @Test
    fun theLinkComesWithoutNetworkAndStaysTheSame() {
        val w = World()
        assertEquals(LiveStatus.Off, w.uploader.status)
        assertNull(w.uploader.nextDueMs())
        w.start()
        assertEquals(0, w.server.calls)
        assertNotNull(LiveKeys.of(w.link))
        assertEquals(w.link, w.uploader.start(w.tour.id, "another name"))
        assertEquals(w.link, w.uploader.link)
        assertEquals(LiveStatus.Live(null), w.uploader.status)
        assertNotNull(LiveShare.parse(w.store.text))
        // the write token is not in the link and not derived from it
        val share = LiveShare.parse(w.store.text)!!
        assertFalse(w.link.contains(share.writeToken))
        assertNotEquals(w.keys.readToken, share.writeToken)
    }

    /** Guards: the normal tour – claim, then one request per upload carrying way and status; the page sees the whole way and fresh numbers. */
    @Test
    fun aTourGoesUpWithOneRequestPerUpload() {
        val w = World()
        w.tour.route = LiveRoute(1, listOf(GeoPoint(46.5, 11.1), GeoPoint(46.6, 11.2)))
        w.start()
        w.run(10 * 60_000)
        assertEquals(1, w.server.claims)
        assertEquals("first push right after the claim, then every two minutes", 5, w.server.pushes)
        assertEquals(listOf("r", "h"), w.server.pushedNames[0].filter { it == "r" || it == "h" })
        assertTrue(w.server.pushedNames.drop(1).none { "r" in it })
        val h = w.head()!!
        assertEquals("Christian", h.name); assertEquals(5, h.counter); assertEquals(120, h.nextS); assertEquals(80, h.batteryPct)
        assertEquals(2, LiveItem.readRoute(w.open("r")!!)!!.size)
        w.run(2 * 60_000, walking = false)
        assertWay(w)
        assertTrue(w.uploader.status is LiveStatus.Live)
    }

    /**
     * Guards: hours in a valley without network lose nothing – the cursor stays, the phone asks rarely meanwhile, and
     * afterwards the whole way arrives in order (no straight jump on the page).
     */
    @Test
    fun hoursWithoutNetworkArriveAfterwardsInOrder() {
        val w = World()
        w.start()
        w.run(4 * 60_000)
        val before = w.way().size
        assertTrue(before > 0)
        w.server.net = { Net.DOWN }
        val calls = w.server.calls
        w.run(9 * 3600_000L)
        assertEquals(before, w.way().size)
        assertTrue("back-off: about six tries an hour, was ${w.server.calls - calls}", w.server.calls - calls <= 9 * 6 + 6)
        assertTrue(w.uploader.status is LiveStatus.Waiting)
        w.server.net = { Net.OK }
        w.uploader.step(w.now, networkBack = true)
        w.run(60_000, walking = false)
        assertWay(w)
        assertTrue("nine hours are more than three chunks", w.head()!!.chunks!! > 3)
        assertTrue(w.uploader.status is LiveStatus.Live)
        // chunk numbers went up one by one
        val chunks = w.server.pushedNames.flatten().mapNotNull(LiveItem::chunkNumber)
        assertEquals((0..chunks.max()).toList(), chunks.distinct())
    }

    /**
     * Guards: flaky network and a killed app never bend the way – calls that fail, answers that get lost after the
     * server stored the upload, a busy server, a process death right after a push (its state write lost): at the end
     * the page's way is exactly the recorded one – no point twice, none missing – and the tour reads "ended".
     */
    @Test
    fun flakyNetworkAndKilledAppsNeverLoseOrDoubleAPoint() {
        for (seed in 1L..300L) {
            val w = World(seed)
            val r = Random(seed * 31)
            w.server.net = { when (r.nextInt(10)) { 0, 1, 2 -> Net.DOWN; 3, 4 -> Net.LOST; 5 -> Net.BUSY; else -> Net.OK } }
            w.start()
            repeat(40) {
                when (r.nextInt(8)) {
                    0 -> { w.store.dropWrites = true; w.run(r.nextInt(240) * 1000L); w.store.dropWrites = false; w.restart() }
                    1 -> w.restart()
                    2 -> w.uploader.step(w.now, event = true)
                    3 -> w.uploader.step(w.now, networkBack = true)
                    4 -> w.tour.walk(r.nextInt(3000), w.now, everyMs = 1, newSegment = true)
                    else -> w.run(r.nextInt(600) * 1000L, walking = r.nextBoolean())
                }
            }
            w.server.net = { Net.OK }
            w.uploader.end()
            w.run(20 * 60_000, walking = false)
            assertEquals("seed $seed", w.tour.points.map(::key), w.way().map(::key))
            assertEquals("seed $seed", LiveState.ENDED, w.head()!!.state)
            assertTrue("seed $seed", w.server.ended)
            assertFalse("seed $seed", w.uploader.pending)
            assertNull("seed $seed", w.store.text)
        }
    }

    /**
     * Guards: a process killed between the push and the write of its state sends the same chunk names again – the
     * server's copy is replaced, the way stays exact, the link stays the same.
     */
    @Test
    fun aDeathBetweenPushAndStateWriteSendsTheSameNamesAgain() {
        val w = World()
        w.start()
        w.run(5 * 60_000)
        val stored = w.store.text
        val pushesBefore = w.server.pushedNames.size
        w.store.dropWrites = true
        w.run(2 * 60_000)
        w.store.dropWrites = false
        assertEquals(stored, w.store.text)
        val lost = w.server.pushedNames.drop(pushesBefore).flatten().filter { LiveItem.chunkNumber(it) != null }
        assertTrue(lost.isNotEmpty())
        w.restart()
        val n = w.server.pushedNames.size
        w.run(60_000)
        val again = w.server.pushedNames.drop(n).flatten().filter { LiveItem.chunkNumber(it) != null }
        assertEquals("the same chunk name again", lost.first(), again.first())
        assertEquals(w.link, w.uploader.link)
        w.run(3 * 60_000, walking = false)
        assertWay(w)
    }

    /** Guards: when the server ended the box (expired, deleted) the phone stops asking for good and the app can say so once. */
    @Test
    fun aBoxEndedByTheServerStopsTheSharing() {
        val w = World()
        w.start()
        w.run(5 * 60_000)
        w.server.net = { Net.GONE }
        w.run(3 * 60_000)
        assertEquals(LiveStatus.StoppedByServer, w.uploader.status)
        assertNull(w.store.text); assertFalse(w.uploader.pending); assertNull(w.uploader.link)
        val calls = w.server.calls
        w.run(3600_000)
        assertEquals("never asked again", calls, w.server.calls)
        w.uploader.acknowledge()
        assertEquals(LiveStatus.Off, w.uploader.status)
        // a new share afterwards is a new link
        val old = w.link
        w.server.net = { Net.OK }
        assertNotEquals(old, w.uploader.start(w.tour.id, ""))
    }

    /** Guards: ending the tour shows "ended" with the whole way – the last points and the final head go up, then nothing more. */
    @Test
    fun theEndSendsTheRestAndTheFinalHeadThenNothing() {
        val w = World()
        w.start()
        w.run(5 * 60_000)
        w.tour.walk(20, w.now)
        w.tour.headKnown = false // the recording is over: its live state is gone
        w.uploader.end()
        assertEquals("the sign is off at once", LiveStatus.Off, w.uploader.status)
        assertTrue(w.uploader.pending)
        w.run(60_000, walking = false)
        assertTrue(w.server.ended)
        assertEquals(LiveState.ENDED, w.head()!!.state)
        assertEquals("the numbers of the last known head stay", "HIKE", w.head()!!.activity)
        assertWay(w)
        assertFalse(w.uploader.pending); assertNull(w.store.text); assertNull(w.uploader.nextDueMs())
        val calls = w.server.calls
        w.run(3600_000, walking = false)
        assertEquals(calls, w.server.calls)
        assertEquals(0, w.server.itemsAfterEnd)
    }

    /** Guards: an end while there is no network is not lost – the final head goes up when the network is back, also after a restart. */
    @Test
    fun anEndWithoutNetworkIsSentLater() {
        val w = World()
        w.start()
        w.run(5 * 60_000)
        w.server.net = { Net.DOWN }
        w.uploader.end()
        w.run(10 * 60_000, walking = false)
        assertFalse(w.server.ended); assertTrue(w.uploader.pending)
        w.restart()
        w.tour.headKnown = false
        w.server.net = { Net.OK }
        w.run(60_000, walking = false)
        assertTrue(w.server.ended)
        assertEquals(LiveState.ENDED, w.head()!!.state)
        assertWay(w)
    }

    /**
     * Guards: "Teilen beenden" really ends it – the sign is off at once, the delete is retried until the server has it
     * (also by a restarted app), and after that there is zero network.
     */
    @Test
    fun stopDeletesTheBoxAndIsRetriedUntilItLands() {
        val w = World()
        w.start()
        w.run(5 * 60_000)
        var fail = 3
        w.server.net = { if (fail-- > 0) Net.DOWN else Net.OK }
        w.uploader.stop()
        assertEquals(LiveStatus.Off, w.uploader.status)
        assertNull(w.uploader.link)
        w.run(20_000)
        assertFalse(w.server.deleted)
        w.restart() // the stop survives a killed app
        assertTrue(w.uploader.pending)
        val pushes = w.server.pushes
        w.run(10 * 60_000)
        assertTrue(w.server.deleted)
        assertEquals("no upload after stop", pushes, w.server.pushes)
        assertFalse(w.uploader.pending); assertNull(w.store.text)
        val calls = w.server.calls
        w.run(3600_000)
        assertEquals(calls, w.server.calls)
        assertEquals(LiveStatus.Off, w.uploader.status)
    }

    /** A server with many boxes, each with its own items; [down] = no network. */
    private class Boxes {
        var down = false
        val items = HashMap<String, HashMap<String, ByteArray>>()
        val deleted = HashSet<String>()
        fun boxes() = LiveBoxes { id, _ ->
            object : LiveBox {
                private fun call(f: () -> Unit) = if (down) LiveReply(0) else { f(); LiveReply(200) }
                override fun claim(readHash: String, writeHash: String) = call { items.getOrPut(id) { HashMap() } }
                override fun push(items: List<LivePush>, ended: Boolean) = call { items.forEach { this@Boxes.items.getValue(id)[it.name] = it.blob } }
                override fun delete() = call { this@Boxes.items.remove(id); deleted += id }
            }
        }
    }

    /**
     * Guards (privacy, review 2026-11-20): "Teilen beenden" without network, then sharing again – the first link's box
     * is still deleted once the network is back (also after a killed app, also when the second share is stopped too),
     * instead of showing the way up to the stop for up to 48 h.
     */
    @Test
    fun aStopStillOnTheWayIsNotDroppedBySharingAgain() {
        val boxes = Boxes(); val tour = Tour(); val store = Store(); val rnd = Random(3)
        var now = t0
        fun fresh() = LiveUploader(boxes.boxes(), tour, store, { n -> ByteArray(n).also(rnd::nextBytes) })
        var u = fresh()
        fun run(ms: Long) { val end = now + ms; while (now < end) { now += 1000; if (now % 4000 < 1000) tour.walk(1, now); u.step(now) } }
        fun box(link: String) = LiveKeys.of(link)!!.boxId

        val first = u.start(tour.id, "Christian")
        run(5 * 60_000)
        assertTrue("the first share reached the server", boxes.items[box(first)].orEmpty().isNotEmpty())
        boxes.down = true
        u.stop()
        run(60_000)
        val second = u.start(tour.id, "Christian")
        assertNotEquals(first, second)
        assertEquals(second, u.link)
        run(60_000)
        u = fresh() // killed while offline
        boxes.down = false
        run(5 * 60_000)
        assertTrue("the first link's box is deleted", box(first) in boxes.deleted)
        assertNull(boxes.items[box(first)])
        assertTrue("the second share uploads", boxes.items[box(second)].orEmpty().containsKey(LiveItem.HEAD))
        assertEquals(second, u.link)

        // both stopped while offline: both go, then nothing is left to do
        boxes.down = true
        u.stop()
        val third = u.start(tour.id, "Christian")
        run(60_000)
        u.stop()
        boxes.down = false
        run(5 * 60_000)
        assertTrue(box(second) in boxes.deleted)
        assertNull(boxes.items[box(third)])
        assertFalse(u.pending)
        assertNull(store.text)
    }

    /** Guards: a share stopped before anything reached the server needs no network at all. */
    @Test
    fun stopBeforeTheFirstRequestIsSilent() {
        val w = World()
        w.start()
        w.uploader.stop()
        assertFalse(w.uploader.pending)
        w.run(60_000)
        assertEquals(0, w.server.calls)
    }

    /** Guards: the planned route costs one upload per route, not one per interval; a re-route or dropping the plan reaches the page. */
    @Test
    fun theRouteGoesUpOncePerVersion() {
        val w = World()
        fun routes() = w.server.pushedNames.count { "r" in it }
        w.start()
        w.run(5 * 60_000)
        assertEquals(0, routes())
        w.tour.route = LiveRoute(3, listOf(GeoPoint(46.5, 11.1), GeoPoint(46.6, 11.2), GeoPoint(46.7, 11.3)))
        w.run(10 * 60_000)
        assertEquals(1, routes())
        assertEquals(3, LiveItem.readRoute(w.open("r")!!)!!.size)
        w.tour.route = LiveRoute(4, listOf(GeoPoint(46.5, 11.1), GeoPoint(46.9, 11.9)))
        w.run(10 * 60_000)
        w.restart() // the version on the server is remembered
        w.run(10 * 60_000)
        assertEquals(2, routes())
        assertEquals(2, LiveItem.readRoute(w.open("r")!!)!!.size)
        w.tour.route = null
        w.run(10 * 60_000)
        assertEquals(3, routes())
        assertEquals(0, LiveItem.readRoute(w.open("r")!!)!!.size)
    }

    /**
     * Guards: nothing of the sharing can throw into the recording service – a box, a database read or a state file
     * that throws is a failed step (retried with back-off), never an exception out of the uploader.
     */
    @Test
    fun aThrowingPortNeverThrowsOutOfTheUploader() {
        val w = World()
        w.start()
        w.run(3 * 60_000)
        w.server.net = { Net.THROW }
        w.run(10 * 60_000)
        assertTrue(w.uploader.status is LiveStatus.Waiting)
        w.server.net = { Net.OK }
        w.tour.broken = true
        w.run(10 * 60_000)
        w.tour.broken = false
        w.store.broken = true
        w.run(10 * 60_000)
        w.uploader.end(); w.uploader.stop(); w.uploader.start(w.tour.id, "x"); w.uploader.nextDueMs(); w.uploader.status
        w.store.broken = false
        // a state file that throws on read at the start: sharing is off, nothing thrown
        val s = Store().apply { broken = true }
        val u = LiveUploader(w.server.boxes(), w.tour, s, { ByteArray(it) })
        assertEquals(LiveStatus.Off, u.step(w.now))
        assertFalse(u.pending)
        // and a box factory that throws
        val u2 = LiveUploader({ _, _ -> throw IllegalStateException("no box") }, w.tour, Store(), { n -> ByteArray(n) { 1 } })
        u2.start(1, "")
        assertTrue(u2.step(w.now) is LiveStatus.Waiting)
    }

    /**
     * Guards: the request budget (the owner's concern 2026-11-20) and the battery – a ten-hour moving tour is about
     * 300 requests, a tour resting at a hut six an hour, and a burst of events never more than one upload per 30 s.
     */
    @Test
    fun theRequestBudgetHolds() {
        val moving = World()
        moving.start()
        moving.run(10 * 3600_000L)
        assertTrue("ten hours moving: ${moving.server.calls} requests", moving.server.calls in 295..310)

        val resting = World()
        resting.tour.state = LiveState.PAUSED; resting.tour.stillS = 60
        resting.start()
        resting.run(10 * 3600_000L, walking = false)
        assertTrue("ten hours paused: ${resting.server.calls} requests", resting.server.calls <= 65)
        assertEquals(600, resting.head()!!.nextS)

        val low = World()
        low.tour.battery = 12
        low.start()
        low.run(3600_000L)
        assertTrue("low battery: ${low.server.calls} requests an hour", low.server.calls <= 9)

        val busy = World()
        busy.start()
        busy.run(60_000)
        val calls = busy.server.calls
        repeat(600) { busy.now += 1000; busy.uploader.step(busy.now, event = true) }
        assertTrue("events every second for ten minutes: ${busy.server.calls - calls}", busy.server.calls - calls <= 21)
    }

    /** Guards: a busy server is left alone as long as it says – no retry storm against a server over its limit. */
    @Test
    fun aBusyServerIsLeftAlone() {
        val w = World()
        w.start()
        w.run(3 * 60_000)
        w.server.net = { Net.BUSY }
        val calls = w.server.calls
        w.run(10 * 60_000)
        repeat(50) { w.uploader.step(w.now, event = true, networkBack = true) }
        assertTrue("503 with retry-after 60: ${w.server.calls - calls} tries in ten minutes", w.server.calls - calls <= 11)
    }

    /**
     * Guards: "Tour beendet" must not sit behind the 30-s event floor – the recording service releases its wake lock at
     * the end, so a final head scheduled half a minute later may wait until the phone next wakes and the page says
     * "kein Kontakt" for a tour that is over. An end (and a stop's delete) right after an upload goes within seconds.
     */
    @Test
    fun anEndRightAfterAnUploadIsSentAtOnce() {
        val w = World()
        w.start()
        w.run(5 * 60_000)
        val pushes = w.server.pushes
        while (w.server.pushes == pushes) w.run(1000) // an upload just went through
        w.tour.headKnown = false
        w.uploader.end()
        assertTrue("due within seconds, not after the event floor", w.uploader.nextDueMs()!! - w.now <= 2000)
        w.run(3000, walking = false)
        assertTrue("the final head is there 3 s after End tour", w.server.ended)
        assertFalse(w.uploader.pending)

        val v = World()
        v.start()
        v.run(5 * 60_000)
        val p2 = v.server.pushes
        while (v.server.pushes == p2) v.run(1000)
        v.uploader.stop()
        v.run(3000, walking = false)
        assertTrue("the box is deleted 3 s after Stop sharing", v.server.deleted)
    }

    /** Guards: a busy server is still left alone at the end – its retry-after holds for the final head too. */
    @Test
    fun anEndDoesNotHammerABusyServer() {
        val w = World()
        w.start()
        w.run(5 * 60_000)
        w.server.net = { Net.BUSY }
        w.tour.headKnown = false
        w.uploader.end()
        val calls = w.server.calls
        w.run(50_000, walking = false)
        assertTrue("at most one try inside the busy floor: ${w.server.calls - calls}", w.server.calls - calls <= 1)
    }

    /**
     * Guards: the remembered state survives as text – what was written is read back exactly, a newer version's extra
     * fields are ignored, and a damaged file is "no share" (never a crash at the start of the recording service).
     */
    @Test
    fun theShareTextReadsBackAndGarbageIsNothing() {
        val s = LiveShare(
            // test vector from the dummy secret i*7+1 – not a credential
            ByteArray(32) { (it * 7 + 1).toByte() }, "wHErkFiAjM3dUXVy18TJETV0arl4vvuSa21gZUBEpjE", 42, "Christian N.", true, 1234, 7, 3, // ggignore
            99, ending = true, stopping = false, lastHead = LiveItem.head(LiveHead(LiveState.PAUSED, batteryPct = 40)),
        )
        assertEquals(s, LiveShare.parse(s.toText()))
        assertEquals(
            "onpath-live 1 AQgPFh0kKzI5QEdOVVxjanF4f4aNlJuiqbC3vsXM09o wHErkFiAjM3dUXVy18TJETV0arl4vvuSa21gZUBEpjE 42 1 1234 7 3 99 1 " +
                "Q2hyaXN0aWFuIE4u " + com.onpath.app.sync.B64.encode(s.lastHead!!),
            s.toText(),
        )
        assertEquals(s, LiveShare.parse(s.toText() + " future-field 5\n"))
        val plain = LiveShare(ByteArray(32), "abc", 1, "")
        assertEquals(plain, LiveShare.parse(plain.toText()))
        assertEquals("", LiveShare.parse(plain.toText())!!.name)
        for (bad in listOf(null, "", "   ", "{\"json\":1}", "onpath-live 2 " + s.toText().drop(14), s.toText().take(60),
            s.toText().replace(" 42 ", " x "), s.toText().replace("AQgP", "A?gP"), "onpath-live 1 a b c d e f g h i j k"))
            assertNull(bad, LiveShare.parse(bad))
    }

    /** Guards: the page can tell which chunks count – the head says how many, so a leftover chunk of a lost upload is not drawn. */
    @Test
    fun theHeadSaysHowManyChunksCount() {
        val h = LiveHead(LiveState.RECORDING, chunks = 12)
        assertEquals(12, LiveItem.readHead(LiveItem.head(h))!!.chunks)
        assertNull(LiveItem.readHead(LiveItem.head(LiveHead(LiveState.RECORDING)))!!.chunks)
        val w = World()
        w.start()
        w.run(5 * 60_000)
        assertEquals(w.server.items.keys.count { LiveItem.chunkNumber(it) != null }, w.head()!!.chunks)
    }
}

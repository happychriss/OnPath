package com.onpath.app.live

import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.recording.RecState
import com.onpath.app.testkit.JdbcTours
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** The points of a tour read with the app's own two statements ([LiveSql]) over JDBC. */
internal class JdbcLivePoints(private val path: String, private val createdMs: Long = 1_800_000_000_000L, private val activity: String? = "HIKE") : LivePoints, AutoCloseable {
    /** For a test: the database cannot be read. */
    @Volatile var broken = false
    // its own connection, as the app's readers have theirs (WAL)
    private val db = DriverManager.getConnection("jdbc:sqlite:$path").apply { createStatement().use { it.execute("PRAGMA busy_timeout=0") } }

    private fun rows(sql: String, vararg args: Any): List<LivePointRow> = db.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeQuery().use { r ->
            buildList {
                while (r.next()) add(LivePointRow(r.getInt(1), r.getInt(2), r.getDouble(3), r.getDouble(4),
                    r.getDouble(5).takeUnless { r.wasNull() }, r.getLong(6).takeUnless { r.wasNull() }, r.getDouble(7).takeUnless { r.wasNull() }))
            }
        }
    }

    override fun after(tourId: Long, seq: Int, max: Int) = rows(LiveSql.POINTS_AFTER, tourId, seq, max)
    override fun last(tourId: Long) = rows(LiveSql.LAST_POINT, tourId).firstOrNull()
    /** As the app's (LiveEdge): over = the row is not "active" any more or in the bin; no row = null. */
    override fun tour(tourId: Long): LiveTourInfo? {
        if (broken) throw java.sql.SQLException("database is locked")
        return db.prepareStatement("SELECT state, deleted_at FROM tours WHERE id=?").use { st ->
            st.setLong(1, tourId)
            st.executeQuery().use { r -> if (!r.next()) null else LiveTourInfo(createdMs, activity, over = r.getString(1) != "active" || r.getObject(2) != null) }
        }
    }
    override fun close() = db.close()
}

internal fun recState(tourId: Long, paused: Boolean = false, offRouteAlert: Boolean = false, arrived: Boolean = false) =
    RecState(tourId, paused = paused, distanceM = 1234.4, ascentM = 56.6, descentM = 0.0, elapsedS = 600, gpsLost = false,
        offRouteM = if (offRouteAlert) 150.0 else null, offRouteAlert = offRouteAlert, followName = null, track = emptyList(), arrived = arrived)

/**
 * The live lane beside a recording (docs/design/live-tracking.md §7). The risk guarded here is the one that matters
 * most: **sharing must never be able to hold up or hurt the recording** – and after it, that the family is not shown a
 * tour as "still going" when it is not, and that a share really ends.
 *
 * The recording is played by a writer on its own SQLite connection (WAL, as on the phone), the server by a box the test
 * can hang; the lane, the uploader, the track reader and the read statements are the real ones. What a JVM test cannot
 * show is Android's own SQLite and process behaviour – that is the Pixel walk's part.
 */
class LiveLaneTest {
    @get:Rule val tmp = TemporaryFolder()

    private val offset = AtomicLong(0)
    private val clock = { System.nanoTime() / 1_000_000 + offset.get() }
    private val state = AtomicReference<RecState?>(null)
    private val snapshots = CopyOnWriteArrayList<LiveSnapshot>()
    private val closers = ArrayList<AutoCloseable>()
    private var lane: LiveLane? = null

    private class Net : LiveNetwork {
        @Volatile var onBack: (() -> Unit)? = null
        val watches = AtomicInteger(); val unwatches = AtomicInteger()
        override fun watch(onBack: () -> Unit) { watches.incrementAndGet(); this.onBack = onBack }
        override fun unwatch() { unwatches.incrementAndGet(); onBack = null }
    }
    private val net = Net()

    /** A box that answers 200 – or hangs every call until [release] while [hang] is set. */
    private class Box : LiveBoxes, LiveBox {
        @Volatile var hang = false
        @Volatile var code = 200
        @Volatile var release = CountDownLatch(1)
        @Volatile var entered = CountDownLatch(1)
        val claims = AtomicInteger(); val deletes = AtomicInteger()
        val pushes = CopyOnWriteArrayList<Pair<List<String>, Boolean>>()
        val blobs = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        override fun open(boxId: String, writeToken: String): LiveBox = this
        private fun wait(): LiveReply { if (hang) { entered.countDown(); release.await(30, TimeUnit.SECONDS) }; return LiveReply(code) }
        override fun claim(readHash: String, writeHash: String): LiveReply { claims.incrementAndGet(); return wait() }
        override fun push(items: List<LivePush>, ended: Boolean): LiveReply {
            val r = wait()
            if (r.ok) { items.forEach { blobs[it.name] = it.blob }; pushes += items.map { it.name } to ended }
            return r
        }
        override fun delete(): LiveReply { deletes.incrementAndGet(); return wait() }
    }
    private val box = Box()

    private class MemStore : LiveStore {
        @Volatile var text: String? = null
        override fun read() = text
        override fun write(text: String?) { this.text = text }
    }
    private val store = MemStore()

    private fun dbPath() = tmp.root.resolve("tours.db").path

    private fun recorder(): JdbcTours = JdbcTours(dbPath()).also {
        closers += it
        it.db.createStatement().use { s -> s.execute("PRAGMA journal_mode=WAL") }
    }

    private fun lane(points: LivePoints): LiveLane {
        var n = 0
        val track = DbLiveTrack(points, state::get, { 80 }, System::currentTimeMillis)
        val uploader = LiveUploader(box, track, store, random = { size -> ByteArray(size) { (n++ * 7 + it).toByte() } })
        return LiveLane(uploader, state::get, track::over, clock, System::currentTimeMillis, net) { snapshots += it }.also { lane = it; it.attach() }
    }

    private fun point(i: Int) = TrackPoint(46.5 + i * 1e-4, 11.1 + i * 1e-4, 1500.0 + i, 1_800_000_000_000L + i * 5_000L)

    private fun until(what: String, ms: Long = 8_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { assertTrue("waited for: $what", System.currentTimeMillis() < end); Thread.sleep(20) }
    }

    @After fun close() {
        box.release.countDown()
        lane?.shutdown()
        closers.forEach { it.close() }
    }

    /**
     * The server never answers. The recorder goes on writing every point without waiting, the service's calls into the
     * lane return at once – and when the server is back, the whole way goes up.
     */
    @Test fun aHangingServerNeverHoldsUpTheRecorder() {
        val rec = recorder()
        val tour = rec.insertTour(TourKind.RECORDED, "t", "HIKE", 2, state = "active")
        repeat(20) { rec.appendPoint(tour, it, 0, point(it), 5f) }
        val points = JdbcLivePoints(dbPath()).also { closers += it }
        state.set(recState(tour))
        box.hang = true
        val l = lane(points)

        l.start(tour, "Christian")
        assertTrue("the lane is inside the request", box.entered.await(5, TimeUnit.SECONDS))
        assertFalse("the lane's thread is held by the request", l.idle(200))

        // the service's side: every call returns at once although the lane hangs
        val t0 = System.nanoTime()
        repeat(50) { l.stateChanged() }
        l.stop(); l.start(tour, "x"); l.acknowledge()
        assertTrue("calls into the lane never wait for it", (System.nanoTime() - t0) / 1_000_000 < 500)

        // the recorder: 500 more points on its own connection while the request hangs
        val failed = AtomicReference<Throwable?>(null)
        val writer = thread {
            try { for (i in 20 until 520) rec.appendPoint(tour, i, if (i < 300) 0 else 1, point(i), 5f) } catch (e: Throwable) { failed.set(e) }
        }
        writer.join(10_000)
        assertFalse("the recorder finished while the server hangs", writer.isAlive)
        assertNull("no write failed or waited for a lock", failed.get())
        assertFalse("and the lane still hangs", l.idle(100))

        box.hang = false
        box.release.countDown()
        until("the stop that was asked for meanwhile reaches the server") { box.deletes.get() > 0 || box.pushes.isNotEmpty() }
    }

    /** With the server back the way arrives complete and in order – read back with the link's keys as the page would. */
    @Test fun theWholeWayArrivesAndTheLinkOpensIt() {
        val rec = recorder()
        val tour = rec.insertTour(TourKind.RECORDED, "t", "HIKE", 2, state = "active")
        repeat(300) { rec.appendPoint(tour, it, if (it < 200) 0 else 1, point(it), 5f) }
        val points = JdbcLivePoints(dbPath()).also { closers += it }
        state.set(recState(tour))
        val l = lane(points)
        l.start(tour, "Christian")
        until("the first upload") { box.pushes.isNotEmpty() }
        until("the link is published") { snapshots.lastOrNull()?.link != null && snapshots.last().lastOkMs != null }
        val snap = snapshots.last()
        assertTrue(snap.sharing); assertFalse(snap.waiting); assertEquals(tour, snap.tourId)
        assertTrue("a wall-clock time near now", kotlin.math.abs(System.currentTimeMillis() - snap.lastOkMs!!) < 10_000)

        val keys = LiveKeys.of(snap.link!!)!!
        val head = LiveItem.readHead(LiveItem.open(keys, LiveItem.HEAD, box.blobs[LiveItem.HEAD]!!)!!)!!
        assertEquals("Christian", head.name); assertEquals(80, head.batteryPct); assertEquals(1234, head.distanceM)
        val got = (0 until head.chunks!!).flatMap { n -> LiveItem.readChunk(LiveItem.open(keys, LiveItem.chunkName(n), box.blobs[LiveItem.chunkName(n)]!!)!!)!! }
        assertEquals(300, got.size)
        assertEquals("the segments as recorded", listOf(0, 1), got.map { it.seg }.distinct())
        assertEquals(point(299).timeMs, got.last().timeMs)
        assertEquals("watched while sharing", 1, net.watches.get())
    }

    /**
     * The service is gone but the tour was not ended (killed, "Continue tour" not chosen yet): nothing is uploaded –
     * the page must say "no contact", not "2 minutes ago" – and with the tour back the same link goes on.
     */
    @Test fun anInterruptedTourIsNotUploadedAsStillGoing() {
        val rec = recorder()
        val tour = rec.insertTour(TourKind.RECORDED, "t", "HIKE", 2, state = "active")
        repeat(5) { rec.appendPoint(tour, it, 0, point(it), 5f) }
        val points = JdbcLivePoints(dbPath()).also { closers += it }
        state.set(recState(tour))
        val l = lane(points)
        l.start(tour, "")
        until("the first upload") { box.pushes.isNotEmpty() }
        val link = snapshots.last { it.link != null }.link

        state.set(null) // the service was destroyed; finish() did not run
        l.stateChanged()
        assertTrue(l.idle())
        val before = box.pushes.size
        offset.addAndGet(3_600_000) // an hour passes
        l.stateChanged()
        assertTrue(l.idle())
        Thread.sleep(300)
        assertEquals("no upload while the tour does not run", before, box.pushes.size)
        assertEquals("the share is kept", link, snapshots.last().link)

        state.set(recState(tour)) // Continue tour
        l.stateChanged()
        until("uploads go on") { box.pushes.size > before }
        assertEquals("with the same link", link, snapshots.last().link)
        assertFalse("and nothing said 'ended'", box.pushes.any { it.second })

        // the tour database cannot be read for a moment while the tour is away: "not known" ends nothing
        points.broken = true
        state.set(null); l.stateChanged(); assertTrue(l.idle())
        points.broken = false
        assertEquals("the share is kept", link, snapshots.last().link)
        assertNotNull(store.text)
    }

    /** End tour: the last upload says "ended", then the sharing is off, the state forgotten and the network watch removed. */
    @Test fun endingTheTourEndsTheShare() {
        val rec = recorder()
        val tour = rec.insertTour(TourKind.RECORDED, "t", "HIKE", 2, state = "active")
        repeat(5) { rec.appendPoint(tour, it, 0, point(it), 5f) }
        val points = JdbcLivePoints(dbPath()).also { closers += it }
        state.set(recState(tour))
        val l = lane(points)
        l.start(tour, "")
        until("the first upload") { box.pushes.isNotEmpty() }

        // the service's order at End tour: the row says "done", then the state goes null
        rec.updateStats(tour, 1234.0, 56.0, 0.0, 600.0, "done")
        state.set(null)
        l.stateChanged()
        assertTrue(l.idle())
        assertFalse("the sign is off at once", snapshots.last().sharing)
        offset.addAndGet(60_000) // past the 30-s floor between two uploads
        l.stateChanged()
        until("the final upload") { box.pushes.any { it.second } }
        until("the share is forgotten") { store.text == null && net.unwatches.get() == 1 }
        assertEquals(LiveSnapshot.OFF, snapshots.last())
        val n = box.pushes.size
        offset.addAndGet(3_600_000); l.stateChanged(); assertTrue(l.idle()); Thread.sleep(200)
        assertEquals("nothing after the end", n, box.pushes.size)
    }

    /** A new tour starts while an older one is still shared (its end never came): the old share ends by itself. */
    @Test fun anotherTourEndsTheOldShare() {
        val rec = recorder()
        val old = rec.insertTour(TourKind.RECORDED, "old", "HIKE", 2, state = "active")
        rec.appendPoint(old, 0, 0, point(0), 5f)
        val points = JdbcLivePoints(dbPath()).also { closers += it }
        state.set(recState(old))
        val l = lane(points)
        l.start(old, "")
        until("the first upload") { box.pushes.isNotEmpty() }

        offset.addAndGet(60_000)
        val next = rec.insertTour(TourKind.RECORDED, "new", "HIKE", 2, state = "active")
        rec.finishOtherActive(next) // what the service does when a tour starts
        state.set(recState(next))
        l.stateChanged()
        until("the old share says ended") { box.pushes.any { it.second } }
        until("and is forgotten") { store.text == null }
    }

    /** Stop sharing: the box is deleted, also when the first try fails; nothing is uploaded afterwards. */
    @Test fun stopDeletesTheBox() {
        val rec = recorder()
        val tour = rec.insertTour(TourKind.RECORDED, "t", "HIKE", 2, state = "active")
        rec.appendPoint(tour, 0, 0, point(0), 5f)
        val points = JdbcLivePoints(dbPath()).also { closers += it }
        state.set(recState(tour))
        val l = lane(points)
        l.start(tour, "")
        until("the first upload") { box.pushes.isNotEmpty() }
        val n = box.pushes.size

        box.code = 0 // no network right now
        l.stop()
        until("a delete was tried") { box.deletes.get() == 1 }
        assertTrue(l.idle())
        assertFalse(snapshots.last().sharing); assertNull(snapshots.last().link)
        assertNotNull("the stop is remembered", store.text)

        box.code = 200
        net.onBack!!.invoke() // Android reports the network back
        offset.addAndGet(60_000); l.stateChanged()
        until("the delete landed") { box.deletes.get() >= 2 && store.text == null }
        assertEquals("no upload after stop", n, box.pushes.size)
    }

    /**
     * Review 2026-11-20: "Teilen" and "Teilen beenden" change what the app shows at once – the link reaches the share
     * sheet and the sign goes off before the server answered (a weak network takes up to 25 s a request).
     */
    @Test fun startAndStopShowBeforeTheServerAnswers() {
        val rec = recorder()
        val tour = rec.insertTour(TourKind.RECORDED, "t", "HIKE", 2, state = "active")
        rec.appendPoint(tour, 0, 0, point(0), 5f)
        val points = JdbcLivePoints(dbPath()).also { closers += it }
        state.set(recState(tour))
        box.hang = true
        val l = lane(points)
        l.start(tour, "")
        assertTrue("the lane is inside the claim", box.entered.await(5, TimeUnit.SECONDS))
        assertNotNull("the link is shown while the claim hangs", snapshots.lastOrNull()?.link)
        assertTrue(snapshots.last().sharing)

        box.release.countDown()
        until("the first upload") { box.pushes.isNotEmpty() }
        assertTrue(l.idle())
        box.release = CountDownLatch(1); box.entered = CountDownLatch(1)
        l.stop()
        assertTrue("the lane is inside the delete", box.entered.await(5, TimeUnit.SECONDS))
        assertFalse("the sign is off while the delete hangs", snapshots.last().sharing)
        assertNull(snapshots.last().link)
    }

    /**
     * Privacy (review 2026-11-20): stopped offline, shared again, then the tour is interrupted – the first link's box is
     * still deleted when the network is back, although nothing of the running share may be uploaded now.
     */
    @Test fun aStopKeptBehindANewShareIsDeletedEvenWhileTheTourIsInterrupted() {
        val rec = recorder()
        val tour = rec.insertTour(TourKind.RECORDED, "t", "HIKE", 2, state = "active")
        rec.appendPoint(tour, 0, 0, point(0), 5f)
        val points = JdbcLivePoints(dbPath()).also { closers += it }
        state.set(recState(tour))
        val l = lane(points)
        l.start(tour, "")
        until("the first upload") { box.pushes.isNotEmpty() }
        val n = box.pushes.size

        box.code = 0
        l.stop()
        until("a delete was tried") { box.deletes.get() == 1 }
        l.start(tour, "")
        assertTrue(l.idle())
        state.set(null) // the service is gone, the tour not ended
        l.stateChanged()
        box.code = 200
        net.onBack?.invoke()
        offset.addAndGet(120_000); l.stateChanged()
        until("the old box was deleted") { box.deletes.get() >= 2 && store.text?.contains("onpath-live-delete") == false }
        assertTrue(l.idle())
        assertEquals("nothing of the interrupted tour uploaded", n, box.pushes.size)
        assertNotNull("the new share is kept for Continue", snapshots.last().link)
    }

    /** A killed process: a new lane reads the stored share and goes on with the same link, no second claim needed. */
    @Test fun aNewProcessGoesOnWithTheStoredShare() {
        val rec = recorder()
        val tour = rec.insertTour(TourKind.RECORDED, "t", "HIKE", 2, state = "active")
        repeat(3) { rec.appendPoint(tour, it, 0, point(it), 5f) }
        val points = JdbcLivePoints(dbPath()).also { closers += it }
        state.set(recState(tour))
        val first = lane(points)
        first.start(tour, "")
        until("the first upload") { box.pushes.isNotEmpty() }
        val link = snapshots.last { it.link != null }.link
        first.shutdown() // the process is gone; the store's text stays

        repeat(3) { rec.appendPoint(tour, 3 + it, 1, point(3 + it), 5f) }
        val n = box.pushes.size
        snapshots.clear()
        lane(points)
        until("the new lane uploads") { box.pushes.size > n }
        assertEquals(link, snapshots.last().link)
        assertEquals("the box was claimed once", 1, box.claims.get())
    }

    /** Parts beside the lane that throw (the snapshot's reader, the network watch) never stop it. */
    @Test fun aThrowingNeighbourNeverStopsTheLane() {
        val rec = recorder()
        val tour = rec.insertTour(TourKind.RECORDED, "t", "HIKE", 2, state = "active")
        rec.appendPoint(tour, 0, 0, point(0), 5f)
        val points = JdbcLivePoints(dbPath()).also { closers += it }
        state.set(recState(tour))
        val track = DbLiveTrack(points, state::get, { throw IllegalStateException("battery") }, System::currentTimeMillis)
        var n = 0
        val uploader = LiveUploader(box, track, store, random = { size -> ByteArray(size) { (n++ + it).toByte() } })
        val calls = AtomicInteger()
        val l = LiveLane(uploader, state::get, { throw IllegalStateException("db") }, clock, System::currentTimeMillis, object : LiveNetwork {
            override fun watch(onBack: () -> Unit) = throw IllegalStateException("no connectivity service")
            override fun unwatch() = throw IllegalStateException("no connectivity service")
        }) { if (calls.incrementAndGet() % 2 == 1) throw IllegalStateException("reader") }
        lane = l
        l.attach(); l.start(tour, "")
        repeat(20) { l.stateChanged() }
        assertTrue("the lane still takes work", l.idle())
        until("and still claims the box") { box.claims.get() >= 1 }
    }
}

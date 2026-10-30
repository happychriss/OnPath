package com.onpath.app.sync

import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Imported GPX tracks in the plan sync (owner 2026-10-29, A-007): the track code, the track item (kind 3, field 4),
 * the ledger and merge rules for tracks, and a sync round with the mailbox's rules (claim, 409, ended, offline).
 */
class SyncTrackTest {
    private val keys = SyncKeys(ByteArray(32) { (it * 7 + 1).toByte() })
    private val phone = SyncFormat.newDevice(ByteArray(8) { 1 })
    private val web = SyncFormat.newDevice(ByteArray(8) { 2 })
    private fun id(n: Int) = SyncFormat.newId(ByteArray(16) { n.toByte() })

    private val small = SyncTrack(
        listOf(listOf(TrackPt(46.67171, 11.15003, 512.0), TrackPt(46.67220, 11.15110, 515.0)), listOf(TrackPt(46.68000, 11.16000, 530.0))),
        Activity.HIKE, 3, listOf(TrackMark(46.67171, 11.15003, "Algund"), TrackMark(46.68, 11.16, null)))

    /** A long wiggly hiking track (≈ 1 point every 8 m). */
    private fun long(n: Int, ele: Boolean = true) = SyncTrack(listOf(List(n) { i ->
        TrackPt(46.6 + i * 0.00005 + 0.0004 * sin(i / 9.0), 11.1 + i * 0.00004 + 0.0003 * cos(i / 13.0), if (ele) 600.0 + 300 * sin(i / 400.0) else null)
    }), Activity.MTB, 2)

    @Test fun trackCodeRoundTrip() {
        val back = TrackCode.decode(TrackCode.encode(small))!!
        assertEquals(small, back)
        // without elevation on every point: no elevation at all
        val noEle = small.copy(segments = listOf(listOf(TrackPt(46.1, 11.1, null), TrackPt(46.2, 11.2, 900.0))))
        assertTrue(TrackCode.decode(TrackCode.encode(noEle))!!.segments.flatten().all { it.ele == null })
        assertEquals(Activity.HIKE to 3, TrackCode.header(TrackCode.fit(small)))
    }

    /** Frozen: the browser (web planner) reads exactly these bytes – a change needs a new version. */
    @Test fun trackFormatVersion1IsFrozen() {
        assertEquals(FROZEN_TRACK, TrackCode.fit(small))
    }

    @Test fun trackCodeNeverThrows() {
        val good = TrackCode.encode(small)
        for (n in 0 until good.size) TrackCode.decode(good.copyOf(n))
        assertNull(TrackCode.decode(byteArrayOf(2) + good.drop(1)))
        val rnd = Random(9)
        repeat(3000) { TrackCode.decode(rnd.nextBytes(rnd.nextInt(0, 120))) }
        assertNull(TrackCode.of("!!"))
        assertNull(TrackCode.header(null))
        assertNull(TrackCode.fit(SyncTrack(listOf(emptyList()), Activity.HIKE)))
    }

    @Test fun aLongTrackIsThinnedToFitAndKeepsItsShape() {
        val t = long(20_000)
        assertTrue(TrackCode.encode(t).size > SyncFormat.MAX_TRACK)
        val text = TrackCode.fit(t)!!
        val bytes = B64.decode(text)!!
        assertTrue(bytes.size <= SyncFormat.MAX_TRACK)
        val back = TrackCode.decode(bytes)!!
        val pts = back.segments.single()
        assertTrue("thinned: ${pts.size}", pts.size in 500 until 20_000)
        assertEquals(t.segments[0].first().lat, pts.first().lat, 1e-5)
        assertEquals(t.segments[0].last().lon, pts.last().lon, 1e-5)
        // every original point stays near the thinned line (within the tolerance that made it fit)
        val worst = t.segments[0].filterIndexed { i, _ -> i % 25 == 0 }.maxOf { p -> pts.zipWithNext().minOf { (q, r) -> offLine(p, q, r) } }
        assertTrue("worst $worst m", worst < 35)
        // the item with it fits the blob limit
        val item = SyncItem(id(1), 5, phone, kind = SyncItem.TRACK, title = "Long day", track = text)
        assertTrue(SyncFormat.seal(keys, item, ByteArray(12)).size < SyncFormat.MAX_BLOB)
        // a short track stays as it is
        assertEquals(small, TrackCode.of(TrackCode.fit(small)))
    }

    @Test fun anExtremeTrackStillFits() {
        val t = SyncTrack(List(30) { s -> long(8_000, ele = s % 2 == 0).segments[0].map { it.copy(lat = it.lat + s * 0.01) } },
            Activity.BIKE_TOURING, 1, List(300) { TrackMark(46.0 + it * 0.001, 11.0, "Point $it with a long name ".repeat(3)) })
        val b = B64.decode(TrackCode.fit(t)!!)!!
        assertTrue(b.size <= SyncFormat.MAX_TRACK)
        assertNotNull(TrackCode.decode(b))
    }

    @Test fun trackItemRoundTripAndOlderReaders() {
        val text = TrackCode.fit(small)!!
        val it = SyncItem(id(3), 1_793_000_000_000, phone, kind = SyncItem.TRACK, title = "Waalweg GPX", track = text)
        assertEquals(it, SyncFormat.decode(SyncFormat.encode(it)))
        assertEquals(it, SyncFormat.open(keys, it.id, SyncFormat.seal(keys, it, ByteArray(12) { 4 })))
        // the track goes as raw bytes (field 4), not as text
        val raw = SyncFormat.encode(it)
        assertTrue(raw.size < SyncFormat.encode(it.copy(track = null)).size + text.length)
        // a version-1 reader (plans only) skips the track field and keeps the item as it is – never shows it as a plan
        val l = SyncLedger.empty()
        l.apply(keys, listOf(Triple(it.id, 1L, SyncFormat.seal(keys, it, ByteArray(12) { 1 }))), 1)
        assertTrue(l.plans().isEmpty())
        assertEquals(listOf("Waalweg GPX"), l.tracks().map { t -> t.title })
        // damaged track bytes: no track, the item still reads
        val noTrack = SyncFormat.decode(SyncFormat.encode(it.copy(track = null)) + byteArrayOf(4, 0))
        assertNull(noTrack!!.track)
    }

    @Test fun ledgerAndMergeTreatTracksLikePlans() {
        val l = SyncLedger.empty()
        val text = TrackCode.fit(small)!!
        assertNull(l.saveTrack("bad", text, "x", 1, phone))
        l.saveTrack(id(1), text, "GPX", 100, phone)
        assertTrue(l.rename(id(1), "GPX renamed", 50, phone))
        assertEquals(101, l.get(id(1))!!.updatedAt)
        // conflict: the older track version stays as a copy
        val mine = l.get(id(1))!!
        val theirs = mine.copy(updatedAt = 200, device = web, title = "Browser name")
        val m = SyncMerge.merge(mine, theirs)
        assertEquals(theirs, m.winner)
        assertEquals("GPX renamed (copy)", m.copy!!.title)
        assertEquals(SyncItem.TRACK, m.copy!!.kind)
        assertEquals(text, m.copy!!.track)
        // unpair keeps tracks to upload again
        assertTrue(l.delete(id(1), 300, phone))
        l.saveTrack(id(2), text, "Kept", 400, phone)
        l.hello(phone, "Pixel", 1)
        l.forget()
        assertEquals(listOf(id(2)), l.entries.keys.toList())
        // an unknown kind (a newer app) is never copied
        val future = SyncItem(id(5), 1, web, kind = 9, title = "?")
        assertNull(SyncMerge.merge(future, future.copy(updatedAt = 2, title = "!")).copy)
    }

    // ---- a sync round (hello, pull, push, 409, claim, ended, offline) ----

    @Test fun roundClaimsPullsPushesAndMergesAfterA409() {
        val server = MemoryServer(page = 2)
        val a = SyncLedger.empty(); val b = SyncLedger.empty()
        var n = 0
        val nonce = { ByteArray(12) { (n++).toByte() } }
        val ra = SyncRound(keys, server.box(keys), nonce)
        val rb = SyncRound(keys, server.box(keys), nonce)
        a.hello(web, "Chrome on Linux", 1)
        for (i in 1..5) a.save(id(i), PLAN, "Plan $i", 10L + i, web)
        val first = ra.run(a)
        assertEquals(SyncRound.Outcome.Done(0, 6, 0, 0), first) // the mailbox was claimed on the way
        b.hello(phone, "Pixel 8", 1)
        val second = rb.run(b) as SyncRound.Outcome.Done
        assertEquals(6, second.pulled) // three pages of two
        assertEquals(5, b.plans().size)
        assertEquals("Chrome on Linux", b.devices(phone).single().name)
        // both rename plan 1 offline; the browser is first, the phone gets a 409, reads again and keeps both
        a.rename(id(1), "Browser", 100, web); b.rename(id(1), "Phone", 200, phone)
        ra.run(a)
        val third = rb.run(b) as SyncRound.Outcome.Done
        assertEquals(0, third.left)
        ra.run(a)
        assertEquals(setOf("Phone", "Browser (copy)"), a.plans().filter { it.title?.let { t -> "Plan" !in t } == true }.map { it.title }.toSet())
        assertEquals(a.plans().map { it.title }.toSet(), b.plans().map { it.title }.toSet())
        assertFalse(server.stored(keys).contains("Browser"))
    }

    @Test fun roundSaysOfflineEndedAndRefused() {
        val server = MemoryServer()
        val l = SyncLedger.empty().apply { save(id(1), PLAN, "A", 1, phone) }
        val r = SyncRound(keys, server.box(keys), { ByteArray(12) })
        server.offline = true
        assertEquals(SyncRound.Outcome.Offline, r.run(l))
        assertEquals(1, l.outbox().size) // still to upload
        server.offline = false
        assertTrue(r.run(l) is SyncRound.Outcome.Done)
        // another code for the same mailbox id cannot happen; a wrong token is "ended" for the device
        val other = object : Mailbox by server.box(keys) { override fun read(since: Long) = Mailbox.Reply(403) }
        assertEquals(SyncRound.Outcome.Ended, SyncRound(keys, other, { ByteArray(12) }).run(l))
        val busy = object : Mailbox by server.box(keys) { override fun read(since: Long) = Mailbox.Reply(429, error = "too many requests") }
        assertEquals(SyncRound.Outcome.Refused(429, "too many requests"), SyncRound(keys, busy, { ByteArray(12) }).run(l))
        server.box(keys).end()
        assertEquals(SyncRound.Outcome.Ended, r.run(l))
    }

    /** Metres from [p] to the segment [a]–[b] (flat, fine for a few km). */
    private fun offLine(p: TrackPt, a: TrackPt, b: TrackPt): Double {
        val k = cos(Math.toRadians(p.lat)) * 111_320
        fun x(t: TrackPt) = t.lon * k
        fun y(t: TrackPt) = t.lat * 110_540
        val dx = x(b) - x(a); val dy = y(b) - y(a)
        val len2 = dx * dx + dy * dy
        val f = if (len2 == 0.0) 0.0 else (((x(p) - x(a)) * dx + (y(p) - y(a)) * dy) / len2).coerceIn(0.0, 1.0)
        return abs(kotlin.math.hypot(x(a) + f * dx - x(p), y(a) + f * dy - y(p)))
    }

    companion object {
        /** A plan code (two points) for the plan items. */
        val PLAN = com.onpath.app.routing.PlanCode.encode(com.onpath.app.routing.SharedPlan(
            listOf(com.onpath.app.routing.GeoPoint(46.67, 11.15), com.onpath.app.routing.GeoPoint(46.68, 11.16)), Activity.HIKE))
        // filled from the first run (frozen track format version 1)
        const val FROZEN_TRACK = "AQUDAQICAcbcuQT2jYgBgAhi1gEGmAz0DR4BDsbcuQT2jYgBQWxndW5kAQjA6bkEwJ2IAQ"
    }
}

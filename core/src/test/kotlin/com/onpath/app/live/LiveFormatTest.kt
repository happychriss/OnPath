package com.onpath.app.live

import com.onpath.app.routing.GeoPoint
import com.onpath.app.sync.B64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The live link and item formats (docs/design/live-tracking.md §5, §6). The risk: the phone and the viewer page are
 * updated at different times – a byte that moves makes every shared link show nothing; and a server (or a stranger)
 * must not be able to read, change or move what the phone sent.
 */
class LiveFormatTest {
    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val keys = LiveKeys(secret)
    private val nonce = ByteArray(12) { (it + 1).toByte() }

    private val head = LiveHead(
        state = LiveState.RECORDING, fix = LiveFix(46.689, 11.14, 1522, 1_800_000_000_000L, 6), gpsLost = false,
        arrived = false, offRouteM = 150, distanceM = 12_400, ascentM = 640, movingS = 11_400,
        startedMs = 1_799_988_000_000L, toGoM = 6_200, arrivalMs = 1_800_006_000_000L, stillS = null, batteryPct = 43,
        activity = "HIKE", name = "Christian", counter = 95, nextS = 120,
    )
    private val line = listOf(GeoPoint(46.689, 11.14, 1522.0), GeoPoint(46.6895, 11.1406, 1530.0), GeoPoint(46.69, 11.141, 1528.0))
    private val points = listOf(
        LivePoint(0, 46.689, 11.14, 1522, 1_800_000_000_000L),
        LivePoint(0, 46.68905, 11.14006, 1523, 1_800_000_004_000L),
        LivePoint(1, 46.6892, 11.1402, 1525, 1_800_000_090_000L),
    )

    private fun hex(b: ByteArray) = b.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    /** Guards: a changed code or key derivation – every link already sent would stop opening its tour. The values were
     * checked apart from this code with Python's hmac and hashlib (HKDF-SHA256, salt "OnPath live v1"). */
    @Test
    fun theLinkAndItsKeysAreFrozen() {
        assertEquals(FROZEN_CODE, LiveCode.encode(secret))
        assertEquals("https://plan.onpath.earth/live/#t=$FROZEN_CODE", LiveCode.link(secret))
        assertEquals(FROZEN_BOX, keys.boxId)
        assertEquals(FROZEN_READ, keys.readToken)
        assertEquals(22, keys.boxId.length)
        assertEquals(43, keys.readToken.length)
    }

    /** Guards: the link pasted in any form (whole link, `t=`, bare code, with spaces) must open; anything else must not be taken for one. */
    @Test
    fun aLinkIsReadInEveryFormAndNothingElseIs() {
        val code = LiveCode.encode(secret)
        for (t in listOf(LiveCode.link(secret), "  t=$code ", code, "https://x/live/?a=1&t=$code", "https://plan.onpath.earth/live/#t=${code}AAAA"))
            assertArrayEquals(t, secret, LiveCode.parse(t))
        for (t in listOf("", " ", "t=", "t=!!", "#t=" + code.dropLast(2), code.dropLast(2), "#t=A", "hello world",
            "https://plan.onpath.earth/#pair=$code", "https://plan.onpath.earth/#p=$code", B64.encode(byteArrayOf(2) + secret)))
            assertNull(t, LiveCode.parse(t))
        assertNotNull(LiveKeys.of(LiveCode.link(secret)))
        assertNull(LiveKeys.of("nothing"))
    }

    /**
     * Guards: the link is read-only. Its keys must be the live ones and nothing else – not the plan sync's keys of the
     * same secret, and no member that could serve as a write token.
     */
    @Test
    fun theLinkGivesReadKeysOnly() {
        val sync = com.onpath.app.sync.SyncKeys(secret)
        assertTrue(keys.boxId != sync.mailbox && keys.readToken != sync.token)
        val names = LiveKeys::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("boxBytes", "boxId", "readToken", "content"), names)
    }

    /** Guards: the head's bytes – a moved tag or number would show the family a wrong state or place. */
    @Test
    fun theHeadIsFrozenAndReadsBack() {
        val b = LiveItem.head(head)
        assertEquals(FROZEN_HEAD, hex(b))
        assertEquals(head, LiveItem.readHead(b))
        val bare = LiveHead(LiveState.PAUSED, stillS = 720, gpsLost = true, arrived = true, nextS = 600)
        assertEquals(bare, LiveItem.readHead(LiveItem.head(bare)))
        assertEquals(LiveHead(LiveState.ENDED), LiveItem.readHead(LiveItem.head(LiveHead(LiveState.ENDED))))
    }

    /** Guards: the followed route's bytes and its thinning – a long plan must still fit an item and keep its ends. */
    @Test
    fun theRouteIsFrozenReadsBackAndALongOneIsThinned() {
        val b = LiveItem.route(line)
        assertEquals(FROZEN_ROUTE, hex(b))
        assertEquals(line, LiveItem.readRoute(b))
        assertEquals(emptyList<GeoPoint>(), LiveItem.readRoute(LiveItem.route(emptyList())))
        val rnd = Random(7)
        var lat = 46.0; var lon = 11.0
        val long = List(30_000) { lat += rnd.nextDouble() * 0.0004; lon += (rnd.nextDouble() - 0.4) * 0.0004; GeoPoint(lat, lon, 500.0 + it % 900) }
        val thin = LiveItem.route(long)
        val back = LiveItem.readRoute(thin)!!
        assertTrue("${thin.size} bytes", thin.size <= LiveItem.MAX_PLAIN)
        assertTrue("${back.size} points", back.size in 100..LiveItem.MAX_ROUTE_POINTS)
        assertEquals(long.first().lat, back.first().lat, 1e-5)
        assertEquals(long.last().lon, back.last().lon, 1e-5)
    }

    /** Guards: the track chunk's bytes – the way so far; segments (a gap is no straight line) and times must survive. */
    @Test
    fun aChunkIsFrozenAndReadsBack() {
        val b = LiveItem.chunk(points)
        assertEquals(FROZEN_CHUNK, hex(b))
        assertEquals(points, LiveItem.readChunk(b))
        // a point without elevation: the chunk carries none, everything else stays
        val mixed = points + LivePoint(1, 46.6893, 11.1403, null, 1_800_000_095_000L)
        assertEquals(mixed.map { it.copy(eleM = null) }, LiveItem.readChunk(LiveItem.chunk(mixed)))
        assertEquals(emptyList<LivePoint>(), LiveItem.readChunk(LiveItem.chunk(emptyList())))
    }

    /** Guards: about 7 bytes a recorded point (the design's upload size) – a walking hour must stay a few KB. */
    @Test
    fun aWalkingHourIsAFewKilobytes() {
        val rnd = Random(3)
        var lat = 46.5; var lon = 11.3; var t = 1_800_000_000_000L; var ele = 900
        val hour = List(1000) { lat += 0.00004 + rnd.nextDouble() * 0.00003; lon += (rnd.nextDouble() - 0.5) * 0.00008; t += 2000 + rnd.nextInt(3000); ele += rnd.nextInt(3) - 1; LivePoint(0, lat, lon, ele, t) }
        val size = LiveItem.chunk(hour).size
        assertTrue("$size bytes for 1000 points", size < 8000)
    }

    /** Guards: a page meeting a newer app (fields it does not know) must still show the tour, not "broken link". */
    @Test
    fun unknownFieldsAreSkipped() {
        val newer = byteArrayOf(99, 3, 1, 2, 3) + byteArrayOf(0x90.toByte(), 0x4e, 0) // tag 99 with 3 bytes, tag 10000 empty
        assertEquals(head, LiveItem.readHead(LiveItem.head(head) + newer))
        assertEquals(line, LiveItem.readRoute(LiveItem.route(line) + newer))
        assertEquals(points, LiveItem.readChunk(LiveItem.chunk(points) + newer))
    }

    /** Guards: damaged or foreign bytes never crash the page or the app – they read as "nothing". */
    @Test
    fun damagedBytesReadAsNothing() {
        val items = listOf(LiveItem.head(head), LiveItem.route(line), LiveItem.chunk(points))
        for (b in items) for (cut in 0 until b.size) {
            val part = b.copyOf(cut)
            LiveItem.readHead(part); LiveItem.readRoute(part); LiveItem.readChunk(part) // must not throw
        }
        val rnd = Random(11)
        repeat(2000) {
            val junk = ByteArray(rnd.nextInt(60)).also(rnd::nextBytes)
            if (junk.isNotEmpty()) junk[0] = 1
            LiveItem.readHead(junk); LiveItem.readRoute(junk); LiveItem.readChunk(junk)
        }
        assertNull(LiveItem.readHead(LiveItem.route(line)))
        assertNull(LiveItem.readRoute(LiveItem.chunk(points)))
        assertNull(LiveItem.readChunk(LiveItem.head(head)))
        assertNull(LiveItem.readHead(byteArrayOf(2, 1)))
        assertNull("a state this reader does not know", LiveItem.readHead(byteArrayOf(1, 1, 1, 1, 9)))
    }

    /** Guards: the sealed blob's layout, and that it opens to exactly what went in. */
    @Test
    fun aBlobIsFrozenAndOpens() {
        val blob = LiveItem.seal(keys, LiveItem.HEAD, LiveItem.head(head), nonce)
        assertEquals(FROZEN_BLOB, LiveItem.toText(blob))
        assertArrayEquals(LiveItem.head(head), LiveItem.open(keys, LiveItem.HEAD, blob))
        assertArrayEquals(blob, LiveItem.fromText(LiveItem.toText(blob)))
        assertEquals(1 + 12 + LiveItem.head(head).size + 16, blob.size)
        // the platform's AES-GCM opens the same blob (the frozen text is not just "what our own code made")
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keys.content, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
        c.updateAAD(byteArrayOf(1) + keys.boxBytes + "h".toByteArray())
        assertArrayEquals(LiveItem.head(head), c.doFinal(blob, 13, blob.size - 13))
    }

    /** Guards: the server (or anyone on the way) changing a single bit of a stored item – the page must refuse it. */
    @Test
    fun everyFlippedByteIsRefused() {
        val blob = LiveItem.seal(keys, LiveItem.chunkName(3), LiveItem.chunk(points), nonce)
        assertNotNull(LiveItem.open(keys, "t0003", blob))
        for (i in blob.indices) for (bit in listOf(1, 0x80)) {
            val bad = blob.copyOf().also { it[i] = (it[i].toInt() xor bit).toByte() }
            assertNull("byte $i", LiveItem.open(keys, "t0003", bad))
        }
        assertNull(LiveItem.open(keys, "t0003", blob.copyOf(blob.size - 1)))
        assertNull(LiveItem.open(keys, "t0003", ByteArray(0)))
    }

    /**
     * Guards: the server moving blobs around – an old chunk served as the head, chunks swapped, or one tour's data
     * served in another tour's box – and a stranger's key.
     */
    @Test
    fun aBlobOpensOnlyUnderItsNameInItsBoxWithItsKey() {
        val plain = LiveItem.chunk(points)
        val blob = LiveItem.seal(keys, LiveItem.chunkName(3), plain, nonce)
        for (name in listOf("t0004", "t0000", "t00003", LiveItem.HEAD, LiveItem.ROUTE)) assertNull(name, LiveItem.open(keys, name, blob))
        val other = LiveKeys(ByteArray(32) { (it * 5 + 2).toByte() })
        assertNull(LiveItem.open(other, "t0003", blob))
        assertNull("no such item name", LiveItem.open(keys, "x", blob))
    }

    /** Guards: item names – the uploader, the server and the page must agree which names exist and how chunks count. */
    @Test
    fun itemNames() {
        assertEquals("t0000", LiveItem.chunkName(0))
        assertEquals("t0042", LiveItem.chunkName(42))
        assertEquals("t12345", LiveItem.chunkName(12_345))
        assertEquals(42, LiveItem.chunkNumber("t0042"))
        assertEquals(12_345, LiveItem.chunkNumber("t12345"))
        for (bad in listOf("t42", "t", "h1", "T0001", "t00a1", "t-001", "", "t00000000000")) assertNull(bad, LiveItem.chunkNumber(bad))
        assertTrue(LiveItem.isName("h") && LiveItem.isName("r") && LiveItem.isName("t0007"))
        assertTrue(!LiveItem.isName("x") && !LiveItem.isName("") && !LiveItem.isName("t7"))
    }

    /**
     * Guards: catching up after hours without network – chunks must stay within an item's size, never cut a point, lose
     * or double none, and a cut sent again must be byte for byte the same (a re-sent chunk overwrites itself).
     */
    @Test
    fun theChunkerCutsWholePointsWithinTheSize() {
        val rnd = Random(5)
        var lat = 46.5; var lon = 11.3; var t = 1_800_000_000_000L
        val all = List(9000) {
            lat += (rnd.nextDouble() - 0.3) * 0.0002; lon += (rnd.nextDouble() - 0.5) * 0.0003; t += 1000 + rnd.nextInt(60_000)
            LivePoint(it / 700, lat, lon, if (it in 2500..2510) null else 800 + rnd.nextInt(40), t)
        }
        assertNull(LiveChunker.next(emptyList()))
        for (max in listOf(LiveItem.MAX_PLAIN, 2000, 300)) {
            var from = 0
            val back = ArrayList<LivePoint>()
            var chunks = 0
            while (from < all.size) {
                val rest = all.subList(from, all.size)
                val cut = LiveChunker.next(rest, max)!!
                assertTrue("chunk of ${cut.bytes.size} > $max", cut.bytes.size <= max)
                assertTrue(cut.count in 1..LiveChunker.MAX_POINTS)
                assertArrayEquals(LiveItem.chunk(rest.subList(0, cut.count)), cut.bytes)
                assertArrayEquals(cut.bytes, LiveChunker.next(rest, max)!!.bytes)
                val read = LiveItem.readChunk(cut.bytes)!!
                assertEquals(cut.count, read.size)
                back += read
                from += cut.count; chunks++
            }
            assertEquals(all.size, back.size)
            for (i in all.indices) {
                assertEquals(all[i].seg, back[i].seg); assertEquals(all[i].timeMs, back[i].timeMs)
                assertEquals(all[i].lat, back[i].lat, 0.6e-5); assertEquals(all[i].lon, back[i].lon, 0.6e-5)
            }
            if (max == LiveItem.MAX_PLAIN) assertTrue("$chunks chunks", chunks in 3..12)
        }
        // a sealed full chunk fits the server's 16 KiB an item as text
        val full = LiveChunker.next(all)!!
        assertTrue(LiveItem.toText(LiveItem.seal(keys, "t0000", full.bytes, nonce)).length <= 16 * 1024)
        // one point alone always goes, however small the limit
        assertEquals(1, LiveChunker.next(all, 5)!!.count)
    }

    private companion object {
        // test vector from the dummy secret i*7+1 – not a credential
        const val FROZEN_CODE = "AQEIDxYdJCsyOUBHTlVcY2pxeH-GjZSboqmwt77FzNPa" // ggignore
        // test vector from the dummy secret i*7+1 – not a credential
        const val FROZEN_BOX = "0dPnimRkezLc3fCguuX6rg" // ggignore
        // test vector from the dummy secret i*7+1 – not a credential
        const val FROZEN_READ = "aFVOTMzUKm5qEBrhELBD-BkTFhqMML-Q55w9JiKjmjo" // ggignore
        const val FROZEN_HEAD = "01010101010212c8f7b904a0fe870180a0f1c2b13403e41706040296010502f0600602800507028859080680ea94bdb1340902b8300a0680bbdfc5b1340c012b0d0448494b450e0943687269737469616e0f015f100178"
        const val FROZEN_ROUTE = "01020103c8f7b904a0fe8701e417647810645003"
        const val FROZEN_CHUNK = "0103010300c8f7b904a0fe8701e41780c0e285e368000a0c02c03e011e1c04e0bf0a"
        // test vector from the dummy secret i*7+1 – not a credential
        const val FROZEN_BLOB = "AQECAwQFBgcICQoLDE_3Q3rz5V0JLGI3QZkonXZtlvD7npf9uIwx4doClVwJXnfFqVzMkIvzaOZC9FMAyAT99prH2vvAbJAo0DKARddqJ4B1K2BRHgDZ79TR5Tx02SQKEJG066brfvQEPfIYeCJR7K1eZEE" // ggignore
    }
}

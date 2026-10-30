package com.onpath.web

import com.onpath.app.sync.PairCode
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncJoin
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncLedger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * The browser's side of A-017 (docs/design/web-sync.md §5.1 and §14 "Folders") on the JVM: the shared JOIN item and
 * hello role give exactly the bytes §5.1 writes, and the page's calls (peek, joinBlob, hello with role, outbox of one
 * item, rebase, tracks) do what the folders need.
 */
class WebSyncJoinTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val browserCode = PairCode.encode(ByteArray(32) { (it * 7 + 1).toByte() }) // the frozen test code (§4)
    private val phoneSecret = ByteArray(32) { (it * 3 + 5).toByte() }
    private val phoneCode = PairCode.encode(phoneSecret)
    private val phone = b64.encodeToString(ByteArray(8) { (0x50 + it).toByte() })

    private fun varint(v: Long) = java.io.ByteArrayOutputStream().apply {
        var x = v; while (x >= 0x80) { write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; write(x.toInt())
    }.toByteArray()

    @Test fun joinItemBytesAsSection51() {
        val sha = MessageDigest.getInstance("SHA-256").digest("OnPath join".toByteArray())
        assertEquals(b64.encodeToString(sha.copyOf(16)), SyncJoin.ID)
        val now = 1_761_800_000_000L
        val want = byteArrayOf(1, 4) + sha.copyOf(16) + Base64.getUrlDecoder().decode(phone) + varint(now) + byteArrayOf(0) +
            byteArrayOf(5, 44) + phoneCode.toByteArray(Charsets.US_ASCII)
        assertArrayEquals(want, SyncFormat.encode(SyncJoin.item(phoneSecret, phone, now)))
    }

    @Test fun helloRoleIsField6AVarint() {
        val id = SyncFormat.deviceItemId(phone)
        val plain = SyncFormat.encode(SyncItem(id, 5, phone, kind = SyncItem.DEVICE, name = "Pixel 8"))
        val withRole = SyncFormat.encode(SyncItem(id, 5, phone, kind = SyncItem.DEVICE, name = "Pixel 8", role = SyncItem.ROLE_BROWSER))
        assertArrayEquals(plain + byteArrayOf(6, 1, 2), withRole)
        // the page's hello carries the role
        val l = SyncLedger.read(WebSync.hello("", phone, "Chrome on Linux", 9.0, SyncItem.ROLE_BROWSER))
        assertEquals(SyncItem.ROLE_BROWSER, l.get(id)!!.role)
    }

    @Test fun peekFindsTheJoinAndTheRoles() {
        val (id, blob) = WebSync.joinBlob(browserCode, phone, "https://plan.onpath.earth/#pair=$phoneCode", 7.0, b64.encodeToString(ByteArray(12) { 3 })).split(' ')
        assertEquals("", WebSync.joinBlob(browserCode, phone, phoneCode, 7.0, "AA")) // nonce too short
        assertEquals(SyncJoin.ID, id)
        val pl = WebSync.hello("", phone, "Pixel 8", 9.0, SyncItem.ROLE_PHONE)
        val phoneHello = WebSync.outbox(pl, browserCode, b64.encodeToString(ByteArray(12) { 4 })).split(' ')
        val peek = WebSync.peek(browserCode, "$id 1 $blob\n${phoneHello[0]} 2 ${phoneHello[2]}")
        assertTrue(peek, peek.contains("\"join\":{\"device\":\"$phone\",\"code\":\"$phoneCode\"}"))
        assertTrue(peek, peek.contains("\"device\":\"$phone\",\"name\":\"Pixel 8\",\"role\":1,\"deleted\":false"))
        // another mailbox's key cannot open it
        assertEquals("{\"join\":null,\"hellos\":[]}", WebSync.peek(phoneCode, "$id 1 $blob"))
        // and the shared ledger never lists a JOIN as a route
        val l = SyncLedger.read(WebSync.apply("", browserCode, "$id 1 $blob", 1.0).substringBefore("#bad"))
        assertTrue(l.plans().isEmpty() && l.tracks().isEmpty())
        assertArrayEquals(phoneSecret, SyncJoin.secretIn(l))
    }

    @Test fun outboxOfOneItemAndRebase() {
        val dev = b64.encodeToString(ByteArray(8) { 1 })
        val id = b64.encodeToString(ByteArray(16) { 2 })
        var l = WebSync.hello("", dev, "Chrome on Linux", 1.0, SyncItem.ROLE_BROWSER)
        l = WebSync.save(l, id, "AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU", "Waalweg", 2.0, dev)
        val rnd = b64.encodeToString(ByteArray(12 * 8) { 5 })
        assertEquals(2, WebSync.outbox(l, browserCode, rnd, "").split('\n').size)
        val onlyHello = WebSync.outbox(l, browserCode, rnd, WebSync.helloId(dev)).split('\n')
        assertEquals(1, onlyHello.size)
        assertTrue(onlyHello[0].startsWith(WebSync.helloId(dev) + " "))
        assertTrue(WebSync.state(l), WebSync.state(l).contains("\"pending\":2,\"unsaved\":1"))
        // §14.6: the list says what is not sent yet (bold) and what is in the mailbox (📱)
        assertTrue(WebSync.list(l, dev), WebSync.list(l, dev).contains("\"mine\":true,\"dirty\":true,\"onPhone\":false"))
        val routeLine = WebSync.outbox(l, browserCode, rnd, id).split(' ')
        val sent = WebSync.acked(l, routeLine[3], 7.0)
        assertTrue(WebSync.list(sent, dev), WebSync.list(sent, dev).contains("\"dirty\":false,\"onPhone\":true"))
        // read again from the start: the same mailbox keeps everything; a new mailbox keeps only the unsaved route
        val same = SyncLedger.read(WebSync.rebase(l, true))
        assertEquals(0, same.since)
        assertEquals(2, same.entries.size)
        val acked = WebSync.acked(l, onlyHello[0].split(' ')[3], 4.0)
        val other = SyncLedger.read(WebSync.rebase(acked, false))
        assertEquals(listOf(id), other.entries.keys.toList())
        assertTrue(other.entries[id]!!.dirty)
    }

    @Test fun trackFitGpxAndList() {
        val t = WebSync.trackFit("46.67,11.16,300;46.68,11.17,320|46.69,11.18", "46.67 11.16 Start hut", "HIKE", 3)
        assertTrue(t.isNotEmpty())
        val gpx = WebSync.trackGpx(t, "Ifinger")
        assertTrue(gpx, gpx.contains("<name>Ifinger</name>") && gpx.contains("Start hut") && gpx.contains("lat=\"46.68"))
        assertEquals("", WebSync.trackFit("", "", "HIKE", 2))
        val dev = b64.encodeToString(ByteArray(8) { 1 })
        val l = WebSync.saveTrack("", b64.encodeToString(ByteArray(16) { 3 }), t, "Ifinger", 1.0, dev)
        assertTrue(WebSync.list(l, dev), WebSync.list(l, dev).contains("\"kind\":\"track\",\"title\":\"Ifinger\""))
        assertTrue(SyncKeys.of("pair=$browserCode") != null)
    }
}

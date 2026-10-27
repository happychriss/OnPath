package com.onpath.app.sync

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Pairing code, keys, item and blob formats, the conflict rule and the local ledger (docs/design/web-sync.md). */
class PlanSyncTest {
    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val keys = SyncKeys(secret)
    private val phone = SyncFormat.newDevice(ByteArray(8) { 1 })
    private val web = SyncFormat.newDevice(ByteArray(8) { 2 })
    private val plan = PlanCode.encode(SharedPlan(listOf(GeoPoint(46.6717, 11.15), GeoPoint(46.6879, 11.1111)), Activity.HIKE, 3, title = "Waalweg"))
    private fun id(n: Int) = SyncFormat.newId(ByteArray(16) { n.toByte() })
    private fun item(n: Int, at: Long, dev: String = phone, title: String? = "Plan $n") =
        SyncItem(id(n), at, dev, title = title, plan = plan)

    // ---- pairing code ----

    @Test fun pairingLinkRoundTrip() {
        val link = PairCode.link("https://plan.onpath.earth/#p=old", secret)
        assertTrue(link, link.startsWith("https://plan.onpath.earth/#pair="))
        assertEquals(44, link.substringAfter("#pair=").length)
        assertTrue(secret.contentEquals(PairCode.parse(link)!!))
        assertTrue(secret.contentEquals(PairCode.parse("  pair=" + PairCode.encode(secret) + " ")!!))
        assertTrue(secret.contentEquals(PairCode.parse("https://x/?a=1&pair=" + PairCode.encode(secret))!!))
    }

    @Test fun pairingParseNeverThrows() {
        val code = PairCode.encode(secret)
        for (t in listOf("", " ", "pair=", "pair=!!", "#pair=" + code.dropLast(2), "#pair=A", PairCode.encode(secret),
            "#p=$code", "#pair=" + "B" + code.drop(1), "https://plan.onpath.earth/#p=$plan", "x".repeat(5000))) {
            assertNull(t.take(40), PairCode.parse(t))
        }
        val rnd = Random(1)
        repeat(500) { PairCode.parse("#pair=" + String(CharArray(rnd.nextInt(0, 60)) { "AZaz09-_x".random(rnd) })) }
    }

    @Test fun planLinksAndPairingLinksDoNotMix() {
        assertNull(PlanCode.parse(PairCode.link("https://plan.onpath.earth/", secret)))
        assertNull(PairCode.parse(PlanCode.link("https://plan.onpath.earth/", PlanCode.decode(plan)!!)))
    }

    /** Frozen: the browser test (web/app/test/sync-smoke.mjs) and the app's tests check the same values. */
    @Test fun keysAreFrozenForVersion1() {
        assertEquals("AQEIDxYdJCsyOUBHTlVcY2pxeH-GjZSboqmwt77FzNPa", PairCode.encode(secret))
        assertEquals(22, keys.mailbox.length)
        assertEquals(43, keys.token.length)
        assertEquals(FROZEN_MAILBOX, keys.mailbox)
        assertEquals(FROZEN_TOKEN, keys.token)
        assertEquals(FROZEN_CHECK, keys.check)
        assertTrue(Regex("""\d{3} \d{3}""").matches(keys.check))
        val other = SyncKeys(ByteArray(32))
        assertNotEquals(keys.mailbox, other.mailbox)
        assertNotEquals(keys.token, other.token)
        assertFalse(keys.content.contentEquals(other.content))
    }

    @Test fun blobIsFrozenForVersion1() {
        val it = SyncItem(id(9), 1_793_000_000_000, web, title = "Waalweg", plan = plan)
        val blob = SyncFormat.seal(keys, it, ByteArray(12) { 5 })
        assertEquals(FROZEN_BLOB, SyncFormat.toText(blob))
        assertEquals(it, SyncFormat.open(keys, id(9), blob))
    }

    // ---- item + blob ----

    @Test fun itemRoundTrip() {
        val a = SyncItem(id(1), 1_793_000_000_123, web, title = "Ü ß ✓ \u0007tab", plan = plan)
        val back = SyncFormat.decode(SyncFormat.encode(a))!!
        assertEquals(a.copy(title = "Ü ß ✓ tab"), back)
        val dev = SyncItem(SyncFormat.deviceItemId(phone), 5, phone, kind = SyncItem.DEVICE, name = "Pixel 8")
        assertEquals(dev, SyncFormat.decode(SyncFormat.encode(dev)))
        val tomb = SyncItem(id(2), 7, phone, deleted = true)
        assertEquals(tomb, SyncFormat.decode(SyncFormat.encode(tomb)))
        assertEquals(PlanCode.decode(plan), PlanCode.decode(back.plan!!))
    }

    @Test fun itemSkipsUnknownFieldsAndRefusesDamage() {
        val bytes = SyncFormat.encode(item(1, 10)) + byteArrayOf(9, 2, 1, 2) // tag 9, 2 bytes
        assertEquals(item(1, 10), SyncFormat.decode(bytes))
        val good = SyncFormat.encode(item(1, 10))
        for (n in 0 until good.size - 1) SyncFormat.decode(good.copyOf(n)) // never throws
        assertNull(SyncFormat.decode(good.copyOf(20)))
        assertNull(SyncFormat.decode(byteArrayOf(2) + good.drop(1)))
        val rnd = Random(3)
        repeat(2000) { SyncFormat.decode(rnd.nextBytes(rnd.nextInt(0, 80))) }
    }

    @Test fun blobOpensOnlyWithTheRightKeyAndPlace() {
        val it = item(1, 10)
        val blob = SyncFormat.seal(keys, it, ByteArray(12) { 3 })
        assertEquals(it, SyncFormat.open(keys, it.id, blob))
        assertNull(SyncFormat.open(SyncKeys(ByteArray(32)), it.id, blob))
        assertNull(SyncFormat.open(keys, id(2), blob)) // moved to another item
        assertNull(SyncFormat.open(keys, it.id, blob.copyOf(blob.size - 1)))
        assertNull(SyncFormat.open(keys, it.id, byteArrayOf(2) + blob.drop(1)))
        assertNull(SyncFormat.open(keys, "x", blob))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("Plan 1"))
        assertTrue(blob.size < SyncFormat.MAX_BLOB)
    }

    @Test fun javaxCryptoOpensTheSameBlob() {
        val jca = object : Aead {
            override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
                javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
                    updateAAD(aad)
                }.doFinal(plain)
            override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
                javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
                    updateAAD(aad)
                }.doFinal(sealed)
            } catch (_: javax.crypto.AEADBadTagException) { null }
        }
        val it = item(4, 99)
        val pure = SyncFormat.seal(keys, it, ByteArray(12) { 6 })
        assertTrue(pure.contentEquals(SyncFormat.seal(keys, it, ByteArray(12) { 6 }, jca)))
        assertEquals(it, SyncFormat.open(keys, it.id, pure, jca))
    }

    // ---- conflict rule ----

    @Test fun newerWinsOlderStaysAsCopy() {
        val older = item(1, 100, phone, "Phone name")
        val newer = item(1, 200, web, "Browser name")
        val m = SyncMerge.merge(older, newer)
        assertEquals(newer, m.winner)
        assertEquals("Phone name (copy)", m.copy!!.title)
        assertEquals(older.plan, m.copy!!.plan)
        assertNotEquals(older.id, m.copy!!.id)
        assertEquals(m, SyncMerge.merge(newer, older)) // order-independent
        assertEquals(SyncMerge.Merged(newer, null), SyncMerge.merge(newer, newer)) // idempotent
    }

    @Test fun sameTimeIsDecidedTheSameEverywhere() {
        val a = item(1, 100, phone, "A"); val b = item(1, 100, web, "B")
        assertEquals(SyncMerge.merge(a, b), SyncMerge.merge(b, a))
        val c = item(1, 100, phone, "C")
        assertEquals(SyncMerge.merge(a, c), SyncMerge.merge(c, a))
    }

    @Test fun deletesAndCopies() {
        val edit = item(1, 100, phone, "Kept")
        val del = SyncItem(id(1), 200, web, deleted = true)
        val m = SyncMerge.merge(edit, del)
        assertTrue(m.winner.deleted)
        assertEquals("Kept (copy)", m.copy?.title) // a newer delete still keeps the older edit
        val m2 = SyncMerge.merge(item(1, 300, phone), del)
        assertEquals(300, m2.winner.updatedAt)
        assertNull(m2.copy) // a losing delete leaves nothing
        val noTitle = SyncMerge.copyOf(item(1, 1, title = null))
        assertEquals("Plan (copy)", noTitle.title)
        assertEquals(SyncFormat.MAX_TITLE, SyncMerge.copyOf(item(1, 1, title = "x".repeat(200))).title!!.length)
        assertNull(SyncMerge.merge(item(1, 1, phone, "Same"), item(1, 2, web, "Same")).copy) // same content, no copy
    }

    @Test fun reconcileTakesNewUpdatesAndMergesConflicts() {
        val base = mapOf(id(1) to SyncEntry(item(1, 100), rev = 3, dirty = false))
        // plain update from the other side
        val upd = SyncMerge.reconcile(base, listOf(RemoteItem(item(1, 150, web, "Renamed"), 5)))
        assertEquals(SyncEntry(item(1, 150, web, "Renamed"), 5, false), upd[id(1)])
        // already seen: ignored
        assertEquals(base, SyncMerge.reconcile(base, listOf(RemoteItem(item(1, 999, web, "Old"), 3))))
        // new item
        assertEquals(2, SyncMerge.reconcile(base, listOf(RemoteItem(item(2, 1, web), 6))).size)
        // edited here and there: newer wins, older is a copy to upload
        val mine = mapOf(id(1) to SyncEntry(item(1, 300, phone, "Mine"), rev = 3, dirty = true))
        val out = SyncMerge.reconcile(mine, listOf(RemoteItem(item(1, 200, web, "Theirs"), 5)))
        assertEquals(SyncEntry(item(1, 300, phone, "Mine"), 5, true), out[id(1)])
        val copy = out.values.single { it.item.id != id(1) }
        assertEquals("Theirs (copy)", copy.item.title)
        assertTrue(copy.dirty)
        // the same page again changes nothing
        assertEquals(out, SyncMerge.reconcile(out, listOf(RemoteItem(item(1, 200, web, "Theirs"), 5))))
    }

    @Test fun bothSidesEndWithTheSameItems() {
        // phone and browser edit the same plan offline, then sync in either order: same two plans on both
        val start = SyncEntry(item(1, 100), 1, false)
        val p = SyncEntry(item(1, 200, phone, "Phone"), 1, true)
        val w = SyncEntry(item(1, 300, web, "Web"), 1, true)
        // browser uploads first (rev 2); phone then reads it and merges
        val phoneSide = SyncMerge.reconcile(mapOf(id(1) to p), listOf(RemoteItem(w.item, 2)))
        val phoneUploads = phoneSide.values.filter { it.dirty }.map { it.item }
        // browser reads the phone's uploads (copy as rev 3)
        val webSide = SyncMerge.reconcile(mapOf(id(1) to SyncEntry(w.item, 2, false)), phoneUploads.mapIndexed { i, it -> RemoteItem(it, 3L + i) })
        assertEquals(phoneSide.values.map { it.item }.toSet(), webSide.values.map { it.item }.toSet())
        assertEquals(setOf("Web", "Phone (copy)"), webSide.values.map { it.item.title }.toSet())
        assertNotNull(start)
    }

    @Test fun ackedKeepsALaterEditDirty() {
        val sent = item(1, 100)
        assertEquals(SyncEntry(sent, 7, false), SyncMerge.acked(SyncEntry(sent, 0, true), sent, 7))
        val later = item(1, 101, title = "Later")
        assertEquals(SyncEntry(later, 7, true), SyncMerge.acked(SyncEntry(later, 0, true), sent, 7))
    }

    // ---- ledger ----

    @Test fun ledgerSaveRenameDeleteAndRoundTrip() {
        val l = SyncLedger.empty()
        assertNull(l.save("bad", plan, "x", 1, web))
        l.save(id(1), plan, "Waalweg", 1000, web)
        l.save(id(2), plan, "Ifinger", 2000, web)
        assertEquals(listOf("Ifinger", "Waalweg"), l.plans().map { it.title })
        assertTrue(l.rename(id(1), "Waalweg Algund", 500, web)) // clock behind: still a newer version
        assertEquals(1001, l.get(id(1))!!.updatedAt)
        assertTrue(l.delete(id(2), 3000, web))
        assertFalse(l.delete(id(2), 3001, web))
        assertFalse(l.rename(id(2), "x", 3002, web))
        assertEquals(listOf("Waalweg Algund"), l.plans().map { it.title })
        l.hello(web, "Firefox on Linux", 10)
        l.hello(web, "Firefox on Linux", 11) // unchanged: not written again
        assertEquals(10, l.get(SyncFormat.deviceItemId(web))!!.updatedAt)
        assertEquals(3, l.outbox().size)
        val back = SyncLedger.read(l.write())
        assertEquals(l.entries, back.entries)
        assertEquals(l.write(), back.write())
    }

    @Test fun ledgerNeverLosesWhatItCannotRead() {
        val l = SyncLedger.empty().apply { save(id(1), plan, "A", 1, web) }
        val text = l.write() + "garbage line\n5 d !!!\n"
        val back = SyncLedger.read(text)
        assertEquals(1, back.plans().size)
        assertEquals(2, back.unreadable)
        assertEquals(text, back.write())
        assertEquals(0, SyncLedger.read(null).plans().size)
        assertTrue(SyncLedger.read("onpath-sync 2 9\nfuture\n").write().contains("future"))
        val rnd = Random(5)
        repeat(300) { SyncLedger.read("onpath-sync 1 0\n" + String(CharArray(rnd.nextInt(0, 80)) { "0123 d-AZaz_\n".random(rnd) })) }
    }

    @Test fun twoLedgersSyncThroughAMailbox() {
        val box = FakeMailbox()
        val a = SyncLedger.empty(); val b = SyncLedger.empty()
        a.hello(web, "Browser", 1); b.hello(phone, "Pixel", 1)
        a.save(id(1), plan, "Waalweg", 100, web)
        box.round(a); box.round(b); box.round(a)
        assertEquals(listOf("Waalweg"), b.plans().map { it.title })
        assertEquals("Pixel", a.devices(web).single().name)
        assertEquals("Browser", b.devices(phone).single().name)
        // both edit offline; the phone is newer
        a.rename(id(1), "Browser name", 200, web)
        b.rename(id(1), "Phone name", 300, phone)
        box.round(a) // browser first
        box.round(b) // phone: 409, merges, uploads winner + copy
        box.round(a)
        val want = setOf("Phone name", "Browser name (copy)")
        assertEquals(want, a.plans().map { it.title }.toSet())
        assertEquals(want, b.plans().map { it.title }.toSet())
        assertTrue(a.outbox().isEmpty() && b.outbox().isEmpty())
        // delete reaches the other side
        b.delete(id(1), 400, phone)
        box.round(b); box.round(a)
        assertEquals(listOf("Browser name (copy)"), a.plans().map { it.title })
        // unpair: plans stay, all to upload again
        a.forget()
        assertEquals(0, a.since)
        assertTrue(a.outbox().all { it.item.kind == SyncItem.PLAN } && a.outbox().size == 1)
        assertFalse(box.stored().contains("Waalweg"))
    }

    /** The mailbox's rules (web/host/src/sync.js) in a few lines: revisions, compare-and-set on the base revision. */
    private inner class FakeMailbox {
        private var rev = 0L
        private val items = LinkedHashMap<String, Pair<Long, ByteArray>>()
        private var n = 0
        fun stored() = items.values.joinToString { String(it.second, Charsets.ISO_8859_1) }

        fun round(l: SyncLedger) {
            repeat(3) {
                l.apply(keys, items.filter { it.value.first > l.since }.map { Triple(it.key, it.value.first, it.value.second) }, rev)
                var conflict = false
                for (e in l.outbox()) {
                    val cur = items[e.item.id]?.first ?: 0
                    if (cur != e.rev) { conflict = true; continue }
                    rev++
                    items[e.item.id] = rev to l.seal(keys, e, ByteArray(12) { (n++).toByte() })
                    l.acked(e.item, rev)
                }
                if (!conflict) return
            }
        }
    }

    companion object {
        // filled from the first run (frozen format version 1)
        const val FROZEN_MAILBOX = "6_ppugL_I2cbDsZYhdOddA"
        const val FROZEN_TOKEN = "wHErkFiAjM3dUXVy18TJETV0arl4vvuSa21gZUBEpjE"
        const val FROZEN_CHECK = "395 514"
        const val FROZEN_BLOB = "AQUFBQUFBQUFBQUFBezwy_DS4VFukiv3lciLKrMNNEjd7xcqYoHVER5ZBI4rz0TPbKW5EUMZ1tFYUge1Y3M852rKXkBCQZmq1iHBROEndXRK7OMGVOO4YsmD_ALasLkzfJMGpmhFHoqWt-FC"
    }
}

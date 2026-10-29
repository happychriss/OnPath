package com.onpath.app.plansync

import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncTrackTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The plan sync's files (files/sync/, docs/design/web-sync.md §11.2): written atomically, read back the same, a crash
 * while writing leaves the old file; the pairing, the device id, the links; and the folder is kept out of Android's
 * backup (a restored phone must not reuse an old mailbox).
 */
class SyncFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun id(n: Int) = SyncFormat.newId(ByteArray(16) { n.toByte() })
    private val dev = SyncFormat.newDevice(ByteArray(8) { 3 })

    @Test fun ledgerFileRoundTrip() {
        val f = SyncFiles(File(tmp.root, "sync"))
        assertEquals(0, f.ledger().entries.size) // no file yet: empty
        val l = SyncLedger.empty()
        l.save(id(1), SyncTrackTest.PLAN, "Waalweg", 10, dev)
        l.saveTrack(id(2), com.onpath.app.sync.TrackCode.fit(com.onpath.app.sync.SyncTrack(
            listOf(listOf(com.onpath.app.sync.TrackPt(46.1, 11.1, 500.0), com.onpath.app.sync.TrackPt(46.2, 11.2, 510.0))),
            com.onpath.app.routing.Activity.HIKE))!!, "GPX", 11, dev)
        l.hello(dev, "Pixel 8", 12)
        l.acked(l.get(id(1))!!, 4)
        f.saveLedger(l)
        val back = f.ledger()
        assertEquals(l.entries, back.entries)
        assertEquals(l.write(), back.write())
        assertFalse(File(tmp.root, "sync/ledger.txt" + SyncFiles.TMP).exists())
    }

    @Test fun aCrashWhileWritingLeavesTheOldFile() {
        val f = SyncFiles(File(tmp.root, "sync"))
        val l = SyncLedger.empty().apply { save(id(1), SyncTrackTest.PLAN, "Old", 10, dev) }
        f.saveLedger(l)
        // a crash after half of the temp file: the rename never happened
        File(tmp.root, "sync/ledger.txt" + SyncFiles.TMP).writeText("onpath-sync 1 0\n0 d AQEB")
        assertEquals(listOf("Old"), f.ledger().plans().map { it.title })
        // the next write replaces the stale temp file
        f.saveLedger(SyncLedger.empty().apply { save(id(2), SyncTrackTest.PLAN, "New", 10, dev) })
        assertEquals(listOf("New"), f.ledger().plans().map { it.title })
    }

    @Test fun pairingDeviceAndLinks() {
        val f = SyncFiles(File(tmp.root, "sync"))
        assertNull(f.pairing())
        val secret = ByteArray(32) { 7 }
        f.savePairing(secret, 1234)
        val p = f.pairing()!!
        assertTrue(secret.contentEquals(p.secret))
        assertEquals(1234, p.since)
        assertEquals(SyncKeys(secret).mailbox, SyncKeys(p.secret!!).mailbox)
        f.forgetPairing()
        assertNull(f.pairing())
        // a damaged pairing file: not paired, never a crash
        File(tmp.root, "sync/pair.txt").writeText("onpath-pair 1\ncode !!!\n")
        assertNull(f.pairing())
        File(tmp.root, "sync/pair.txt").writeText("garbage")
        assertNull(f.pairing())
        // the device id is made once
        var calls = 0
        val d = f.device { n -> calls++; ByteArray(n) { 1 } }
        assertEquals(d, f.device { n -> calls++; ByteArray(n) { 2 } })
        assertEquals(1, calls)
        assertTrue(SyncFormat.isDevice(d))
        // links + pending
        val links = SyncLinks.empty()
        links.link(id(1), "uuid-a"); links.link(id(2), "uuid-b"); links.markPending(id(2)); links.markPending(id(3))
        links.link(id(4), "uuid-a") // a tour has one item: the newer link wins
        f.saveLinks(links)
        val lb = f.links()
        assertEquals(mapOf(id(2) to "uuid-b", id(4) to "uuid-a"), lb.all)
        assertEquals(setOf(id(2), id(3)), lb.pending)
        assertEquals(id(4), lb.itemOf("uuid-a"))
        assertTrue(SyncLinks.read("nonsense").all.isEmpty())
        f.saveLastOk(99)
        assertEquals(99, f.lastOk())
    }

    /** files/sync/ (pairing secret, ledger) never goes into Android's backup or a device transfer. */
    @Test fun syncFolderIsNotBackedUp() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = File("src/main/res/xml/$name").readText()
            assertFalse(name, Regex("""<include[^>]*path="sync""").containsMatchIn(xml))
            assertTrue(name, Regex("""<exclude domain="file" path="sync/?"""").findAll(xml).count() >= 1)
        }
        assertEquals("sync", SyncFiles.DIR)
    }
}

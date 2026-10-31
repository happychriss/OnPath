package com.onpath.app.plansync

import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncLedger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Field bug 2026-10-31 (owner: "why does my phone say connected when the browser doesn't?"): the browser unpaired itself
 * – it wrote its own hello as deleted, the mailbox stayed – and the phone still said "Paired with browser ✓ · 1 min ago".
 * "Connected" needs a live browser hello in the ledger.
 */
class BrowserLinkTest {
    private val phone = SyncFormat.newDevice(ByteArray(8) { 1 })
    private val web = SyncFormat.newDevice(ByteArray(8) { 9 })
    private val now = 1_793_000_000_000L

    private fun withPhoneHello() = SyncLedger.empty().also { it.hello(phone, "Pixel 8", now, SyncItem.ROLE_PHONE) }

    /** Exactly the phone's ledger of the field bug: its own hello live (role 1), the browser's hello a tombstone. */
    @Test fun theBrowserHelloDeletedIsUnpairedInTheBrowser() {
        val l = withPhoneHello()
        l.hello(web, "Chrome on Linux", now, SyncItem.ROLE_BROWSER)
        l.delete(SyncFormat.deviceItemId(web), now + 1, web) // the browser's Unpair (web sync.ts: syncDelete of its hello)
        val tomb = l.get(SyncFormat.deviceItemId(web))!!
        assertEquals(true, tomb.deleted); assertEquals(SyncItem.DEVICE, tomb.kind); assertEquals(null, tomb.role)
        assertEquals(SyncItem.ROLE_PHONE, l.get(SyncFormat.deviceItemId(phone))!!.role)
        assertEquals(BrowserLink.UNPAIRED, BrowserLink.of(l, phone))
        // the ledger as written and read back (the phone's sync file) says the same
        assertEquals(BrowserLink.UNPAIRED, BrowserLink.of(SyncLedger.read(l.write()), phone))
    }

    @Test fun aLiveBrowserHelloIsConnected() {
        val l = withPhoneHello()
        l.hello(web, "Chrome on Linux", now, SyncItem.ROLE_BROWSER)
        assertEquals(BrowserLink.CONNECTED, BrowserLink.of(l, phone))
        // one browser unpaired, another (A-017 join) still there: connected
        val other = SyncFormat.newDevice(ByteArray(8) { 6 })
        l.hello(other, "Firefox on Windows", now, SyncItem.ROLE_BROWSER)
        l.delete(SyncFormat.deviceItemId(web), now + 1, web)
        assertEquals(BrowserLink.CONNECTED, BrowserLink.of(l, phone))
        // a page from before the role (none): a browser too
        val old = withPhoneHello().also { it.hello(web, "Chrome", now) }
        assertEquals(BrowserLink.CONNECTED, BrowserLink.of(old, phone))
    }

    @Test fun noBrowserHelloYetIsWaiting() {
        assertEquals(BrowserLink.WAITING, BrowserLink.of(SyncLedger.empty(), phone))
        assertEquals(BrowserLink.WAITING, BrowserLink.of(withPhoneHello(), phone))
        // another phone's hello (an older install of this phone) is no browser
        val l = withPhoneHello().also { it.hello(SyncFormat.newDevice(ByteArray(8) { 3 }), "Pixel 7", now, SyncItem.ROLE_PHONE) }
        assertEquals(BrowserLink.WAITING, BrowserLink.of(l, phone))
    }

    /** A browser sent into this mailbox (JOIN) after the old one unpaired: waiting for its hello, not "unpaired". */
    @Test fun afterAJoinItIsWaiting() {
        val l = withPhoneHello()
        l.hello(web, "Chrome on Linux", now, SyncItem.ROLE_BROWSER)
        l.delete(SyncFormat.deviceItemId(web), now + 1, web)
        val scan = PairScan.of(l, phone, now + 60_000)
        assertEquals(BrowserLink.WAITING, BrowserLink.of(l, phone, scan))
        // the same browser pairs again: its new hello over its tombstone → connected
        l.hello(web, "Chrome on Linux", now + 70_000, SyncItem.ROLE_BROWSER)
        assertEquals(BrowserLink.CONNECTED, BrowserLink.of(l, phone, scan))
    }

    /**
     * Owner 2026-10-31: after a scan the phone said "connected" while the browser still said "Warten auf Handy" – an OLD
     * live browser hello (an earlier browser) was in the ledger. After a scan only a hello after it counts.
     */
    @Test fun anOldLiveBrowserHelloIsNoSignOfTheScannedBrowser() {
        val l = withPhoneHello()
        l.hello(web, "Chrome on Linux", now, SyncItem.ROLE_BROWSER) // the earlier browser, still live
        assertEquals(BrowserLink.CONNECTED, BrowserLink.of(l, phone))
        val scanAt = now + 3_600_000
        val scan = PairScan.of(l, phone, scanAt)
        assertEquals("old hello only", BrowserLink.WAITING, BrowserLink.of(l, phone, scan))
        // an old hello the phone had not pulled before the scan (another browser, hours ago) is no sign either
        val older = SyncFormat.newDevice(ByteArray(8) { 4 })
        l.hello(older, "Edge on Windows", scanAt - 2 * 3_600_000, SyncItem.ROLE_BROWSER)
        assertEquals(BrowserLink.WAITING, BrowserLink.of(l, phone, scan))
        // the scanned browser says hello (its clock a minute behind the phone's) → connected
        val scanned = SyncFormat.newDevice(ByteArray(8) { 5 })
        l.hello(scanned, "Firefox on Windows", scanAt - 60_000, SyncItem.ROLE_BROWSER)
        assertEquals(BrowserLink.CONNECTED, BrowserLink.of(l, phone, scan))
        // kept in a file: a restart keeps waiting the same way
        val back = PairScan.read(scan.write())
        assertEquals(scan, back)
        assertEquals(null, PairScan.read("something else"))
        assertEquals(null, PairScan.read(null))
    }

    @Test fun aScanStopsWaitingAfterHalfAnHourWithAGoodPass() {
        val scan = PairScan(now)
        assertEquals(false, scan.expired(now + PairScan.WAIT_MS + 1, passOk = false))
        assertEquals(false, scan.expired(now + 60_000, passOk = true))
        assertEquals(true, scan.expired(now + PairScan.WAIT_MS + 1, passOk = true))
    }
}

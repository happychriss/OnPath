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

    /** A browser sent into this mailbox (JOIN): waiting for its hello, not "unpaired", until it said hello. */
    @Test fun afterAJoinItIsWaiting() {
        assertEquals(BrowserLink.WAITING, BrowserLink.afterJoin(BrowserLink.UNPAIRED, joined = true))
        assertEquals(BrowserLink.UNPAIRED, BrowserLink.afterJoin(BrowserLink.UNPAIRED, joined = false))
        assertEquals(BrowserLink.CONNECTED, BrowserLink.afterJoin(BrowserLink.CONNECTED, joined = true))
    }
}

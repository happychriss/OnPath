package com.onpath.app.plansync

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.Rd5PartsTest
import com.onpath.app.routing.SharedPlan
import com.onpath.app.sync.AesGcm
import com.onpath.app.sync.MemoryServer
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncRound
import com.onpath.app.sync.SyncRoutingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * The routing fingerprint in plan sync (docs/design/routing-fingerprint.md "Sync fields", owner 2026-10-31: browser and
 * app route identically – and say so quietly when they cannot): the phone writes its fingerprint and the plan's rd5 dates
 * into the plans it saves; a plan from the browser with another fingerprint gets the grey line when opened; a plan routed
 * there with newer routing data makes the phone check for that data right away.
 */
class SyncRoutingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mine = "r1-aaaaaaaaaaaa"
    private val theirs = "r1-bbbbbbbbbbbb"
    private val today = 1_793_000_000_000L // day 20752 (2026-10-26)
    private val phoneDevice = SyncFormat.newDevice(ByteArray(8) { 1 })
    private val webDevice = SyncFormat.newDevice(ByteArray(8) { 9 })
    private val a = GeoPoint(46.67171, 11.15003) // E10_N45
    private val b = GeoPoint(46.68512, 11.13470)
    private val far = GeoPoint(47.2, 15.4) // E15_N45
    private fun id(k: Int) = SyncFormat.newId(ByteArray(16) { k.toByte() })
    private fun plan(points: List<GeoPoint>) = PlanCode.encode(SharedPlan(points, Activity.HIKE, 2))
    private fun item(device: String = webDevice, routing: String? = theirs, data: String? = null) =
        SyncItem(id(1), today, device, title = "Waalweg", plan = plan(listOf(a, b)), routing = routing, routingData = data)

    // ---- the grey line ----

    @Test fun theLineShowsOnlyWhenTheFingerprintsDiffer() {
        assertTrue(SyncRouting.differs(mine, theirs))
        assertFalse(SyncRouting.differs(mine, mine))
        assertFalse("unknown on the other side: no line", SyncRouting.differs(mine, null))
        assertFalse("unknown here: no line", SyncRouting.differs(null, theirs))
        assertFalse("a newer fingerprint format: not comparable, no line", SyncRouting.differs(mine, "r2-bbbbbbbbbbbbbbbb"))
        assertFalse(SyncRouting.differs(mine, "garbage"))
        val days = mapOf<String, Long?>("E10_N45" to 20752L)
        fun line(i: SyncItem) = SyncRouting.opened(i, phoneDevice, mine, listOf(a, b), days, today).otherRouting
        assertTrue(line(item()))
        assertFalse(line(item(routing = mine)))
        assertFalse(line(item(routing = null)))
        assertFalse("saved by this phone with an older app version: not \"planned in the browser\"", line(item(device = phoneDevice)))
    }

    // ---- newer routing data: refreshIfNewer right away ----

    @Test fun openedChecksTilesOnlyForANewerDate() {
        val days = mapOf<String, Long?>("E10_N45" to 20745L, "E15_N45" to 20752L)
        fun refresh(data: String?, d: Map<String, Long?> = days) = SyncRouting.opened(item(data = data), phoneDevice, mine, listOf(a, b), d, today).refresh
        assertEquals(listOf("E10_N45"), refresh("E10_N45:20750"))
        assertEquals(emptyList<String>(), refresh("E10_N45:20745")) // the same
        assertEquals(emptyList<String>(), refresh("E10_N45:20700")) // the phone's is newer
        assertEquals(emptyList<String>(), refresh("E20_N45:20999")) // not on the phone
        // without field 8 (older browser): the plan's tiles older than 7 days – instead of 35
        assertEquals("7 days old: not older than 7", emptyList<String>(), refresh(null))
    }

    @Test fun withoutTheDateTheFallbackIsSevenDays() {
        fun refresh(days: Map<String, Long?>, pts: List<GeoPoint> = listOf(a, b)) =
            SyncRouting.opened(item(data = null), phoneDevice, mine, pts, days, today).refresh
        assertEquals(emptyList<String>(), refresh(mapOf("E10_N45" to 20745L))) // 7 days: still fine
        assertEquals(listOf("E10_N45"), refresh(mapOf("E10_N45" to 20744L))) // 8 days: check now
        assertEquals(listOf("E10_N45"), refresh(mapOf("E10_N45" to null))) // date unknown: check
        assertEquals(emptyList<String>(), refresh(emptyMap())) // not on the phone: loads when routed
        assertEquals(listOf("E10_N45", "E15_N45"), refresh(mapOf("E10_N45" to 20000L, "E15_N45" to 20000L), listOf(a, far)))
        assertEquals(setOf("E10_N45", "E15_N45"), SyncRouting.tilesOf(listOf(a, b, far)))
    }

    @Test fun throttleChecksATileOncePerGap() {
        val t = RefreshThrottle(1000)
        assertEquals(listOf("E10_N45"), t.take(listOf("E10_N45", "E10_N45"), 0))
        assertEquals(emptyList<String>(), t.take(listOf("E10_N45"), 999))
        assertEquals(listOf("E5_N45"), t.take(listOf("E10_N45", "E5_N45"), 999))
        assertEquals(listOf("E10_N45"), t.take(listOf("E10_N45"), 1000))
    }

    // ---- the pass: sending and receiving ----

    private val secret = ByteArray(32) { (it * 3 + 5).toByte() }
    private val keys = SyncKeys(secret)
    private val server = MemoryServer()
    private val store = FakeTourStore()
    private var clock = today
    private val rnd = Random(4)
    private val stamp = object : RoutingStamp {
        var days: Map<String, Long?> = mapOf("E10_N45" to 20745L)
        override fun fingerprint() = mine
        override fun days() = days
    }
    private val files by lazy { SyncFiles(tmp.newFolder("sync")).also { it.savePairing(secret, clock) } }
    private val phone by lazy {
        PlanSyncPass(files, store, { server.box(it) }, { n -> rnd.nextBytes(n) }, { clock }, "Pixel 8", { "Route" }, AesGcm,
            routing = stamp)
    }
    private fun phoneSync() = phone.run { false }!!.also { clock += 1000 }
    private val browser = SyncLedger.empty()
    private var n = 0
    private val browserRound = SyncRound(keys, server.box(keys), { ByteArray(12) { (n++).toByte() } })
    private fun browserSync() = browserRound.run(browser).also { clock += 1000 }

    @Test fun aPlanSavedOnThePhoneCarriesItsRouting() {
        store.savePlanned("Waalweg", listOf(a, b, far))
        phoneSync(); browserSync()
        val p = browser.plans().single()
        assertEquals(mine, p.routing)
        assertEquals("only the tiles on the phone, with their dates", "E10_N45:20745", p.routingData)
        // a pass without a stamp (older code paths) writes none – unknown, never a guess
        val plain = PlanSyncPass(SyncFiles(tmp.newFolder("other")).also { it.savePairing(ByteArray(32) { 7 }, clock) }, FakeTourStore().also {
            it.savePlanned("X", listOf(a, b)) }, { server.box(it) }, { k -> rnd.nextBytes(k) }, { clock }, "Pixel 8", { "Route" }, AesGcm)
        plain.run { false }
        val other = SyncLedger.empty()
        SyncRound(SyncKeys(ByteArray(32) { 7 }), server.box(SyncKeys(ByteArray(32) { 7 })), { ByteArray(12) { (n++).toByte() } }).run(other)
        assertNull(other.plans().single().routing); assertNull(other.plans().single().routingData)
    }

    @Test fun aPlanRoutedInTheBrowserWithNewerDataAsksForItAtOnce() {
        browser.save(id(1), plan(listOf(a, b)), "Newer", clock, webDevice, routing = theirs, routingData = "E10_N45:20752")
        browserSync()
        val r = phoneSync()
        assertEquals(1, r.added)
        assertEquals(listOf("E10_N45"), r.newerData)
        // the browser moves a point, its data is as old as the phone's: nothing to fetch
        browser.save(id(1), plan(listOf(b, a)), "Newer", clock, webDevice, routing = theirs, routingData = "E10_N45:20745")
        browserSync()
        val r2 = phoneSync()
        assertEquals(1, r2.updated)
        assertEquals(emptyList<String>(), r2.newerData)
        // older data in the browser, or no field 8: nothing either
        browser.save(id(2), plan(listOf(a, b)), "Older", clock, webDevice, routing = theirs, routingData = "E10_N45:20700")
        browser.save(id(3), plan(listOf(a, b)), "Old page", clock, webDevice)
        browserSync()
        assertEquals(emptyList<String>(), phoneSync().newerData)
        // the phone does not re-save a plan only because its own routing differs (samePlan): the browser's stays
        phoneSync(); browserSync()
        assertEquals(theirs, browser.get(id(1))!!.routing)
    }

    @Test fun openedReadsTheLinkedItem() {
        browser.save(id(1), plan(listOf(a, b)), "From the browser", clock, webDevice, routing = theirs, routingData = "E10_N45:20752")
        browserSync(); phoneSync()
        val t = store.byName("From the browser")
        val item = files.ledger().get(files.links().itemOf(t.uuid)!!)!!
        val o = SyncRouting.opened(item, files.device { k -> rnd.nextBytes(k) }, mine, listOf(a, b), stamp.days(), clock)
        assertTrue(o.otherRouting)
        assertEquals(listOf("E10_N45"), o.refresh)
    }

    @Test fun fileStampReadsTheRd5Dates() {
        val dir = tmp.newFolder("segments")
        Rd5PartsTest.fakeRemoteFile(dir, Rd5PartsTest.sizes(), created = today)
        java.io.File(dir, "E5_N45.rd5").writeBytes(ByteArray(10)) // too short: no date, but on the phone
        val s = FileRoutingStamp({ ByteArray(0) }, { dir })
        assertEquals(mapOf("E10_N45" to 20752L, "E5_N45" to null), s.days())
        assertTrue(com.onpath.app.routing.RoutingFingerprint.isComparable(s.fingerprint()))
        assertEquals("E10_N45:20752", SyncRouting.dataFor(listOf(a, b), s.days()))
        assertNull(SyncRouting.dataFor(listOf(GeoPoint(46.5, 7.0)), s.days()))
        assertEquals(20752L, SyncRoutingData.day(today))
    }
}

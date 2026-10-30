package com.onpath.app.plansync

import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import com.onpath.app.sync.AesGcm
import com.onpath.app.sync.MemoryServer
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncRound
import com.onpath.app.sync.SyncTrack
import com.onpath.app.sync.TrackCode
import com.onpath.app.sync.TrackPt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * CORE FEATURE (A-007, web planner phase 2, docs/design/web-sync.md §11): saved routes and imported GPX tracks sync
 * between the phone and the paired browser – save, rename, delete both ways; a change from the browser updates the
 * route and routes it again; a delete from the browser moves it to the recycle bin (never gone for good); a conflict
 * keeps both versions; never during a recording; unpair ends the mailbox and keeps every route. The browser here is
 * the shared ledger + round (the web planner runs the same Kotlin); the mailbox has sync.js's rules (MemoryServer;
 * the real sync.js: SyncMailboxE2ETest).
 */
class CorePlanSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val secret = ByteArray(32) { (it * 3 + 5).toByte() }
    private val keys = SyncKeys(secret)
    private val server = MemoryServer()
    private val store = FakeTourStore()
    private var clock = 1_793_000_000_000L
    private val rnd = Random(42)
    private val files by lazy { SyncFiles(tmp.newFolder("sync")).also { it.savePairing(secret, clock) } }
    private val phone by lazy {
        PlanSyncPass(files, store, { server.box(it) }, { n -> rnd.nextBytes(n) }, { clock }, "Pixel 8",
            { if (it == SyncItem.TRACK) "Track" else "Route" }, AesGcm)
    }
    private var recording = false
    private fun phoneSync() = phone.run { recording }!!.also { clock += 1000 }

    // the browser: the web planner's side (same shared code)
    private val web = SyncFormat.newDevice(ByteArray(8) { 9 })
    private val browser = SyncLedger.empty()
    private var n = 0
    private val browserRound = SyncRound(keys, server.box(keys), { ByteArray(12) { (n++).toByte() } })
    private fun browserSync() = browserRound.run(browser).also { clock += 1000 }
    private fun browserPlan(title: String) = browser.plans().single { it.title == title }

    private val a = GeoPoint(46.67171, 11.15003)
    private val b = GeoPoint(46.68512, 11.13470)
    private val c = GeoPoint(46.69020, 11.16110)

    @Test fun aSavedRouteReachesTheBrowserWithPointsNamesAndPins() {
        store.savePlanned("Waalweg", listOf(a, b, c), Activity.HIKE, 3, names = mapOf(1 to "Leiter Alm"), pinned = setOf(1))
        val r = phoneSync()
        assertTrue(r.outcome is SyncRound.Outcome.Done)
        browserSync()
        val p = PlanCode.decode(browserPlan("Waalweg").plan!!)!!
        assertEquals(listOf(a, b, c), p.points)
        assertEquals(Activity.HIKE, p.activity); assertEquals(3, p.sac)
        assertEquals(mapOf(1 to "Leiter Alm"), p.names); assertEquals(setOf(1), p.pinned)
        assertEquals("Pixel 8", browser.devices(web).single().name)
        assertFalse("the server sees no title", server.stored(keys).contains("Waalweg"))
        // nothing echoes: more passes on both sides upload nothing new
        phoneSync(); browserSync()
        val again = phoneSync().outcome as SyncRound.Outcome.Done
        assertEquals(0, again.pushed)
    }

    @Test fun renameAndDeleteOnThePhoneReachTheBrowser() {
        val t = store.savePlanned("Waalweg", listOf(a, b))
        phoneSync(); browserSync()
        store.update(t) { it.copy(name = "Algunder Waalweg") }
        phoneSync(); browserSync()
        assertEquals(listOf("Algunder Waalweg"), browser.plans().map { it.title })
        // to the recycle bin → deleted in the browser; restored → back
        store.update(t) { it.copy(deletedMs = clock) }
        phoneSync(); browserSync()
        assertTrue(browser.plans().isEmpty())
        store.update(t) { it.copy(deletedMs = null) }
        phoneSync(); browserSync()
        assertEquals(listOf("Algunder Waalweg"), browser.plans().map { it.title })
        // recycle bin emptied (deleted for good): stays deleted, the link goes
        store.update(t) { it.copy(deletedMs = clock) }; phoneSync()
        store.deleteForGood(t); phoneSync(); browserSync()
        assertTrue(browser.plans().isEmpty())
        assertTrue(files.links().all.isEmpty())
    }

    @Test fun aRouteFromTheBrowserIsSavedAndRouted() {
        browser.save(id(1), plan(listOf(a, b, c), Activity.MTB, names = mapOf(0 to "Algund")), "Meran → Vellau", clock, web)
        browserSync()
        val r = phoneSync()
        val t = store.byName("Meran → Vellau")
        assertEquals(TourKind.PLANNED, t.kind)
        assertEquals(listOf(a, b, c), t.waypoints.map { GeoPoint(it.lat, it.lon) })
        assertEquals("Algund", t.waypoints[0].name)
        assertEquals(Activity.MTB.name, t.activity)
        assertEquals(listOf(t.id), r.toRoute)
        assertTrue(r.toursChanged)
        // the browser moves a point → the route changes and is routed again; a rename only renames
        browser.save(id(1), plan(listOf(a, c), Activity.MTB), "Meran → Vellau", clock, web); browserSync()
        val r2 = phoneSync()
        assertEquals(listOf(a, c), store.byName("Meran → Vellau").waypoints.map { GeoPoint(it.lat, it.lon) })
        assertEquals(listOf(t.id), r2.toRoute)
        browser.rename(id(1), "Vellau", clock, web); browserSync()
        val r3 = phoneSync()
        assertEquals("Vellau", store.list.single { it.id == t.id }.name)
        assertTrue(r3.toRoute.isEmpty())
        // no echo back to the browser
        val before = browser.since
        browserSync()
        assertEquals(1, server.itemCount(keys) - browser.plans().size) // only the phone's hello besides the plan
        assertTrue(browser.since >= before)
    }

    @Test fun aDeleteFromTheBrowserMovesTheRouteToTheRecycleBin() {
        val t = store.savePlanned("Ifinger", listOf(a, b))
        phoneSync(); browserSync()
        val item = browser.plans().single().id
        browser.delete(item, clock, web); browserSync()
        val r = phoneSync()
        assertEquals(1, r.binned)
        val inBin = store.list.single { it.id == t.id }
        assertNotNull("in the recycle bin, never deleted for good", inBin.deletedMs)
        // the browser brings it back (a newer save of the same item) → out of the recycle bin, with the new points
        browser.save(item, plan(listOf(a, c)), "Ifinger", clock, web); browserSync()
        assertEquals(listOf(t.id), phoneSync().toRoute)
        val back = store.list.single()
        assertNull(back.deletedMs)
        assertEquals(listOf(a, c), back.waypoints.map { GeoPoint(it.lat, it.lon) })
    }

    @Test fun importedTracksSyncBothWays() {
        // coordinates on the 1e-5° grid of the track code (the phone keeps its own line anyway)
        val segs = listOf(List(300) { i -> TrackPoint((4_660_000 + i * 20) / 1e5, (1_110_000 + i * 10) / 1e5, 600.0 + i) },
            listOf(TrackPoint(46.7, 11.2, 900.0)))
        val t = store.saveImported("Ultental GPX", segs, listOf(GpxWaypoint(46.6, 11.1, "Start")), Activity.MTB)
        phoneSync(); browserSync()
        val item = browser.tracks().single()
        assertEquals("Ultental GPX", item.title)
        val tr = TrackCode.of(item.track)!!
        assertEquals(Activity.MTB, tr.activity)
        assertEquals(segs.map { s -> s.map { TrackPt(it.lat, it.lon, it.ele) } }, tr.segments)
        assertEquals("Start", tr.marks.single().name)
        assertTrue(browser.plans().isEmpty())
        // renamed in the browser: renamed on the phone, the line stays the phone's own
        browser.rename(item.id, "Ultental", clock, web); browserSync()
        val linesBefore = store.lines[t.id]
        phoneSync()
        assertEquals("Ultental", store.list.single { it.id == t.id }.name)
        assertTrue(linesBefore === store.lines[t.id])
        // a track from the browser → an imported track on the phone
        val text = TrackCode.fit(SyncTrack(listOf(listOf(TrackPt(46.5, 11.0, 300.0), TrackPt(46.51, 11.01, 320.0))), Activity.HIKE, 4))!!
        browser.saveTrack(id(3), text, "Vigiljoch", clock, web); browserSync()
        phoneSync()
        val got = store.byName("Vigiljoch")
        assertEquals(TourKind.IMPORTED, got.kind)
        assertEquals(4, got.sac)
        assertEquals(listOf(TrackPoint(46.5, 11.0, 300.0), TrackPoint(46.51, 11.01, 320.0)), store.lines[got.id]!!.single())
        // deleted in the browser → recycle bin
        browser.delete(id(3), clock, web); browserSync(); phoneSync()
        assertNotNull(store.list.single { it.id == got.id }.deletedMs)
    }

    @Test fun recordedToursAreNotSynced() {
        store.saveRecorded("Tour 29 Oct")
        phoneSync(); browserSync()
        assertTrue(browser.plans().isEmpty() && browser.tracks().isEmpty())
    }

    @Test fun aConflictKeepsBothVersions() {
        val t = store.savePlanned("Waalweg", listOf(a, b))
        phoneSync(); browserSync()
        val id = browser.plans().single().id
        // both rename while apart; the browser is later (newer wins), the phone's name stays as a copy
        store.update(t) { it.copy(name = "Phone name") }
        clock += 10
        browser.rename(id, "Browser name", clock + 5_000, web)
        browserSync()
        val r = phoneSync()
        assertTrue(r.toursChanged)
        browserSync()
        val want = setOf("Browser name", "Phone name (copy)")
        assertEquals(want, store.live().map { it.name }.toSet())
        assertEquals(want, browser.plans().map { it.title }.toSet())
        // a second pass changes nothing
        phoneSync(); browserSync()
        assertEquals(2, store.live().size)
    }

    @Test fun nothingComesInDuringARecording() {
        browser.save(id(1), plan(listOf(a, b)), "After the tour", clock, web); browserSync()
        recording = true
        val r = phoneSync()
        assertTrue(r.skippedForRecording)
        assertTrue(store.list.isEmpty())
        recording = false
        phoneSync()
        assertEquals("After the tour", store.list.single().name)
    }

    @Test fun aFailedSaveIsTriedAgain() {
        browser.save(id(1), plan(listOf(a, b)), "Try again", clock, web); browserSync()
        store.failWrites = 1
        val r = phoneSync()
        assertEquals(1, r.failed)
        assertTrue(store.list.isEmpty())
        assertTrue(files.links().pending.isNotEmpty())
        phoneSync()
        assertEquals("Try again", store.list.single().name)
        assertTrue(files.links().pending.isEmpty())
    }

    @Test fun unpairEndsTheMailboxAndKeepsEveryRoute() {
        store.savePlanned("Waalweg", listOf(a, b))
        phoneSync(); browserSync()
        assertEquals(200, phone.unpair())
        assertTrue(server.ended(keys))
        assertEquals(0, server.itemCount(keys))
        assertNull(files.pairing())
        assertEquals(1, store.live().size)
        assertNull(phone.run { false }) // not paired: no pass
        // the browser learns it on its next round (its plans stay there too)
        assertEquals(SyncRound.Outcome.Ended, browserSync())
        assertEquals(1, browser.plans().size)
        // a new pairing: every route goes to the new mailbox
        val second = ByteArray(32) { 77 }
        files.savePairing(second, clock)
        phoneSync()
        assertEquals(2, server.itemCount(SyncKeys(second))) // the route + the hello
    }

    @Test fun endedInTheBrowserKeepsTheRoutes() {
        store.savePlanned("Waalweg", listOf(a, b))
        phoneSync()
        server.box(keys).end() // the browser unpaired
        val r = phoneSync()
        assertEquals(SyncRound.Outcome.Ended, r.outcome)
        phone.forgetHere()
        assertNull(files.pairing())
        assertEquals(1, store.live().size)
        // the ledger keeps the route, to upload on a new pairing
        assertEquals(1, files.ledger().outbox().size)
    }

    @Test fun offlineKeepsEverythingForLater() {
        store.savePlanned("Offline", listOf(a, b))
        server.offline = true
        assertEquals(SyncRound.Outcome.Offline, phoneSync().outcome)
        server.offline = false
        phoneSync(); browserSync()
        assertEquals(listOf("Offline"), browser.plans().map { it.title })
    }

    private fun id(k: Int) = SyncFormat.newId(ByteArray(16) { k.toByte() })
    private fun plan(points: List<GeoPoint>, act: Activity = Activity.HIKE, names: Map<Int, String> = emptyMap()) =
        PlanCode.encode(SharedPlan(points, act, 2, names = names))
}

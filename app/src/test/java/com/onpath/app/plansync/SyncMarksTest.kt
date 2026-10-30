package com.onpath.app.plansync

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * C16 (owner 2026-10-30): a route or imported track that arrived from the browser, or that the browser changed, is
 * marked in My routes → Planned (bold + "New from the browser" / "Changed in the browser") until the user opens it.
 * A change on the phone never marks; opening, the recycle bin and unpair clear it; the marks survive a restart.
 */
class SyncMarksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val secret = ByteArray(32) { (it * 3 + 5).toByte() }
    private val keys = SyncKeys(secret)
    private val server = MemoryServer()
    private val store = FakeTourStore()
    private var clock = 1_793_000_000_000L
    private val rnd = Random(7)
    private val dir by lazy { tmp.newFolder("sync") }
    private val files by lazy { SyncFiles(dir).also { it.savePairing(secret, clock) } }
    private val phone by lazy {
        PlanSyncPass(files, store, { server.box(it) }, { n -> rnd.nextBytes(n) }, { clock }, "Pixel 8",
            { if (it == SyncItem.TRACK) "Track" else "Route" }, AesGcm)
    }
    private fun phoneSync() = phone.run { false }!!.also { clock += 1000 }

    private val web = SyncFormat.newDevice(ByteArray(8) { 9 })
    private val browser = SyncLedger.empty()
    private var n = 0
    private val browserRound = SyncRound(keys, server.box(keys), { ByteArray(12) { (n++).toByte() } })
    private fun browserSync() = browserRound.run(browser).also { clock += 1000 }

    private val a = GeoPoint(46.67171, 11.15003)
    private val b = GeoPoint(46.68512, 11.13470)
    private val c = GeoPoint(46.69020, 11.16110)

    private fun marks() = files.marks.all()

    @Test fun aRouteFromTheBrowserIsMarkedNewUntilOpened() {
        browser.save(id(1), plan(listOf(a, b, c)), "Meran → Vellau", clock, web); browserSync()
        phoneSync()
        val t = store.byName("Meran → Vellau")
        assertEquals(mapOf(t.uuid to SyncMark.NEW), marks())
        // more passes (seeing the list, polls) keep it
        phoneSync(); phoneSync()
        assertEquals(SyncMark.NEW, files.marks.of(t.uuid))
        // changed in the browser before it was opened: still "new"
        browser.save(id(1), plan(listOf(a, c)), "Meran → Vellau", clock, web); browserSync(); phoneSync()
        assertEquals(SyncMark.NEW, files.marks.of(t.uuid))
        // opened (My routes → tap: AppViewModel.openTour) → gone
        files.marks.remove(t.uuid)
        assertTrue(marks().isEmpty())
        assertTrue(files.marks.state.value.isEmpty())
    }

    @Test fun aTrackFromTheBrowserIsMarkedNew() {
        val text = TrackCode.fit(SyncTrack(listOf(listOf(TrackPt(46.5, 11.0, 300.0), TrackPt(46.51, 11.01, 320.0))), Activity.HIKE, 4))!!
        browser.saveTrack(id(3), text, "Vigiljoch", clock, web); browserSync()
        phoneSync()
        assertEquals(mapOf(store.byName("Vigiljoch").uuid to SyncMark.NEW), marks())
    }

    @Test fun aChangeOrRenameInTheBrowserMarksChanged() {
        val t = store.savePlanned("Waalweg", listOf(a, b))
        phoneSync(); browserSync()
        assertTrue("the phone's own route is not marked", marks().isEmpty())
        val item = browser.plans().single().id
        browser.save(item, plan(listOf(a, b, c)), "Waalweg", clock, web); browserSync()
        phoneSync()
        assertEquals(mapOf(t.uuid to SyncMark.CHANGED), marks())
        files.marks.remove(t.uuid) // opened
        browser.rename(item, "Algunder Waalweg", clock, web); browserSync()
        phoneSync()
        assertEquals(mapOf(t.uuid to SyncMark.CHANGED), marks())
    }

    @Test fun ownSavesRenamesAndDeletesOnThePhoneNeverMark() {
        val t = store.savePlanned("Waalweg", listOf(a, b))
        phoneSync(); browserSync()
        store.update(t) { it.copy(name = "Algunder Waalweg") }
        phoneSync(); browserSync(); phoneSync()
        store.update(t) { it.copy(waypoints = it.waypoints + com.onpath.app.gpx.GpxWaypoint(c.lat, c.lon, null)) }
        phoneSync(); browserSync(); phoneSync()
        store.update(t) { it.copy(deletedMs = clock) }
        phoneSync(); browserSync(); phoneSync()
        store.update(t) { it.copy(deletedMs = null) }
        phoneSync(); browserSync(); phoneSync()
        assertTrue(marks().isEmpty())
        assertTrue(File(dir, "marks.txt").let { !it.exists() || SyncMarks.read(it.readText()).isEmpty() })
    }

    @Test fun recycleBinClearsTheMark() {
        browser.save(id(1), plan(listOf(a, b)), "Ifinger", clock, web); browserSync(); phoneSync()
        val t = store.byName("Ifinger")
        // deleted in the browser → recycle bin, no mark
        browser.delete(id(1), clock, web); browserSync(); phoneSync()
        assertTrue(marks().isEmpty())
        // a new route, then deleted on the phone (the next pass sees it in the bin)
        browser.save(id(2), plan(listOf(b, c)), "Vigiljoch", clock, web); browserSync(); phoneSync()
        val v = store.byName("Vigiljoch")
        assertEquals(setOf(v.uuid), marks().keys)
        store.update(v) { it.copy(deletedMs = clock) }
        phoneSync()
        assertTrue(marks().isEmpty())
        assertTrue(t.uuid.isNotEmpty())
    }

    @Test fun unpairClearsTheMarks() {
        browser.save(id(1), plan(listOf(a, b)), "Ifinger", clock, web); browserSync(); phoneSync()
        assertEquals(1, marks().size)
        assertEquals(200, phone.unpair())
        assertTrue(marks().isEmpty())
        assertTrue(SyncFiles(dir).marks.all().isEmpty()) // on disk too
        assertNull(files.pairing())
    }

    @Test fun marksSurviveARestart() {
        browser.save(id(1), plan(listOf(a, b)), "Ifinger", clock, web)
        browser.save(id(2), plan(listOf(b, c)), "Vigiljoch", clock, web); browserSync(); phoneSync()
        val ifinger = store.byName("Ifinger").uuid
        val t = store.savePlanned("Waalweg", listOf(a, c))
        phoneSync(); browserSync()
        browser.save(browser.plans().single { it.title == "Waalweg" }.id, plan(listOf(a, b, c)), "Waalweg", clock, web); browserSync(); phoneSync()
        files.marks.remove(store.byName("Vigiljoch").uuid) // opened
        val again = SyncFiles(dir).marks // the app restarted: a new instance on the same folder
        assertTrue("nothing read before the start reads it", again.state.value.isEmpty())
        assertEquals(mapOf(ifinger to SyncMark.NEW, t.uuid to SyncMark.CHANGED), again.all())
        assertEquals(again.all(), again.state.value)
    }

    @Test fun fileFormatIsTolerant() {
        val m = mapOf("u-1" to SyncMark.NEW, "u-2" to SyncMark.CHANGED)
        assertEquals(m, SyncMarks.read(SyncMarks.write(m)))
        assertTrue(SyncMarks.read(null).isEmpty())
        assertTrue(SyncMarks.read("garbage\nu-1 n\n").isEmpty()) // no header: none
        assertEquals(mapOf("u-1" to SyncMark.NEW), SyncMarks.read("${SyncMarks.HEADER}\nu-1 n\nu-2 x\nbroken\n\n"))
        // a write that fails (storage full): reported, the mark kept for this run
        var fail = true
        val s = SyncMarks({ null }, { if (fail) error("disk full") })
        s.arrived("u-1")
        assertEquals(mapOf("u-1" to SyncMark.NEW), s.all())
        fail = false
        s.changed("") // no uuid: ignored
        assertEquals(1, s.all().size)
    }

    private fun id(k: Int) = SyncFormat.newId(ByteArray(16) { k.toByte() })
    private fun plan(points: List<GeoPoint>, act: Activity = Activity.HIKE) = PlanCode.encode(SharedPlan(points, act, 2))
}

package com.onpath.app.plansync

import com.onpath.app.search.Place
import com.onpath.app.search.SavedPlaces
import com.onpath.app.search.SearchStore
import com.onpath.app.sync.AesGcm
import com.onpath.app.sync.MemoryServer
import com.onpath.app.sync.PlaceSync
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncPlaces
import com.onpath.app.sync.SyncRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * CORE FEATURE C16 + F-PLAN-14 (owner 2026-11-15 „Ja, mit Abgleich“, docs/design/web-sync.md §5.4): the kept places
 * sync between the phone and the paired browser: keep / unkeep both ways. „Zuhause“ is the planner's only (the app has
 * none since 2026-11-28): its home item is read and left alone by the phone – never sent, cleared or taken in. A pairing with places on both sides merges them (the same place once),
 * a failed or skipped import is tried again and never turns into a delete, an older reader loses nothing. The browser
 * here is the shared ledger + round + [PlaceSync] (the planner runs the same Kotlin; its line codec is WebKept, the
 * same SearchStore line).
 */
class CorePlaceSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val secret = ByteArray(32) { (it * 5 + 3).toByte() }
    private val keys = SyncKeys(secret)
    private val server = MemoryServer()
    private var clock = 1_793_000_000_000L
    private val rnd = Random(7)
    private val places = MemoryPlaces()
    private val files by lazy { SyncFiles(tmp.newFolder("sync")).also { it.savePairing(secret, clock) } }
    private val phone by lazy {
        PlanSyncPass(files, FakeTourStore(), { server.box(it) }, { n -> rnd.nextBytes(n) }, { clock }, "Pixel 8",
            { "Route" }, AesGcm, places = places)
    }
    private var recording = false
    private fun phoneSync() = phone.run { recording }!!.also { clock += 1000 }

    // the browser: the planner's side (the same shared code)
    private val web = SyncFormat.newDevice(ByteArray(8) { 9 })
    private var browser = SyncLedger.empty()
    private var webPlaces = SavedPlaces()
    private val webSync = PlaceSync(SearchStore::encode, SearchStore::decode)
    private var n = 0
    private fun browserSync(): SyncRound.Outcome {
        val seen = webPlaces
        webSync.export(browser, seen, clock, web)
        val before = browser.entries.mapValues { it.value.item }
        val o = SyncRound(keys, server.box(keys), { ByteArray(12) { (n++).toByte() } }).run(browser)
        webPlaces = webSync.import(browser, PlaceSync.changed(before, browser), webPlaces, seen)
        clock += 1000
        return o
    }

    private val hut = Place("n:4711", "Leiter Alm", "alpine_hut", 46.689, 11.14, ele = 1522)
    private val hutOnline = Place("photon:N4711", "Leiter Alm", "alpine_hut", 46.68915, 11.14005, address = "Algund")
    private val peak = Place("n:12", "Mutspitze", "peak", 46.7203, 11.1251, ele = 2295)
    private val home = Place("addr:1", "Hauptstraße 3", "address", 46.6712, 11.1500, village = "Algund")
    private val work = Place("addr:2", "Bahnhofstraße 1", "address", 46.6650, 11.1590)

    private fun ids(ps: List<Place>) = ps.map { it.id }.toSet()

    @Test fun keptPlacesTravelBothWays() {
        places.s = SavedPlaces(null, listOf(hut))
        assertFalse(phoneSync().placesChanged)
        browserSync()
        assertNull(webPlaces.home)
        assertEquals(listOf(hut), webPlaces.favourites)
        assertFalse("the server sees no place name", server.stored(keys).contains("Leiter"))
        // the browser keeps the peak → it arrives on the phone, first in the list
        webPlaces = webPlaces.toggleFavourite(peak)
        browserSync()
        val r = phoneSync()
        assertTrue(r.placesChanged)
        assertEquals(listOf(peak, hut), places.s.favourites)
        // nothing echoes: more passes send nothing and change nothing
        browserSync()
        val again = phoneSync()
        assertEquals(0, (again.outcome as SyncRound.Outcome.Done).pushed)
        assertFalse(again.placesChanged)
    }

    @Test fun unkeepTravelsBothWays() {
        places.s = SavedPlaces(null, listOf(hut, peak))
        phoneSync(); browserSync()
        places.s = places.s.toggleFavourite(hut) // „★ Gemerkt“ off on the phone
        phoneSync(); browserSync()
        assertEquals(listOf(peak), webPlaces.favourites)
        webPlaces = webPlaces.toggleFavourite(peak) // off in the browser
        browserSync(); phoneSync()
        assertTrue(places.s.favourites.isEmpty())
        assertTrue(files.links().pending.isEmpty())
    }

    /**
     * Captain 2026-11-28 (Zuhause out of the app, the format unchanged): the planner's home item is read and left alone –
     * set, replaced and cleared in the browser, it never reaches the phone, never crashes it, never costs another place,
     * and the phone never sends or clears it.
     */
    @Test fun theBrowsersHomeIsReadAndLeftAlone() {
        places.s = SavedPlaces(null, listOf(hut))
        phoneSync(); browserSync()
        webPlaces = webPlaces.withHome(home) // „Als Zuhause festlegen“ in the browser
        browserSync()
        val r = phoneSync()
        assertFalse(r.placesChanged)
        assertNull(places.s.home)
        assertEquals(listOf(hut), places.s.favourites)
        assertEquals(0, r.unreadable)
        webPlaces = webPlaces.withHome(work) // replaced in the browser
        browserSync(); phoneSync(); browserSync()
        assertEquals(work, webPlaces.home) // the phone never cleared it
        assertNull(places.s.home)
        assertEquals(1, browser.entries.values.count { it.item.id == SyncPlaces.HOME_ID })
        // even a phone that still holds a home of its own (an older version's store) sends nothing for it
        places.s = places.s.withHome(peak)
        phoneSync(); browserSync()
        assertEquals(work, webPlaces.home)
        webPlaces = webPlaces.withHome(null) // cleared in the browser: the phone's own stays as it is
        browserSync(); phoneSync()
        assertEquals(peak, places.s.home)
        assertEquals(listOf(hut), places.s.favourites)
        assertTrue(files.links().pending.isEmpty())
    }

    @Test fun pairingWithPlacesOnBothSidesMergesThemWithoutCopies() {
        // the browser kept places before the pairing (browser-local), the phone too – the same home, the hut twice
        // (the phone's from the map, the browser's from the online search: another line, the same place)
        places.s = SavedPlaces(null, listOf(hut, peak))
        webPlaces = SavedPlaces(home, listOf(hutOnline, work))
        phoneSync(); browserSync(); phoneSync(); browserSync()
        assertNull(places.s.home)
        assertEquals(setOf(hut.id, peak.id, work.id), ids(places.s.favourites))
        assertEquals(3, places.s.favourites.size)
        assertEquals(home, webPlaces.home)
        assertEquals(setOf(hutOnline.id, peak.id, work.id), ids(webPlaces.favourites))
        assertEquals(3, webPlaces.favourites.size)
        // in step: nothing moves any more
        val r = phoneSync(); browserSync()
        assertEquals(0, (r.outcome as SyncRound.Outcome.Done).pushed)
        assertFalse(r.placesChanged)
        // „★ Gemerkt“ off for the hut on the phone takes both copies away everywhere
        places.s = places.s.toggleFavourite(hut)
        phoneSync(); browserSync(); phoneSync()
        assertEquals(setOf(peak.id, work.id), ids(webPlaces.favourites))
        assertEquals(setOf(peak.id, work.id), ids(places.s.favourites))
        assertTrue(browser.places().none { SavedPlaces.same(SearchStore.decode(it.place!!)!!, hut) })
    }

    @Test fun aFailedImportIsTriedAgainAndNeverBecomesADelete() {
        places.s = SavedPlaces(null, listOf(hut))
        phoneSync(); browserSync()
        webPlaces = webPlaces.toggleFavourite(peak).withHome(home)
        browserSync()
        places.failUpdates = 1
        val r = phoneSync()
        assertFalse(r.placesChanged)
        assertEquals(listOf(hut), places.s.favourites)
        assertTrue(files.links().pending.isNotEmpty())
        // the next pass brings them in first – the export never takes the missing peak for a delete (the home stays the browser's)
        val again = phoneSync()
        assertTrue(again.placesChanged)
        assertEquals(listOf(peak, hut), places.s.favourites)
        assertNull(places.s.home)
        browserSync()
        assertEquals(setOf(hut.id, peak.id), ids(webPlaces.favourites))
        assertEquals(home, webPlaces.home)
        assertTrue(files.links().pending.isEmpty())
    }

    @Test fun anUnreadablePlacesFileDeletesNothing() {
        webPlaces = SavedPlaces(home, listOf(peak))
        browserSync()
        places.s = SavedPlaces(null, listOf(hut))
        phoneSync(); browserSync()
        places.failReads = 99 // the prefs file cannot be read: never „nothing kept“
        phoneSync(); browserSync()
        assertEquals(home, webPlaces.home)
        assertEquals(setOf(hut.id, peak.id), ids(webPlaces.favourites))
    }

    @Test fun placesFromTheBrowserWaitForTheTourEnd() {
        phoneSync()
        webPlaces = webPlaces.toggleFavourite(peak)
        browserSync()
        recording = true
        val r = phoneSync()
        assertTrue(r.skippedForRecording)
        assertTrue(places.s.favourites.isEmpty())
        recording = false
        assertTrue(phoneSync().placesChanged)
        assertEquals(listOf(peak), places.s.favourites)
    }

    @Test fun anOlderReaderLosesNothing() {
        // an older page (or app) in the same mailbox: it skips field 11 – it stores place items without their line
        places.s = SavedPlaces(null, listOf(hut))
        webPlaces = webPlaces.withHome(home)
        phoneSync(); browserSync()
        // some history (the peak kept and dropped in the browser), read by the phone
        webPlaces = webPlaces.toggleFavourite(peak); browserSync(); phoneSync()
        webPlaces = webPlaces.toggleFavourite(peak); browserSync(); phoneSync()
        assertEquals(listOf(hut), places.s.favourites)
        var old = SyncLedger.empty()
        val oldRound = SyncRound(keys, server.box(keys), { ByteArray(12) { (n++).toByte() } })
        oldRound.run(old); old = stripped(old)
        assertTrue(old.places().isEmpty() && old.home() == null) // it shows none
        assertEquals(0, old.plans().size + old.tracks().size)    // and makes no tour of them
        // the mailbox lost its history (idle purge / reset): the older reader sends what it kept – without lines
        server.purge(keys)
        oldRound.run(old); old = stripped(old)
        val r = phoneSync()
        assertNull(places.s.home)
        assertEquals(listOf(hut), places.s.favourites)
        assertFalse(r.placesChanged)
        assertEquals(0, r.unreadable) // place items are no "newer app" items
        // the phone sent its line again: the browser keeps its favourite (its home is the browser's own: its next export sends it)
        browserSync()
        assertEquals(listOf(hut), webPlaces.favourites)
        val fresh = SyncLedger.empty()
        SyncRound(keys, server.box(keys), { ByteArray(12) { (n++).toByte() } }).run(fresh)
        assertEquals(1, fresh.places().size)
    }

    @Test fun aPhoneWithoutPlaceSyncLeavesThePlacesAlone() {
        webPlaces = SavedPlaces(home, listOf(peak))
        browserSync()
        val noPlaces = PlanSyncPass(files, FakeTourStore(), { server.box(it) }, { n -> rnd.nextBytes(n) }, { clock }, "Pixel 8",
            { "Route" }, AesGcm)
        noPlaces.run { false }
        assertEquals(2, files.ledger().entries.values.count { it.item.kind == SyncItem.PLACE && SyncPlaces.live(it.item) })
        browserSync()
        assertEquals(home, webPlaces.home)
        assertEquals(listOf(peak), webPlaces.favourites)
    }

    /** [l] as an older reader keeps it: place items without field 11 (it skips the tag), written back that way. */
    private fun stripped(l: SyncLedger): SyncLedger {
        val lines = l.write().split('\n').filter { it.isNotBlank() }
        return SyncLedger.read((listOf(lines.first()) + lines.drop(1).map { line ->
            val p = line.split(' ')
            val item = SyncFormat.decode(SyncFormat.fromText(p[2])!!)!!.copy(place = null)
            "${p[0]} ${p[1]} ${SyncFormat.toText(SyncFormat.encode(item))}"
        }).joinToString("\n"))
    }

    /** The phone's kept places in memory, like SearchStore's prefs. */
    class MemoryPlaces : PlaceStore {
        var s = SavedPlaces()
        var failUpdates = 0
        var failReads = 0
        override fun read(): SavedPlaces { if (failReads > 0) { failReads--; error("places file unreadable") }; return s }
        override fun update(change: (SavedPlaces) -> SavedPlaces): Boolean {
            if (failUpdates > 0) { failUpdates--; error("places not written") }
            val next = change(read())
            return (next != s).also { s = next }
        }
    }
}

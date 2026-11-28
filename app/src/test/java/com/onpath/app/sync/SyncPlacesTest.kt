package com.onpath.app.sync

import com.onpath.app.search.Place
import com.onpath.app.search.SavedPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Kept places in the sync (owner 2026-11-15 „Ja, mit Abgleich“, F-PLAN-14; docs/design/web-sync.md §5.4): item kind 5,
 * field 11 = the SearchStore line, Zuhause = the one item [SyncPlaces.HOME_ID], a favourite's id from its line; the merge
 * without clocks; [PlaceSync]'s export / import over [SavedPlaces]. Shared code – the web build runs this test too.
 */
class SyncPlacesTest {
    private val phone = SyncFormat.newDevice(ByteArray(8) { 1 })
    private val web = SyncFormat.newDevice(ByteArray(8) { 2 })
    private val sync = PlaceSync(Lines::encode, Lines::decode)

    private val hut = Place("n:4711", "Leiter Alm", "alpine_hut", 46.689, 11.14, ele = 1522)
    /** The same hut from the online search: another id and line, the same name 20 m away – the same place. */
    private val hutOnline = Place("photon:N4711", "Leiter Alm", "alpine_hut", 46.68915, 11.14005, address = "Algund")
    private val peak = Place("n:12", "Mutspitze", "peak", 46.7203, 11.1251, ele = 2295)
    private val home = Place("addr:1", "Hauptstraße 3", "address", 46.6712, 11.1500, village = "Algund")
    private val work = Place("addr:2", "Bahnhofstraße 1", "address", 46.6650, 11.1590)

    private val hutLine = "n:4711\tLeiter Alm\talpine_hut\t\t46.689000\t11.140000\t1522\t\tPOI\t\t\t"

    @Test fun theFormatIsFrozen() {
        assertEquals(5, SyncItem.PLACE)
        assertEquals(hutLine, Lines.encode(hut))
        // first 16 bytes of SHA-256("OnPath home") / ("OnPath place " + line), checked with Python's hashlib
        assertEquals("pgMGQeTKkugzkpC39hnwlw", SyncPlaces.HOME_ID)
        assertEquals("COgvE3Dj8pbgW9pnl8DPxw", SyncPlaces.itemId(hutLine))
        val item = SyncItem(SyncPlaces.itemId(hutLine), 1000, phone, kind = SyncItem.PLACE, place = hutLine)
        val bytes = SyncFormat.encode(item)
        assertEquals("010508e82f1370e3f296e05bda6797c0cfc70101010101010101e807000b3e6e3a34373131094c656974657220416c6d09616c" +
            "70696e655f687574090934362e3638393030300931312e31343030303009313532320909504f49090909", hex(bytes))
        assertEquals(item, SyncFormat.decode(bytes))
    }

    @Test fun theLineTravelsAsItIsAndOnlyOnPlaces() {
        // a newer app's extra field stays in the line
        val newer = hutLine + "\tx=1"
        assertEquals(newer, SyncFormat.decode(SyncFormat.encode(SyncItem(SyncPlaces.itemId(newer), 1, phone, kind = SyncItem.PLACE, place = newer)))!!.place)
        // not a place line: not written (too few fields, no coordinates, a line break, too long)
        for (bad in listOf("n:1\tx", "n:1\tA\tpeak\t\tnorth\t11.1\t\t\tPOI", "n:1\tA\tpeak\t\t46.1\t11.1\t\t\tPOI\nn:2", "\tA\tpeak\t\t46.1\t11.1\t\t\tPOI",
                "n:1\t" + "A".repeat(3000) + "\tpeak\t\t46.1\t11.1\t\t\tPOI")) {
            assertNull(bad, SyncPlaces.clean(bad))
        }
        // field 11 on a plan item is never written or read
        val plan = SyncItem(SyncFormat.newId(ByteArray(16) { 3 }), 1, phone, title = "R", plan = "AQ", place = hutLine)
        assertNull(SyncFormat.decode(SyncFormat.encode(plan))!!.place)
        // a tombstone carries the line of the place it removes
        val l = SyncLedger.empty()
        val kept = l.keepPlace(hutLine, 10, phone)!!
        assertTrue(l.unkeepPlace(kept.id, 11, phone))
        val dead = SyncFormat.decode(SyncFormat.encode(l.get(kept.id)!!))!!
        assertTrue(dead.deleted); assertEquals(hutLine, dead.place)
    }

    @Test fun anOlderReaderSkipsAnUnknownKindAndKeepsIt() {
        // what this app does with a kind it does not know (as an older app / page does with kind 5): it reads, stores and
        // writes the item back unchanged, never shows it, never copies it
        val unknown = byteArrayOf(1, 9) + ByteArray(16) { 7 } + ByteArray(8) { 1 } + byteArrayOf(10, 0, 40, 2, 'h'.code.toByte(), 'i'.code.toByte())
        val item = SyncFormat.decode(unknown)!!
        assertEquals(9, item.kind)
        assertFalse(item.isUserItem)
        val l = SyncLedger.read("onpath-sync 1 3\n3 - " + SyncFormat.toText(unknown) + "\n")
        assertEquals(item, l.get(item.id))
        assertTrue(l.plans().isEmpty() && l.tracks().isEmpty() && l.places().isEmpty() && l.home() == null)
        val merged = SyncMerge.reconcile(mapOf(item.id to SyncEntry(item.copy(updatedAt = 99), 3, dirty = true)),
            listOf(RemoteItem(item.copy(updatedAt = 50), 4)))
        assertEquals(1, merged.size) // no copy
        // and kind 5 as an older reader keeps it: without field 11 (it skips the tag) – no place, nothing to show
        val stripped = SyncFormat.decode(SyncFormat.encode(SyncItem(SyncPlaces.HOME_ID, 5, web, kind = SyncItem.PLACE)))!!
        assertNull(stripped.place)
        assertFalse(SyncPlaces.live(stripped))
    }

    @Test fun keepingTheSamePlaceTwiceIsOneItem() {
        val l = SyncLedger.empty()
        val a = l.keepPlace(hutLine, 10, phone)!!
        assertEquals(a, l.keepPlace(hutLine, 20, web))
        assertEquals(1, l.places().size)
        // on two devices: the same line → the same id, the second write meets the first – one item, nothing to send
        val merged = SyncMerge.reconcile(mapOf(a.id to SyncEntry(a.copy(device = web, updatedAt = 30), 0, dirty = true)),
            listOf(RemoteItem(a, 1)))
        assertEquals(SyncEntry(a, 1, dirty = false), merged.getValue(a.id))
        assertEquals(1, merged.size)
    }

    @Test fun aNewHomeReplacesTheOldOneAndClearingIsATombstone() {
        val l = SyncLedger.empty()
        assertTrue(l.setHome(Lines.encode(home), 10, phone))
        assertFalse(l.setHome(Lines.encode(home), 11, phone)) // the same: nothing
        assertTrue(l.setHome(Lines.encode(work), 12, phone))
        assertEquals(Lines.encode(work), l.home()!!.place)
        assertEquals(1, l.entries.values.count { it.item.kind == SyncItem.PLACE })
        assertTrue(l.setHome(null, 13, phone))
        assertNull(l.home())
        assertEquals(Lines.encode(work), l.get(SyncPlaces.HOME_ID)!!.place) // the tombstone says which home went
    }

    @Test fun theMergeKeepsWhatTheUserKeptWithoutClocks() {
        val id = SyncPlaces.itemId(hutLine)
        val live = SyncItem(id, 100, phone, kind = SyncItem.PLACE, place = hutLine)
        val dead = SyncItem(id, 100, web, deleted = true, kind = SyncItem.PLACE, place = hutLine)
        // a delete made here on a version that is no longer current is dropped (the clock says nothing: ours is newer)
        var m = SyncMerge.reconcile(mapOf(id to SyncEntry(dead.copy(device = phone, updatedAt = 9_999), 3)), listOf(RemoteItem(live.copy(device = web), 4)))
        assertEquals(SyncEntry(live.copy(device = web), 4, dirty = false), m.getValue(id))
        // a keep made here beats a delete the other side wrote without seeing it – it goes up on top (base 4)
        m = SyncMerge.reconcile(mapOf(id to SyncEntry(live.copy(updatedAt = 1), 3)), listOf(RemoteItem(dead, 4)))
        assertEquals(SyncEntry(live.copy(updatedAt = 1), 4, dirty = true), m.getValue(id))
        // an older reader's line-less version never replaces a line
        m = SyncMerge.reconcile(mapOf(id to SyncEntry(live, 3)), listOf(RemoteItem(live.copy(place = null, device = web), 4)))
        assertEquals(hutLine, m.getValue(id).item.place)
        assertTrue(m.getValue(id).dirty)
    }

    @Test fun aHomeThatLosesStaysAFavourite() {
        val mine = SyncItem(SyncPlaces.HOME_ID, 9_999, phone, kind = SyncItem.PLACE, place = Lines.encode(home))
        val theirs = SyncItem(SyncPlaces.HOME_ID, 100, web, kind = SyncItem.PLACE, place = Lines.encode(work))
        val m = SyncMerge.reconcile(mapOf(mine.id to SyncEntry(mine, 0)), listOf(RemoteItem(theirs, 5)))
        assertEquals(SyncEntry(theirs, 5, dirty = false), m.getValue(SyncPlaces.HOME_ID)) // the mailbox's home stays
        val fav = m.getValue(SyncPlaces.itemId(Lines.encode(home)))
        assertEquals(Lines.encode(home), fav.item.place)
        assertTrue(fav.dirty)
        // the same home on both sides: nothing extra
        val same = SyncMerge.reconcile(mapOf(mine.id to SyncEntry(mine, 0)), listOf(RemoteItem(mine.copy(device = web), 5)))
        assertEquals(1, same.size)
        // their „Zuhause entfernen“ never saw our new home: ours stays
        val cleared = SyncMerge.reconcile(mapOf(mine.id to SyncEntry(mine, 3)),
            listOf(RemoteItem(theirs.copy(deleted = true), 4)))
        assertEquals(SyncEntry(mine, 4, dirty = true), cleared.getValue(SyncPlaces.HOME_ID))
    }

    @Test fun forgetDropsThePlacesAndRestartSendsThemAgain() {
        val l = SyncLedger.empty()
        l.keepPlace(hutLine, 1, phone); l.setHome(Lines.encode(home), 2, phone)
        val r = SyncLedger.read(l.write()); r.restart()
        assertEquals(2, r.outbox().count { it.item.kind == SyncItem.PLACE })
        l.forget()
        assertTrue(l.entries.isEmpty())
    }

    @Test fun pairingWithPlacesOnBothSidesMergesWithoutCopies() {
        // phone: home + hut + peak; browser: the same home, the hut from the online search (another line), work
        val p = SyncLedger.empty(); val w = SyncLedger.empty()
        val phoneKept = SavedPlaces(home, listOf(hut, peak))
        val webKept = SavedPlaces(home, listOf(hutOnline, work))
        sync.export(p, phoneKept, 10, phone); sync.export(w, webKept, 10, web)
        // the browser reads what the phone sent (as a round does)
        val before = w.entries.mapValues { it.value.item }
        val remote = p.entries.values.mapIndexed { i, e -> RemoteItem(e.item, i + 1L) }
        val after = SyncLedger.read(w.write()).also { it.applyItems(remote) }
        val got = sync.import(after, PlaceSync.changed(before, after), webKept, webKept)
        assertEquals(home, got.home)
        assertEquals(listOf(peak, hutOnline, work).map { it.id }.toSet(), got.favourites.map { it.id }.toSet())
        assertEquals(3, got.favourites.size) // the hut once
        // and nothing for the export to change afterwards (no echo, no tombstone)
        assertEquals(0, sync.export(after, got, 20, web))
    }

    @Test fun aTombstoneTakesEveryCopyButNotAPlaceKeptAgainSince() {
        val l = SyncLedger.empty()
        val kept = SavedPlaces(null, listOf(hut, peak))
        l.applyItems(listOf(hut, peak).mapIndexed { i, p ->
            RemoteItem(SyncItem(SyncPlaces.itemId(Lines.encode(p)), 5, web, kind = SyncItem.PLACE, place = Lines.encode(p)), i + 1L)
        })
        assertEquals(0, sync.export(l, kept, 10, phone)) // in step
        // the other side's copy of the hut (its own line, its own item) is no longer kept there
        val id = SyncPlaces.itemId(Lines.encode(hutOnline))
        val before = l.entries.mapValues { it.value.item }
        l.applyItems(listOf(RemoteItem(SyncItem(id, 11, web, deleted = true, kind = SyncItem.PLACE, place = Lines.encode(hutOnline)), 7)))
        // the line differs from ours – the same-place rule finds ours
        assertEquals(listOf(peak), sync.import(l, PlaceSync.changed(before, l), kept, kept).favourites)
        // kept again after the export (not in what the export saw): stays
        assertEquals(listOf(hut, peak), sync.import(l, listOf(id), kept, SavedPlaces(null, listOf(peak))).favourites)
    }

    @Test fun aHomeChangedAfterTheExportStaysAsTheUserHasIt() {
        val l = SyncLedger.empty()
        l.applyItems(listOf(RemoteItem(SyncItem(SyncPlaces.HOME_ID, 5, web, kind = SyncItem.PLACE, place = Lines.encode(work)), 1)))
        val seen = SavedPlaces(home, emptyList())
        assertEquals(work, sync.import(l, listOf(SyncPlaces.HOME_ID), seen, seen).home)
        // the user set another home between the export and the import: his stays (the next export sends it)
        assertEquals(peak, sync.import(l, listOf(SyncPlaces.HOME_ID), SavedPlaces(peak), seen).home)
    }

    /** The app (2026-11-28, no Zuhause): the home item is read and left alone – not taken in, not sent, not cleared. */
    @Test fun withoutHomeTheHomeItemIsLeftAlone() {
        val app = PlaceSync(Lines::encode, Lines::decode, withHome = false)
        val l = SyncLedger.empty()
        l.applyItems(listOf(RemoteItem(SyncItem(SyncPlaces.HOME_ID, 5, web, kind = SyncItem.PLACE, place = Lines.encode(work)), 1)))
        val kept = SavedPlaces(null, listOf(peak))
        assertEquals("not taken in, the favourites untouched", kept, app.import(l, listOf(SyncPlaces.HOME_ID), kept, kept))
        assertEquals("nothing for the home (the peak goes out as a favourite)", 1, app.export(l, kept, 10, phone))
        assertEquals("the planner's home stays", Lines.encode(work), l.home()?.place)
        assertEquals("a home of its own is never sent either", 0, app.export(l, SavedPlaces(home, listOf(peak)), 11, phone))
        assertEquals(Lines.encode(work), l.home()?.place)
    }

    @Test fun aLineThisDeviceCannotReadIsLeftAlone() {
        val l = SyncLedger.empty()
        val odd = "n:9\tNeu\tpeak\t\t46.1\t11.1\t\t\tPOI"
        l.applyItems(listOf(RemoteItem(SyncItem(SyncPlaces.itemId(odd), 5, web, kind = SyncItem.PLACE, place = odd), 1)))
        val strict = PlaceSync(Lines::encode, decode = { line -> if (line.startsWith("n:9")) null else Lines.decode(line) })
        assertEquals(SavedPlaces(), strict.import(l, l.entries.keys, SavedPlaces(), SavedPlaces()))
        assertEquals(0, strict.export(l, SavedPlaces(), 10, phone)) // not deleted because it is not shown here
        assertTrue(SyncPlaces.live(l.get(SyncPlaces.itemId(odd))))
    }

    private fun SyncLedger.applyItems(items: List<RemoteItem>) {
        val keys = SyncKeys(ByteArray(32) { 4 })
        apply(keys, items.map { Triple(it.item.id, it.rev, SyncFormat.seal(keys, it.item, ByteArray(12) { 1 })) }, items.maxOf { it.rev })
    }

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** The SearchStore line (app: SearchStore.encode / decode, planner: WebKept) – the shared format, written out here. */
    object Lines {
        private fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ')
        fun encode(p: Place): String = listOf(p.id, clean(p.name), p.kind, p.sub.orEmpty(),
            String.format(Locale.ROOT, "%.6f", p.lat), String.format(Locale.ROOT, "%.6f", p.lon),
            p.ele?.toString().orEmpty(), clean(p.address), p.source.name, clean(p.town), clean(p.village),
            if (p.shared) "s" else "").joinToString("\t")
        fun decode(l: String): Place? {
            val p = l.split('\t'); if (p.size < 9) return null
            return Place(p[0], p[1].ifEmpty { null }, p[2], p[4].toDoubleOrNull() ?: return null, p[5].toDoubleOrNull() ?: return null,
                p[6].toIntOrNull(), p[3].ifEmpty { null }, p[7].ifEmpty { null },
                Place.Source.entries.firstOrNull { it.name == p[8] } ?: Place.Source.POI,
                town = p.getOrNull(9)?.ifEmpty { null }, village = p.getOrNull(10)?.ifEmpty { null }, shared = p.getOrNull(11) == "s")
        }
    }
}

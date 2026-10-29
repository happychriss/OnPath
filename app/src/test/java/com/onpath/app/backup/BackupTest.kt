package com.onpath.app.backup

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

/**
 * CORE FEATURE C11 (owner): tours and plans are never lost – a GPX file per tour / plan in
 * Documents/OnPath, restorable after a reinstall or on a new phone; deleting goes to a 30-day recycle bin.
 */
class BackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Rome")
    private val day = 24L * 3600 * 1000
    // 2026-10-21 10:12 in Rome
    private val created = 1792570320000L

    private val track = listOf(
        listOf(TrackPoint(46.6714123, 11.1700456, 412.3, 1792570320000L), TrackPoint(46.6716, 11.1703, 415.0, 1792570325000L)),
        listOf(TrackPoint(46.6720, 11.1710, null, 1792570400000L), TrackPoint(46.6722, 11.1711, 420.1, null)),
    )
    private val tourMeta = BackupMeta("u-tour", TourKind.RECORDED, "Algund → Meran", created, "HIKE", 3, 5230.5, 312.0, 40.5,
        3720.0, emptyList(), emptySet(), 1792570320000L, 1792570400000L, null, 1792574000000L)
    private val planMeta = BackupMeta("u-plan", TourKind.PLANNED, "Waalweg <Algund> & \"Meran\"", created, "MTB", 4, 12480.0, 842.0,
        310.0, 4.6 * 3600, listOf(GpxWaypoint(46.67, 11.16, "Start"), GpxWaypoint(46.68, 11.17, null), GpxWaypoint(46.69, 11.18, "Hütte", 1200.0)),
        setOf(2), savedMs = 1792574000000L)
    private val planLine = listOf(listOf(TrackPoint(46.67, 11.16, 400.0), TrackPoint(46.68, 11.17, 500.0), TrackPoint(46.69, 11.18, 1200.0)))

    // ---------------------------------------------------------------- naming

    @Test fun readableSafeFileNames() {
        assertEquals("2026-10-21 Algund → Meran", BackupNames.base(created, "Algund → Meran", zone))
        assertEquals("Tour 21 Oct 2026, 10_12", BackupNames.sanitize("Tour 21 Oct 2026, 10:12"))
        assertEquals("a_b_c_d_e_f_g_h_i", BackupNames.sanitize("a/b\\c:d*e?f\"g<h>i"))
        assertEquals("Tour", BackupNames.sanitize("  ...  "))
        assertEquals("x y", BackupNames.sanitize("x\n\ty"))
        assertEquals("hidden", BackupNames.sanitize("..hidden")) // leading dots trimmed: no hidden files
        assertTrue(BackupNames.sanitize("x".repeat(300)).length <= BackupNames.MAX_NAME)
    }

    @Test fun uniqueNamesInAFolder() {
        assertEquals("A.gpx", BackupNames.unique("A", emptyList()))
        assertEquals("A (2).gpx", BackupNames.unique("A", listOf("a.gpx")))
        assertEquals("A (3).gpx", BackupNames.unique("A", listOf("A.gpx", "A (2).gpx")))
    }

    // ---------------------------------------------------------------- file format

    @Test fun tourRoundTripIsIdentical() {
        val text = BackupCodec.encode(tourMeta, track)
        val back = BackupCodec.decode(text)!!
        assertEquals(tourMeta, back.meta)
        assertSameLine(track, back.segments)
    }

    @Test fun planRoundTripKeepsStopsNamesAndPins() {
        val text = BackupCodec.encode(planMeta, planLine)
        val back = BackupCodec.decode(text)!!
        assertEquals(planMeta, back.meta)
        assertEquals("plan", back.meta.type)
        assertEquals(setOf(2), back.meta.pinned)
        assertSameLine(planLine, back.segments)
    }

    @Test fun backupIsAPlainGpxForOtherApps() {
        val g = GpxParser.parse(BackupCodec.encode(planMeta, planLine))
        assertEquals(planMeta.name, g.name)
        assertEquals(3, g.pointCount)
        assertEquals(3, g.waypoints.size) // stops as waypoints
        assertTrue(g.warnings.isEmpty())
        val tour = GpxParser.parse(BackupCodec.encode(tourMeta, track))
        assertEquals(2, tour.allSegments.size)
        assertEquals(4, tour.pointCount)
    }

    @Test fun foreignGpxIsNoBackup() {
        val other = com.onpath.app.gpx.GpxWriter.write("Komoot", planLine)
        assertFalse(BackupCodec.hasRecord(other))
        assertNull(BackupCodec.decode(other))
        // a damaged record is skipped, never thrown
        assertNull(BackupCodec.decode(BackupCodec.encode(tourMeta, track).replace("uuid", "uu\"id")))
    }

    @Test fun metaFromTour() {
        val t = Tour(7, TourKind.RECORDED, "T", created, "HIKE", 3, 1.0, 2.0, 3.0, 4.0, "done", emptyList(), "u", setOf(1), null)
        val m = BackupMeta.of(t, track, 99L)
        assertEquals(1792570320000L, m.startMs)
        assertEquals(1792570400000L, m.endMs)
        assertEquals(99L, m.savedMs)
        assertEquals("u", m.uuid)
    }

    private fun assertSameLine(a: List<List<TrackPoint>>, b: List<List<TrackPoint>>) {
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (x, y) ->
            assertEquals(x.size, y.size)
            x.zip(y).forEach { (p, q) ->
                assertEquals(p.lat, q.lat, 1e-7); assertEquals(p.lon, q.lon, 1e-7)
                if (p.ele == null) assertNull(q.ele) else assertEquals(p.ele!!, q.ele!!, 0.05)
                assertEquals(p.timeMs, q.timeMs)
            }
        }
    }

    // ---------------------------------------------------------------- restore

    private fun found(m: BackupMeta, file: String = "${m.name}.gpx", segs: List<List<TrackPoint>> = track) =
        FoundBackup(BackupEntry(m, segs), "h:$file", file)

    @Test fun restoreDedupesByUuidNewestWins() {
        val old = found(tourMeta.copy(savedMs = 1, name = "old"))
        val new = found(tourMeta.copy(savedMs = 2, name = "new"), "copy (2).gpx")
        val plan = found(planMeta)
        val r = BackupRestore.plan(listOf(old, new, plan), existingUuids = emptySet())
        assertEquals(2, r.size)
        assertEquals("new", r.first { it.entry.meta.uuid == "u-tour" }.entry.meta.name)
    }

    @Test fun restoreSkipsToursOnThePhoneAndTheRecycleBin() {
        val binned = found(tourMeta.copy(uuid = "u-bin", deletedMs = 5))
        val r = BackupRestore.plan(listOf(found(tourMeta), found(planMeta), binned), existingUuids = setOf("u-tour"))
        assertEquals(listOf("u-plan"), r.map { it.entry.meta.uuid })
    }

    @Test fun restoreSummary() {
        assertEquals("23 tracks and 5 routes restored.", BackupRestore.summary(BackupRestore.Result(23, 5, 0, 0)))
        assertEquals("1 track and 0 routes restored (2 already on this phone, 1 could not be read).",
            BackupRestore.summary(BackupRestore.Result(1, 0, 2, 1)))
        assertEquals("No tracks or routes restored.", BackupRestore.summary(BackupRestore.Result(0, 0, 0, 0)))
    }

    // ---------------------------------------------------------------- recycle bin

    @Test fun recycleBinKeeps30Days() {
        val now = created + 100 * day
        assertFalse(RecycleBin.expired(now - 29 * day, now))
        assertTrue(RecycleBin.expired(now - 30 * day, now))
        assertEquals(29, RecycleBin.daysLeft(now - 1000, now))
        assertEquals(0, RecycleBin.daysLeft(now - 31 * day, now))
        assertEquals(now - 30 * day, RecycleBin.cutoff(now))
    }

    // ---------------------------------------------------------------- the folder (fake store)

    class FakeStore(override val key: String = "documents") : BackupStore {
        override val label = "Documents/OnPath"
        /** handle → (path, content); path = "name" or "Deleted/name" */
        val files = LinkedHashMap<String, Pair<String, String>>()
        var next = 0
        var failWith: String? = null
        var canRename = true
        val paths get() = files.values.map { it.first }.sorted()
        fun text(path: String) = files.values.first { it.first == path }.second

        override fun create(fileName: String, deleted: Boolean, bytes: ByteArray): BackupStore.Created {
            failWith?.let { throw BackupException(it) }
            val dir = if (deleted) "Deleted/" else ""
            var name = fileName
            var n = 1
            while (files.values.any { it.first == dir + name }) name = fileName.removeSuffix(".gpx") + " (${n++}).gpx" // like MediaStore
            val h = "h${next++}"
            files[h] = (dir + name) to String(bytes)
            return BackupStore.Created(h, name)
        }
        override fun overwrite(handle: String, bytes: ByteArray): Boolean {
            failWith?.let { throw BackupException(it) }
            val f = files[handle] ?: return false
            files[handle] = f.first to String(bytes); return true
        }
        override fun rename(handle: String, fileName: String): String? {
            if (!canRename) return null
            val f = files[handle] ?: return null
            files[handle] = (f.first.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + fileName) to f.second
            return handle
        }
        override fun delete(handle: String): Boolean { files.remove(handle); return true }
    }

    private fun backup(store: FakeStore = FakeStore(), index: BackupIndex = BackupIndex(tmp.root.resolve("index.json"))) =
        Triple(TourBackup(store, index, zone), store, index)

    @Test fun saveAgainOverwritesTheSameFile() {
        val (b, store) = backup()
        assertEquals("2026-10-21 Algund → Meran.gpx", b.save(tourMeta, track))
        b.save(tourMeta.copy(distanceM = 6000.0), track)
        assertEquals(listOf("2026-10-21 Algund → Meran.gpx"), store.paths)
        assertEquals(6000.0, BackupCodec.decode(store.text("2026-10-21 Algund → Meran.gpx"))!!.meta.distanceM, 0.0)
    }

    @Test fun renameRenamesTheFile() {
        val (b, store) = backup()
        b.save(tourMeta.copy(name = "Tour 21 Oct 2026, 10:12"), track)
        b.save(tourMeta, track)
        assertEquals(listOf("2026-10-21 Algund → Meran.gpx"), store.paths)
        // a folder that cannot rename: the new file replaces the old one
        store.canRename = false
        b.save(tourMeta.copy(name = "Meran"), track)
        assertEquals(listOf("2026-10-21 Meran.gpx"), store.paths)
    }

    @Test fun twoToursWithTheSameNameGetTwoFiles() {
        val (b, store) = backup()
        b.save(tourMeta, track)
        b.save(tourMeta.copy(uuid = "u-2"), track)
        assertEquals(listOf("2026-10-21 Algund → Meran (2).gpx", "2026-10-21 Algund → Meran.gpx"), store.paths)
    }

    @Test fun recycleBinMovesTheFileAndBackThenPurge() {
        val (b, store, index) = backup()
        b.save(tourMeta, track)
        b.save(tourMeta.copy(deletedMs = created + day), track)
        assertEquals(listOf("Deleted/2026-10-21 Algund → Meran.gpx"), store.paths)
        b.save(tourMeta, track) // restored from the bin
        assertEquals(listOf("2026-10-21 Algund → Meran.gpx"), store.paths)
        b.save(tourMeta.copy(deletedMs = created + day), track)
        b.purge("u-tour")
        assertTrue(store.paths.isEmpty())
        assertNull(index.get("u-tour"))
    }

    @Test fun fileDeletedByTheUserIsWrittenAgain() {
        val (b, store) = backup()
        b.save(tourMeta, track)
        store.files.clear()
        b.save(tourMeta, track)
        assertEquals(listOf("2026-10-21 Algund → Meran.gpx"), store.paths)
    }

    @Test fun indexSurvivesARestartOfTheApp() {
        val f = tmp.root.resolve("idx.json")
        val store = FakeStore()
        TourBackup(store, BackupIndex(f), zone).save(tourMeta, track)
        TourBackup(store, BackupIndex(f), zone).save(tourMeta.copy(ascentM = 1.0), track)
        assertEquals(1, store.files.size)
        // unreadable index: no crash, the next save writes a new file (a duplicate at worst, never a loss)
        f.writeText("{broken")
        TourBackup(store, BackupIndex(f), zone).save(tourMeta, track)
        assertEquals(2, store.files.size)
    }

    @Test fun anotherFolderGetsItsOwnFiles() {
        val idx = BackupIndex(tmp.root.resolve("i.json"))
        val docs = FakeStore("documents")
        TourBackup(docs, idx, zone).save(tourMeta, track)
        val drive = FakeStore("tree:drive")
        val b = TourBackup(drive, idx, zone)
        assertFalse(b.has("u-tour"))
        b.save(tourMeta, track)
        assertEquals(1, drive.files.size)
        assertEquals(1, docs.files.size)
    }

    // ---------------------------------------------------------------- hooks (when a backup is written)

    private class Source(val tours: MutableMap<Long, Tour>, val segs: List<List<TrackPoint>>) : BackupSource {
        override fun tour(id: Long) = tours[id]
        override fun segments(id: Long) = segs
        override fun all() = tours.values.toList()
    }

    private class Events : BackupQueue.Listener {
        val saved = mutableListOf<String>(); val failed = mutableListOf<String>()
        override fun saved(fileName: String, count: Int) { saved += fileName }
        override fun failed(reason: String, e: Throwable?) { failed += reason }
    }

    private fun tour(id: Long, state: String = "done", kind: TourKind = TourKind.RECORDED, name: String = "Tour $id", deleted: Long? = null) =
        Tour(id, kind, name, created, "HIKE", 3, 100.0, 1.0, 1.0, 60.0, state, emptyList(), "u$id", emptySet(), deleted)

    @Test fun finishedTourIsBackedUpRecordingIsNot() {
        val tours = mutableMapOf(1L to tour(1, state = "active"))
        val (b, store) = backup()
        val ev = Events()
        val q = BackupQueue(Source(tours, track), { b }, { it.run() }, ev)
        q.tourChanged(1) // still recording
        assertTrue(store.files.isEmpty())
        tours[1] = tour(1) // finished
        q.tourChanged(1)
        assertEquals(listOf("2026-10-21 Tour 1.gpx"), store.paths)
        assertEquals(1, ev.saved.size)
        // saved plan, then renamed
        tours[2] = tour(2, kind = TourKind.PLANNED, name = "Plan")
        q.tourChanged(2)
        tours[2] = tour(2, kind = TourKind.PLANNED, name = "Waalweg")
        q.tourChanged(2)
        assertEquals(listOf("2026-10-21 Tour 1.gpx", "2026-10-21 Waalweg.gpx"), store.paths)
        assertEquals("plan", BackupCodec.decode(store.text("2026-10-21 Waalweg.gpx"))!!.meta.type)
        // deleted → Deleted/, purged → gone
        tours[1] = tour(1, deleted = created + day)
        q.tourChanged(1)
        assertEquals(listOf("2026-10-21 Waalweg.gpx", "Deleted/2026-10-21 Tour 1.gpx"), store.paths)
        tours.remove(1); q.purged(listOf("u1"))
        assertEquals(listOf("2026-10-21 Waalweg.gpx"), store.paths)
        assertTrue(ev.failed.isEmpty())
    }

    @Test fun failureIsReportedNeverThrown() {
        val (b, store) = backup()
        store.failWith = "storage full"
        val ev = Events()
        val q = BackupQueue(Source(mutableMapOf(1L to tour(1)), track), { b }, { it.run() }, ev)
        q.tourChanged(1)
        assertEquals(listOf("storage full"), ev.failed)
        val noFolder = BackupQueue(Source(mutableMapOf(1L to tour(1)), track), { throw BackupException("no permission") }, { it.run() }, ev)
        noFolder.tourChanged(1)
        assertEquals("no permission", ev.failed.last())
    }

    @Test fun startOfAppWritesOnlyMissingFiles() {
        val tours = mutableMapOf(1L to tour(1), 2L to tour(2), 3L to tour(3, state = "active"))
        val (b, store) = backup()
        val q = BackupQueue(Source(tours, track), { b }, { it.run() }, Events())
        q.tourChanged(1)
        val before = store.files.toMap()
        var n = -1
        q.syncAll(missingOnly = true) { n = it }
        assertEquals(1, n) // tour 2; tour 1 has its file, tour 3 is still recording
        assertEquals(2, store.files.size)
        assertEquals(before["h0"], store.files["h0"])
        assertNotNull(store.files.values.firstOrNull { it.first == "2026-10-21 Tour 2.gpx" })
    }
}

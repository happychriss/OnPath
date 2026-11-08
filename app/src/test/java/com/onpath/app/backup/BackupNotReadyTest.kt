package com.onpath.app.backup

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

/**
 * CORE FEATURE C11, field bug 2026-11-08 (Samsung S10, Android 12, right after a restart): at app start MediaStore said
 * "Volume external_primary not found" – the phone's storage was not there yet (the process got the stub path
 * /dev/null; a minute later it was /storage/emulated/0). The red HINWEIS "Backup to Documents/OnPath failed" came at
 * once. Now: the copy stays queued and is retried quietly; the notice comes only when it keeps failing.
 */
class BackupNotReadyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Rome")
    private val created = 1792570320000L
    private val track = listOf(listOf(TrackPoint(46.67, 11.17, 412.0, created), TrackPoint(46.68, 11.18, 415.0, created + 5000)))
    private val volumeMissing = "Volume external_primary not found"

    /** The S10's MediaStore: "Volume external_primary not found" for the first [notReadyFor] writes. */
    private class BootingStore(var notReadyFor: Int) : BackupStore {
        val inner = BackupTest.FakeStore()
        override val key = inner.key
        override val label = inner.label
        var tries = 0
        private fun check() {
            tries++
            if (notReadyFor > 0) {
                notReadyFor--
                // what MediaStoreBackupStore threw in 0.17.3: the plain reason, the volume error as the cause
                throw BackupException("the Documents folder cannot be written", IllegalArgumentException("Volume external_primary not found"))
            }
        }
        override fun create(fileName: String, deleted: Boolean, bytes: ByteArray): BackupStore.Created { check(); return inner.create(fileName, deleted, bytes) }
        override fun overwrite(handle: String, bytes: ByteArray): Boolean { check(); return inner.overwrite(handle, bytes) }
        override fun rename(handle: String, fileName: String) = inner.rename(handle, fileName)
        override fun delete(handle: String) = inner.delete(handle)
    }

    private class Source(val tours: MutableMap<Long, Tour>, val segs: List<List<TrackPoint>>) : BackupSource {
        override fun tour(id: Long) = tours[id]
        override fun segments(id: Long) = segs
        override fun all() = tours.values.toList()
    }

    private class Events : BackupQueue.Listener {
        val saved = mutableListOf<String>(); val failed = mutableListOf<String>(); val waiting = mutableListOf<Long?>()
        override fun saved(fileName: String, count: Int) { saved += fileName }
        override fun failed(reason: String, e: Throwable?) { failed += reason }
        override fun waiting(reason: String, e: Throwable?, retryInMs: Long?) { waiting += retryInMs }
    }

    /** The backup thread's timer, driven by hand. */
    private class Timer {
        val due = ArrayDeque<Pair<Long, Runnable>>()
        fun schedule(ms: Long, r: Runnable) { due += ms to r }
        fun runNext(): Long { val (ms, r) = due.removeFirst(); r.run(); return ms }
    }

    private fun tour(id: Long, deleted: Long? = null) =
        Tour(id, TourKind.RECORDED, "Tour $id", created, "HIKE", 3, 100.0, 1.0, 1.0, 60.0, "done", emptyList(), "u$id", emptySet(), deleted)

    private var clock = 1_794_129_335_000L // 2026-11-08 10:15:35

    private fun queue(store: BootingStore, tours: MutableMap<Long, Tour>, ev: Events, timer: Timer, pending: BackupQueue.PendingStore,
                      index: BackupIndex = BackupIndex(tmp.root.resolve("index.json"))) =
        BackupQueue(Source(tours, track), { TourBackup(store, index, zone) }, { it.run() }, ev, { clock },
            later = timer::schedule, store = pending)

    // ---------------------------------------------------------------- which volume, which failure

    @Test fun thePrimaryVolumeWhenListedElseTheGenericOne() {
        assertEquals("external_primary", BackupVolumes.choose(setOf("external_primary", "1a2b-3c4d")))
        assertEquals("external_primary", BackupVolumes.choose(setOf("EXTERNAL_PRIMARY")))
        assertEquals("external", BackupVolumes.choose(setOf("1a2b-3c4d"))) // only an SD card listed: MediaStore picks
        assertEquals("external", BackupVolumes.choose(emptySet())) // storage still starting
    }

    @Test fun aMissingVolumeIsNotReadyAFullDiskIsAFailure() {
        assertTrue(BackupVolumes.isNotReady(BackupException("x", IllegalStateException("wrapped", IllegalArgumentException(volumeMissing)))))
        assertTrue(BackupVolumes.isNotReady(IllegalArgumentException("Volume 1a2b-3c4d not found")))
        assertTrue(BackupVolumes.isNotReady(BackupException("not ready", notReady = true)))
        assertFalse(BackupVolumes.isNotReady(BackupException("storage full", java.io.IOException("write failed: ENOSPC"))))
        assertFalse(BackupVolumes.isNotReady(SecurityException("no access")))
    }

    // ---------------------------------------------------------------- the field case

    @Test fun firstFailureAtStartShowsNoNotice_theRetryWrites_neverANotice() {
        // like the S10: an unfinished tour deleted at app start (→ Deleted/) while the storage is still starting
        val tours = mutableMapOf(5L to tour(5, deleted = clock))
        val store = BootingStore(notReadyFor = 1)
        val ev = Events(); val timer = Timer(); val pending = BackupQueue.PendingStore.InMemory()
        val q = queue(store, tours, ev, timer, pending)
        q.tourChanged(5)
        assertTrue("no box at the first not-ready", ev.failed.isEmpty())
        assertEquals(listOf<Long?>(30_000L), ev.waiting)
        assertEquals(setOf(5L), pending.value.ids) // queued, kept across a restart
        assertTrue(store.inner.files.isEmpty())

        clock += 30_000L
        assertEquals(30_000L, timer.runNext())
        assertEquals(listOf("Deleted/2026-10-21 Tour 5.gpx"), store.inner.paths)
        assertEquals(BackupQueue.Pending(), pending.value) // nothing queued, the count starts again
        // the app-start sync afterwards: nothing missing, nothing written twice
        q.syncAll(missingOnly = true)
        assertEquals(1, store.inner.files.size)
        assertTrue("never a box", ev.failed.isEmpty())
        assertTrue(timer.due.isEmpty())
    }

    @Test fun aQueuedTourIsWrittenAtTheNextTourEndAndAfterARestart() {
        val tours = mutableMapOf(1L to tour(1), 2L to tour(2))
        val store = BootingStore(notReadyFor = 1)
        val ev = Events(); val timer = Timer(); val pending = BackupQueue.PendingStore.InMemory()
        queue(store, tours, ev, timer, pending).tourChanged(1)
        assertEquals(setOf(1L), pending.value.ids)
        // the app is closed before the retry: the next start writes it (the queue was kept)
        clock += 5 * 60_000L
        val ev2 = Events()
        queue(store, tours, ev2, Timer(), pending).tourChanged(2) // the next tour end
        assertEquals(listOf("2026-10-21 Tour 1.gpx", "2026-10-21 Tour 2.gpx"), store.inner.paths)
        assertTrue(pending.value.ids.isEmpty())
        assertTrue(ev.failed.isEmpty() && ev2.failed.isEmpty())
    }

    @Test fun theNoticeComesOnlyWhenItKeepsFailing_overTimeAndAnotherStart() {
        val tours = mutableMapOf(1L to tour(1))
        val store = BootingStore(notReadyFor = Int.MAX_VALUE)
        val ev = Events(); val timer = Timer(); val pending = BackupQueue.PendingStore.InMemory()
        queue(store, tours, ev, timer, pending).tourChanged(1)
        // the quiet retries in this run: 30 s, 2 min, 10 min, 30 min – no box (one occasion only)
        for (expected in BackupQueue.RETRY_DELAYS_MS) { clock += expected; assertEquals(expected, timer.runNext()) }
        assertTrue(timer.due.isEmpty()) // then it waits for the next backup / app start
        assertTrue(ev.failed.isEmpty())
        // a second occasion soon after the first would not be enough either – here: the next start, 45 min later
        clock += 3 * 60_000L
        val ev2 = Events()
        val q2 = queue(store, tours, ev2, timer, pending)
        q2.syncAll(missingOnly = true)
        assertEquals(1, ev2.failed.size)
        assertTrue("says what to do", ev2.failed[0].contains("restart the phone"))
        // once only: the next attempts stay quiet
        q2.tourChanged(1)
        assertEquals(1, ev2.failed.size)
        // the storage is back: the tour is written, everything clears
        store.notReadyFor = 0
        clock += 60_000L
        q2.syncAll(missingOnly = true)
        assertEquals(listOf("2026-10-21 Tour 1.gpx"), store.inner.paths)
        assertEquals(BackupQueue.Pending(), pending.value)
    }

    @Test fun aSecondOccasionWithinTenMinutesStaysQuiet() {
        val tours = mutableMapOf(1L to tour(1), 2L to tour(2))
        val store = BootingStore(notReadyFor = Int.MAX_VALUE)
        val ev = Events(); val pending = BackupQueue.PendingStore.InMemory()
        val q = queue(store, tours, ev, Timer(), pending)
        q.syncAll(missingOnly = true) // app start
        clock += 60_000L
        q.tourChanged(2) // a tour end a minute later
        assertTrue(ev.failed.isEmpty())
        assertEquals(setOf(2L), pending.value.ids)
        assertTrue(pending.value.syncAll)
        assertEquals(2, pending.value.occasions)
    }

    @Test fun backUpAllNowSaysItAtOnce_aFullDiskToo() {
        val tours = mutableMapOf(1L to tour(1))
        val ev = Events()
        var n = 0
        queue(BootingStore(notReadyFor = 1), tours, ev, Timer(), BackupQueue.PendingStore.InMemory())
            .syncAll(missingOnly = false) { n = it }
        assertEquals(-1, n)
        assertEquals(1, ev.failed.size) // the user pressed the button: no silent wait

        val full = BackupTest.FakeStore().apply { failWith = "storage full" }
        val ev2 = Events()
        BackupQueue(Source(tours, track), { TourBackup(full, BackupIndex(null), zone) }, { it.run() }, ev2).tourChanged(1)
        assertEquals(listOf("storage full"), ev2.failed) // a real failure: at once, as before
    }

    @Test fun aMissedMoveToTheRecycleBinIsDoneAtTheNextStart() {
        val tours = mutableMapOf(1L to tour(1))
        val store = BootingStore(notReadyFor = 0)
        val index = BackupIndex(tmp.root.resolve("i.json"))
        val ev = Events()
        val q = queue(store, tours, ev, Timer(), BackupQueue.PendingStore.InMemory(), index)
        q.tourChanged(1)
        // deleted to the bin, but the move was lost (e.g. the app killed before the retry)
        tours[1] = tour(1, deleted = clock)
        q.syncAll(missingOnly = true)
        assertEquals(listOf("Deleted/2026-10-21 Tour 1.gpx"), store.inner.paths)
        assertTrue(index.get("u1")!!.deleted)
        assertNull(ev.failed.firstOrNull())
    }
}

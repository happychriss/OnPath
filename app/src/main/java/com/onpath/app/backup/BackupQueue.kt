package com.onpath.app.backup

import com.onpath.app.data.Tour
import com.onpath.app.gpx.TrackPoint
import com.onpath.strings.*

/** What the backup reads (TourDb in the app, a map in tests). */
interface BackupSource {
    fun tour(id: Long): Tour?
    fun segments(id: Long): List<List<TrackPoint>>
    /** Every tour incl. the recycle bin. */
    fun all(): List<Tour>
    /** The plan a recorded tour followed ([com.onpath.app.data.TourPlan]); null = none. */
    fun plan(id: Long): com.onpath.app.data.TourPlan? = null
}

/**
 * When a backup file is written (JVM tested): after a tour is finished (state "done", never while it records),
 * after a plan is saved / overwritten, after a rename, when a tour goes to the recycle bin (file → Deleted/) or comes
 * back, and – at app start – for every tour that has no file in the current folder yet (first run with backups,
 * another folder chosen, a move to Deleted/ that did not happen). All work runs on [run] (one background thread in the
 * app): recording is never blocked. Failures are reported to [listener] with a plain reason, never thrown.
 *
 * Storage not ready yet ([BackupVolumes.isNotReady], field bug 2026-11-08: "Volume external_primary not found" right
 * after a restart) is no failure for the user: the tour stays queued ([Pending], kept across restarts), is retried
 * after [RETRY_DELAYS_MS] (via [later]), at the next backup (tour end, save …) and at the next app start, and only
 * [Listener.waiting] hears of it (an INFO). The user's notice ([Listener.failed]) comes only when it keeps failing:
 * still not ready after [WARN_AFTER_MS] and on a later occasion than the first (another app start or backup) – or at
 * once when the user asked ("Back up all now"). A real failure (disk full, no access) is reported at once as before.
 */
class BackupQueue(
    private val source: BackupSource,
    /** The current folder's backup; throws [BackupException] when there is none that can be written. */
    private val backup: () -> TourBackup,
    private val run: (Runnable) -> Unit,
    private val listener: Listener,
    private val now: () -> Long = System::currentTimeMillis,
    /** Runs a retry after a delay on the same thread as [run] (the app: the backup thread). */
    private val later: (Long, Runnable) -> Unit = { _, _ -> },
    /** The queued tours across restarts (the app: prefs). */
    private val store: PendingStore = PendingStore.InMemory(),
) {
    interface Listener {
        fun saved(fileName: String, count: Int)
        fun failed(reason: String, e: Throwable?)
        /** Storage not ready: nothing lost, the tours stay queued; retried in [retryInMs] (null = at the next start / backup). */
        fun waiting(reason: String, e: Throwable?, retryInMs: Long?) {}
    }

    /** Tours still to be written because the storage was not ready, and since when / on how many occasions it was not. */
    data class Pending(val ids: Set<Long> = emptySet(), val sinceMs: Long? = null, val occasions: Int = 0,
                       val syncAll: Boolean = false, val warned: Boolean = false)

    interface PendingStore {
        fun load(): Pending
        fun save(p: Pending)
        class InMemory(var value: Pending = Pending()) : PendingStore {
            override fun load() = value
            override fun save(p: Pending) { value = p }
        }
    }

    // the fields below are touched on the backup thread only
    private var pending: Pending? = null
    private var retryStep = 0
    private var retryScheduled = false
    /** The occasion (a call from outside, not a timed retry) already met a not-ready storage. */
    private var occasionCounted = false

    private fun p(): Pending = pending ?: store.load().also { pending = it }
    private fun set(n: Pending) { if (n != pending) { pending = n; store.save(n) } }

    /** A tour / plan was finished, saved, renamed, moved to or out of the recycle bin. */
    fun tourChanged(id: Long) = run.invoke(Runnable {
        occasionCounted = false
        if (!retryQueued()) { set(p().copy(ids = p().ids + id)); return@Runnable }
        when (val o = attempt { saveNow(id) }) {
            is Outcome.NotReady -> { set(p().copy(ids = p().ids + id)); notReady(o) }
            else -> Unit
        }
    })

    /** Recycle bin entries removed for good: their files go too. */
    fun purged(uuids: List<String>) {
        if (uuids.isEmpty()) return
        run.invoke(Runnable {
            when (val o = attempt { val b = backup(); uuids.forEach { b.purge(it) } }) {
                is Outcome.NotReady -> listener.waiting(o.reason, o.e, null) // the files stay in the folder: nothing lost
                else -> Unit
            }
        })
    }

    /** Writes every tour without a file in the current folder ([missingOnly]) or all again; [done] gets the count (-1 = failed). */
    fun syncAll(missingOnly: Boolean, done: ((Int) -> Unit)? = null) = run.invoke(Runnable {
        occasionCounted = false
        val userAsked = !missingOnly
        if (!retryQueued()) {
            if (userAsked) listener.failed(Txt.s(Res.string.backup_err_documents_not_ready), null)
            set(p().copy(syncAll = true)); done?.invoke(-1); return@Runnable
        }
        var n = 0
        when (val o = attempt { n = writeAll(missingOnly) }) {
            Outcome.Ok -> done?.invoke(n)
            Outcome.Failed -> done?.invoke(-1)
            is Outcome.NotReady -> {
                set(p().copy(syncAll = true))
                if (userAsked) listener.failed(o.reason, o.e) else notReady(o)
                done?.invoke(-1)
            }
        }
    })

    private fun writeAll(missingOnly: Boolean): Int {
        var n = 0
        val b = backup()
        for (t in source.all()) {
            if (t.state != "done" || t.uuid.isEmpty()) continue
            if (missingOnly && b.has(t.uuid, t.deletedMs != null)) continue
            saveTour(b, t); n++
        }
        if (n > 0) listener.saved("$n files", b.count())
        return n
    }

    /**
     * Writes the queued tours (and a queued sync of missing files); false when the storage is still not ready (the
     * rest stays queued). True when nothing is queued.
     */
    private fun retryQueued(): Boolean {
        val q = p()
        if (q.ids.isEmpty() && !q.syncAll) return true
        for (id in q.ids) {
            when (val o = attempt { saveNow(id) }) {
                is Outcome.NotReady -> { notReady(o); return false }
                else -> set(p().copy(ids = p().ids - id)) // written, gone, or a real failure (reported)
            }
        }
        if (p().syncAll) {
            when (val o = attempt { writeAll(missingOnly = true) }) {
                is Outcome.NotReady -> { notReady(o); return false }
                else -> set(p().copy(syncAll = false))
            }
        }
        return true
    }

    private fun notReady(o: Outcome.NotReady) {
        var q = p()
        val t = now()
        if (q.sinceMs == null) q = q.copy(sinceMs = t, occasions = 0, warned = false)
        if (!occasionCounted) { occasionCounted = true; q = q.copy(occasions = q.occasions + 1) }
        val keepsFailing = t - q.sinceMs!! >= WARN_AFTER_MS && q.occasions >= 2
        val delay = RETRY_DELAYS_MS.getOrNull(retryStep)
        if (keepsFailing && !q.warned) {
            q = q.copy(warned = true)
            set(q)
            listener.failed(o.reason, o.e)
        } else {
            set(q)
            listener.waiting(o.reason, o.e, if (retryScheduled) null else delay)
        }
        if (delay != null && !retryScheduled) {
            retryScheduled = true; retryStep++
            later(delay, Runnable { retryScheduled = false; retryQueued() })
        }
    }

    /** Written (or a real failure, reported): the storage is there – the not-ready count starts again. */
    private fun storageWorks() {
        retryStep = 0
        val q = p()
        if (q.sinceMs != null || q.occasions != 0 || q.warned) set(q.copy(sinceMs = null, occasions = 0, warned = false))
    }

    private fun saveNow(id: Long) {
        val t = source.tour(id) ?: return // deleted meanwhile
        if (t.state != "done" || t.uuid.isEmpty()) return // still recording: the finished tour is backed up
        val b = backup()
        listener.saved(saveTour(b, t), b.count())
    }

    private fun saveTour(b: TourBackup, t: Tour): String {
        val segs = source.segments(t.id)
        return b.save(BackupMeta.of(t, segs, now(), if (t.hasPlan) source.plan(t.id) else null), segs)
    }

    private sealed interface Outcome {
        object Ok : Outcome
        object Failed : Outcome
        class NotReady(val reason: String, val e: Throwable) : Outcome
    }

    private inline fun attempt(block: () -> Unit): Outcome = try {
        block(); storageWorks(); Outcome.Ok
    } catch (e: Exception) {
        if (BackupVolumes.isNotReady(e)) {
            // the one reason that says what to do (shown only when it keeps failing), whatever the folder's own text was
            Outcome.NotReady(Txt.s(Res.string.backup_err_documents_not_ready), e)
        } else {
            storageWorks()
            listener.failed(if (e is BackupException) e.reason else Txt.s(Res.string.backup_err_not_written), e)
            Outcome.Failed
        }
    }

    companion object {
        /** Quiet retries after a not-ready storage: 30 s, 2 min, 10 min, 30 min – then at the next backup / app start. */
        val RETRY_DELAYS_MS = listOf(30_000L, 120_000L, 600_000L, 1_800_000L)
        /** The user hears of a not-ready storage only after this long, and on a second occasion (start / backup). */
        const val WARN_AFTER_MS = 10 * 60_000L
    }
}

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
}

/**
 * When a backup file is written (JVM tested): after a tour is finished (state "done", never while it records),
 * after a plan is saved / overwritten, after a rename, when a tour goes to the recycle bin (file → Deleted/) or comes
 * back, and – at app start – for every tour that has no file in the current folder yet (first run with backups,
 * another folder chosen). All work runs on [run] (one background thread in the app): recording is never blocked.
 * Failures are reported to [listener] with a plain reason, never thrown.
 */
class BackupQueue(
    private val source: BackupSource,
    /** The current folder's backup; throws [BackupException] when there is none that can be written. */
    private val backup: () -> TourBackup,
    private val run: (Runnable) -> Unit,
    private val listener: Listener,
    private val now: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun saved(fileName: String, count: Int)
        fun failed(reason: String, e: Throwable?)
    }

    /** A tour / plan was finished, saved, renamed, moved to or out of the recycle bin. */
    fun tourChanged(id: Long) = run.invoke(Runnable { guarded { saveNow(id) } })

    /** Recycle bin entries removed for good: their files go too. */
    fun purged(uuids: List<String>) {
        if (uuids.isEmpty()) return
        run.invoke(Runnable { guarded { val b = backup(); uuids.forEach { b.purge(it) } } })
    }

    /** Writes every tour without a file in the current folder ([missingOnly]) or all again; [done] gets the count (-1 = failed). */
    fun syncAll(missingOnly: Boolean, done: ((Int) -> Unit)? = null) = run.invoke(Runnable {
        var n = 0
        val ok = guarded {
            val b = backup()
            for (t in source.all()) {
                if (t.state != "done" || t.uuid.isEmpty()) continue
                if (missingOnly && b.has(t.uuid)) continue
                saveTour(b, t); n++
            }
            if (n > 0) listener.saved("$n files", b.count())
        }
        done?.invoke(if (ok) n else -1)
    })

    private fun saveNow(id: Long) {
        val t = source.tour(id) ?: return // deleted meanwhile
        if (t.state != "done" || t.uuid.isEmpty()) return // still recording: the finished tour is backed up
        val b = backup()
        listener.saved(saveTour(b, t), b.count())
    }

    private fun saveTour(b: TourBackup, t: Tour): String {
        val segs = source.segments(t.id)
        return b.save(BackupMeta.of(t, segs, now()), segs)
    }

    private inline fun guarded(block: () -> Unit): Boolean = try { block(); true } catch (e: BackupException) {
        listener.failed(e.reason, e); false
    } catch (e: Exception) {
        listener.failed(Txt.s(Res.string.backup_err_not_written), e); false
    }
}

package com.onpath.app.backup

import com.onpath.app.data.TourKind
import com.onpath.strings.*

/** A backup file found in the picked folder: its entry plus where it lies (handle, file name). */
data class FoundBackup(val entry: BackupEntry, val handle: String, val fileName: String)

/** What a restore does (JVM tested): which files become tours, and the sentence the user reads. */
object BackupRestore {

    /**
     * Files to import: only OnPath backups, not in the recycle bin, not already on the phone (same uuid), and each
     * uuid once – the newest save wins (a tour backed up on two phones, or a copy "Name (2).gpx").
     */
    fun plan(found: List<FoundBackup>, existingUuids: Set<String>): List<FoundBackup> =
        found.filter { it.entry.meta.deletedMs == null && it.entry.meta.uuid !in existingUuids }
            .groupBy { it.entry.meta.uuid }
            .map { (_, same) -> same.maxWith(compareBy<FoundBackup>({ it.entry.meta.savedMs }, { it.entry.segments.sumOf { s -> s.size } })) }
            .sortedBy { it.entry.meta.createdMs }

    data class Result(val tours: Int, val plans: Int, val skipped: Int, val failed: Int)

    /** "23 tours and 5 plans restored." (+ already there / unreadable). */
    fun summary(r: Result): String {
        val main = when {
            r.tours == 0 && r.plans == 0 -> Txt.s(Res.string.backup_restore_none)
            else -> Txt.s(Res.string.backup_restore_main, Txt.p(Res.plurals.backup_n_tracks, r.tours, r.tours),
                Txt.p(Res.plurals.backup_n_routes, r.plans, r.plans))
        }
        val extra = buildList {
            if (r.skipped > 0) add(Txt.s(Res.string.backup_restore_skipped, r.skipped))
            if (r.failed > 0) add(Txt.s(Res.string.backup_restore_failed, r.failed))
        }
        return main + (if (extra.isEmpty()) "." else " (" + extra.joinToString(", ") + ").")
    }

    /** Planned routes count as plans; recorded and imported tracks as tours. */
    fun isPlan(k: TourKind) = k == TourKind.PLANNED
}

/** Recycle bin: deleted tours and plans stay restorable for [DAYS] days, then the app start removes them. */
object RecycleBin {
    const val DAYS = 30
    const val KEEP_MS = DAYS * 24L * 3600 * 1000

    fun expired(deletedMs: Long, nowMs: Long) = nowMs - deletedMs >= KEEP_MS

    /** Oldest deletion time that is still kept at [nowMs]. */
    fun cutoff(nowMs: Long) = nowMs - KEEP_MS

    /** Whole days until it is removed (0 = at the next app start). */
    fun daysLeft(deletedMs: Long, nowMs: Long): Int =
        (((deletedMs + KEEP_MS) - nowMs).coerceAtLeast(0) / (24L * 3600 * 1000)).toInt()
}

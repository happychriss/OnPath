package com.onpath.app.backup

import com.onpath.app.data.TourKind

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
        fun n(k: Int, one: String, many: String) = "$k ${if (k == 1) one else many}"
        val main = when {
            r.tours == 0 && r.plans == 0 -> "No tours or plans restored"
            else -> "${n(r.tours, "tour", "tours")} and ${n(r.plans, "plan", "plans")} restored"
        }
        val extra = buildList {
            if (r.skipped > 0) add("${r.skipped} already on this phone")
            if (r.failed > 0) add("${r.failed} could not be read")
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

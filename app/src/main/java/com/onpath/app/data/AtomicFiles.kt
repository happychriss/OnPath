package com.onpath.app.data

import com.onpath.app.debug.Problems
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces a file so that a reader always finds the old or the new one, never none (robust first: never lose a file).
 *
 * Not `File.renameTo`: newer desktop JDKs (25) delete the target first on Linux (unlink + rename – the unit tests saw a
 * file missing for a moment), and the old fallbacks at the call sites (delete the target, rename again) lost the file
 * when the second rename failed. Not `android.util.AtomicFile` either: Android only (no JVM tests) and it reads a
 * `.bak` instead of the file. `java.nio.file` is there from API 26 (minSdk).
 *
 * The files shared with the web planner (TeaVM: `routing/BRouterEngine.kt`, `routing/Rd5Parts.kt`,
 * `search/OfflineIndex.kt`) do not use this – TeaVM has no `File.toPath` / `AtomicMoveNotSupportedException`.
 */
object AtomicFiles {

    /**
     * The temp file's name base beside [target] (same folder = same volume, so the move is one rename). Each write makes
     * its own unique file from it ([uniqueTemp]) – field bug 2026-11-07: two writers of one file (two app screens, two
     * sync workers) shared one fixed temp file; the first moved it, the second found nothing ("NoSuchFileException
     * links.txt.tmp -> links.txt") and could have moved the other's half-written content.
     */
    fun tmpFor(target: File): File = File(target.parentFile, ".${target.name}.tmp")

    /** A fresh temp file for one write: `<base without .tmp>.<unique>.tmp` in [base]'s folder. */
    private fun uniqueTemp(base: File): File =
        File.createTempFile(base.name.removeSuffix(".tmp").padEnd(3, '_') + ".", ".tmp", base.parentFile)

    /** One lock per target file: writes of one file in this process run one after the other. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun lockFor(target: File): Any = locks.getOrPut(target.absoluteFile.normalize().path) { Any() }

    /** Temp files of writes a crash interrupted are of no use after this long (a write in progress is never this old). */
    const val STALE_TMP_MS = 3_600_000L

    /**
     * Removes the temp files a crash or kill left in [dir]: ours only (`*.tmp`, also the old fixed names), older than
     * [STALE_TMP_MS]. Returns how many went; quiet (traces only).
     */
    fun cleanStaleTemps(dir: File, now: Long = System.currentTimeMillis()): Int {
        var n = 0
        dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") && now - f.lastModified() > STALE_TMP_MS }.orEmpty().forEach {
            if (it.delete()) n++ else Problems.trace("data", "Old temp file ${it.name} not removed")
        }
        if (n > 0) Problems.trace("data", "Removed $n old temp file(s) in ${dir.name} (writes a crash interrupted)")
        return n
    }

    /**
     * Moves [tmp] over [target] in one step (rename(2)). Never deletes [target] first. Throws on failure; then [tmp]
     * stays (the new content is not lost) and [target] is unchanged – the caller reports it. [quiet]: for `debug/`
     * itself (Problems, EventLog), which must not report into Problems.
     */
    @Throws(IOException::class)
    fun replace(tmp: File, target: File, quiet: Boolean = false) {
        val from = tmp.toPath()
        val to = target.toPath()
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            // only when tmp is on another volume (never beside the target): the JDK replaces as well as it can
            if (!quiet) Problems.warn("data", "${target.name}: replaced without an atomic move (temp file on another volume)", e)
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Writes [target] through a fresh temp file named after [tmp] ([uniqueTemp]), one write of [target] at a time in this
     * process ([lockFor]): [block] fills the temp file, it is flushed to disk ([sync]), then [replace]d over the target.
     * Throws on failure; a half-written temp file is deleted, a complete one that could not be moved stays.
     */
    @Throws(IOException::class)
    fun write(target: File, tmp: File = tmpFor(target), sync: Boolean = true, quiet: Boolean = false,
              block: (FileOutputStream) -> Unit) {
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs() && !it.isDirectory) throw IOException("folder of ${target.name} not created") }
        synchronized(lockFor(target)) {
            val own = uniqueTemp(tmp) // never shared with another write of the same file
            try {
                FileOutputStream(own).use { out ->
                    block(out)
                    out.flush()
                    if (sync) out.fd.sync()
                }
            } catch (e: Throwable) {
                own.delete() // incomplete: of no use, frees the space
                throw e
            }
            replace(own, target, quiet)
        }
    }

    @Throws(IOException::class)
    fun writeText(target: File, text: String, tmp: File = tmpFor(target), sync: Boolean = true, quiet: Boolean = false) =
        write(target, tmp, sync, quiet) { it.write(text.toByteArray(Charsets.UTF_8)) }

    @Throws(IOException::class)
    fun writeBytes(target: File, bytes: ByteArray, tmp: File = tmpFor(target), sync: Boolean = true) =
        write(target, tmp, sync) { it.write(bytes) }
}

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

    /** The temp file beside [target] (same folder = same volume, so the move is one rename). */
    fun tmpFor(target: File): File = File(target.parentFile, ".${target.name}.tmp")

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
     * Writes [target] through [tmp]: [block] fills the temp file, it is flushed to disk ([sync]), then [replace]d over
     * the target. Throws on failure; a half-written temp file is deleted, a complete one that could not be moved stays.
     */
    @Throws(IOException::class)
    fun write(target: File, tmp: File = tmpFor(target), sync: Boolean = true, quiet: Boolean = false,
              block: (FileOutputStream) -> Unit) {
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs() && !it.isDirectory) throw IOException("folder of ${target.name} not created") }
        try {
            FileOutputStream(tmp).use { out ->
                block(out)
                out.flush()
                if (sync) out.fd.sync()
            }
        } catch (e: Throwable) {
            tmp.delete() // incomplete: of no use, frees the space
            throw e
        }
        replace(tmp, target, quiet)
    }

    @Throws(IOException::class)
    fun writeText(target: File, text: String, tmp: File = tmpFor(target), sync: Boolean = true, quiet: Boolean = false) =
        write(target, tmp, sync, quiet) { it.write(text.toByteArray(Charsets.UTF_8)) }

    @Throws(IOException::class)
    fun writeBytes(target: File, bytes: ByteArray, tmp: File = tmpFor(target), sync: Boolean = true) =
        write(target, tmp, sync) { it.write(bytes) }
}

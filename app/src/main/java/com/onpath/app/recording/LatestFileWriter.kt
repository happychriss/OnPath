package com.onpath.app.recording

import com.onpath.app.data.AtomicFiles
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Writes one small state file (the trip file) on [exec], not on the caller's thread: the newest content wins, writes
 * and a [delete] happen in the order they were asked for.
 *
 * Field 2026-11-07 ("Main thread blocked 695 ms" in the recording tick): the trip file was written with an fsync on the
 * main thread every 30 s; on a busy phone one fsync can take hundreds of ms. [exec] must run one task at a time.
 */
class LatestFileWriter(
    private val file: File,
    private val exec: Executor,
    private val onError: (Exception) -> Unit,
) {
    private val pending = AtomicReference<String?>(null)

    /** Writes [text] soon (on [exec]); a newer text before that replaces it. Never blocks, never throws. */
    fun write(text: String) {
        if (pending.getAndSet(text) == null) submit { drain() }
    }

    /** Removes the file after every write asked for before; a waiting write is dropped. */
    fun delete() {
        pending.set(null)
        submit {
            pending.set(null)
            if (file.exists() && !file.delete()) onError(java.io.IOException("${file.name} not deleted"))
        }
    }

    /**
     * Writes [text] and waits for it (at most [timeoutMs]) – the service is going away and the process may follow.
     * True = on disk.
     */
    fun writeAndWait(text: String, timeoutMs: Long = 2_000): Boolean {
        pending.set(null)
        val task = FutureTask { writeNow(text) }
        return try {
            exec.execute(task)
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            onError(e); false
        }
    }

    private fun drain() {
        val text = pending.getAndSet(null) ?: return // expected: dropped by delete() or written by writeAndWait()
        writeNow(text)
    }

    private fun writeNow(text: String): Boolean = try {
        AtomicFiles.writeText(file, text); true
    } catch (e: Exception) {
        onError(e); false
    }

    private fun submit(block: () -> Unit) {
        try { exec.execute(block) } catch (e: Exception) {
            // the executor is shut down (service gone): written here so nothing is lost
            onError(e)
            block()
        }
    }
}

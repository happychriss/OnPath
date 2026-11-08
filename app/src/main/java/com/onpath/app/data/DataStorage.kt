package com.onpath.app.data

import android.content.Context
import com.onpath.app.debug.Problems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Whether the storage holding the offline data is there right now; [whenReady] runs a block once it is. */
interface StorageGate {
    fun ready(): Boolean
    /** Runs [block] once the storage is ready: at once when it is, else later on a background thread. */
    fun whenReady(block: () -> Unit)

    companion object {
        /** Always there (tests, internal folders). */
        val ALWAYS = object : StorageGate {
            override fun ready() = true
            override fun whenReady(block: () -> Unit) = block()
        }
    }
}

/**
 * Where the offline data lives (map cells, styles, routing parts, the search's address files – `…/Android/data/
 * com.onpath.app/files/onpath`) and whether that shared storage is there yet.
 *
 * Field 2026-11-08 (Galaxy S10, Android 12, the app started right after a phone restart): for about a minute Android
 * has no external files folder for the app (`Failed to ensure /dev/null/Android/data/com.onpath.app/files`,
 * `getExternalFilesDir` = null) or the folder cannot be read yet. Before, the data folder then fell back to the app's
 * internal folder for the whole process – an empty map store, routing parts and map areas loaded again into the wrong
 * place. Now:
 * - the folder is chosen once per process ([dir]): the external one when Android gives it, else the one remembered
 *   from the last start ([remembered]), else the standard path of the primary storage – never silently another place;
 * - [ready] says whether it can be read now; until then the map store, the routing loader and the search wait
 *   ([whenReady], checked every [recheckMs]) and nothing is deleted, rewritten or downloaded because files seem missing.
 *
 * Problems: INFO when the storage is not ready at start and when it comes back, WARN once when it stays away longer
 * than [warnAfterMs]. Pure apart from the files; unit-tested (`DataStorageTest`).
 */
class DataStorage(
    /** Android's external files folder (`getExternalFilesDir(null)`); null or `/dev/null/…` while not ready. */
    private val external: () -> File?,
    /** The data folder of the last start where the storage was ready (a small file in internal storage). */
    private val remembered: Remembered,
    /** Standard external files folder of the primary storage (used when nothing is remembered). */
    private val standard: () -> File?,
    /** Internal files folder: the last resort when there is no external storage at all. */
    private val internal: File,
    /** Polls [ready] for [whenReady] blocks; null = only [poll] (tests). */
    private val scope: CoroutineScope? = null,
    private val recheckMs: Long = RECHECK_MS,
    private val warnAfterMs: Long = WARN_AFTER_MS,
    private val now: () -> Long = System::currentTimeMillis,
) : StorageGate {

    interface Remembered {
        fun read(): String?
        fun write(path: String)
    }

    /** A remembered path in [file] (one line); a failure only means the standard path is used next time. */
    class RememberedFile(private val file: File) : Remembered {
        override fun read(): String? = try { if (file.isFile) file.readText().trim().ifEmpty { null } else null } catch (e: Exception) {
            Problems.warn("data", "Remembered data folder unreadable; the standard folder is used", e); null
        }
        override fun write(path: String) {
            try { AtomicFiles.writeText(file, path) } catch (e: Exception) { Problems.warn("data", "Data folder not remembered", e) }
        }
    }

    /** The data folder for this process (chosen once; every store of the process uses the same one). */
    val dir: File by lazy { choose() }

    /** The data folder was chosen while the storage was not ready (remembered / standard path). */
    @Volatile var chosenWhileNotReady = false
        private set

    @Volatile private var isReady = false
    @Volatile private var notReadySince = 0L
    @Volatile private var warned = false
    private val waiting = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var polling = false

    private fun choose(): File {
        val ext = external()
        if (usable(ext)) {
            val d = File(ext!!, DIR)
            if (remembered.read() != d.absolutePath) remembered.write(d.absolutePath)
            return d
        }
        chosenWhileNotReady = true
        val r = remembered.read()?.let { File(it) }?.takeIf { usable(it) }
        val s = standard()?.takeIf { usable(it) }?.let { File(it, DIR) }
        val d = r ?: s ?: File(internal, DIR)
        Problems.info("data", "Storage not ready at start (external files folder: ${ext?.path ?: "none"}); data folder ${d.path}, " +
            "waiting for it")
        return d
    }

    /**
     * True when the data folder can be read now. Once true it stays true for the process (cheap; asked on the main
     * thread). Never throws.
     */
    override fun ready(): Boolean {
        if (isReady) return true
        val d = dir
        val ok = try {
            val ext = external()
            val extOk = usable(ext) || d.path.startsWith(internal.path)
            if (extOk && ext != null && File(ext, DIR).absolutePath != d.absolutePath && usable(ext))
                // the app's folder moved since the last start (another storage): this process stays with [d]
                Problems.warn("data", "Data folder is ${File(ext, DIR).path} now, this run uses ${d.path} (until the app restarts)")
            extOk && (d.isDirectory || d.mkdirs()) && d.list() != null
        } catch (e: Exception) {
            // expected: the storage is not there yet (SecurityException / IOException from the platform)
            Problems.trace("data", "Storage check failed: ${e.javaClass.simpleName} ${e.message}")
            false
        }
        val t = now()
        if (ok) {
            isReady = true
            if (notReadySince > 0) Problems.info("data", "Storage ready after ${(t - notReadySince) / 1000} s; data loads normally")
            else if (chosenWhileNotReady) Problems.info("data", "Storage ready; data loads normally")
            return true
        }
        if (notReadySince == 0L) {
            notReadySince = t
            Problems.info("data", "Storage not ready yet (${d.path}); waiting, nothing is loaded or removed meanwhile")
        } else if (!warned && t - notReadySince >= warnAfterMs) {
            warned = true
            Problems.warn("data", "Storage still not ready after ${(t - notReadySince) / 1000} s (${d.path}); maps and routing data wait")
        }
        return false
    }

    override fun whenReady(block: () -> Unit) {
        if (ready()) { run(block); return }
        waiting += block
        startPolling()
        poll() // ready meanwhile: run it now
    }

    /** Checks once; when ready, runs (and forgets) the waiting blocks. Returns [ready]. */
    fun poll(): Boolean {
        if (!ready()) return false
        while (waiting.isNotEmpty()) {
            val b = waiting.removeAt(0)
            run(b)
        }
        return true
    }

    private fun run(block: () -> Unit) {
        try { block() } catch (e: Exception) { Problems.error("data", "Work after the storage became ready failed", e) }
    }

    private fun startPolling() {
        val s = scope ?: return
        synchronized(this) { if (polling) return; polling = true }
        s.launch {
            try { while (!poll()) delay(recheckMs) } finally { synchronized(this@DataStorage) { polling = false } }
        }
    }

    companion object {
        const val DIR = "onpath"
        const val RECHECK_MS = 3_000L
        const val WARN_AFTER_MS = 60_000L
        /** File in internal storage with the last data folder path. */
        const val REMEMBERED_FILE = "data-dir.txt"

        /** A folder Android gives for a storage that is there: not null, not under `/dev/null` (a volume not ready). */
        fun usable(f: File?): Boolean = f != null && f.path.isNotEmpty() && f.path != "/dev/null" && !f.path.startsWith("/dev/null/")

        @Volatile private var instance: DataStorage? = null

        /** The one [DataStorage] of the app process. */
        fun get(context: Context): DataStorage = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): DataStorage = DataStorage(
            external = { app.getExternalFilesDir(null) },
            remembered = RememberedFile(File(app.filesDir, REMEMBERED_FILE)),
            // primary shared storage of this user (uid / 100000 = user id), as Android names it on every phone since 6
            standard = { File("/storage/emulated/${android.os.Process.myUid() / 100_000}/Android/data/${app.packageName}/files") },
            internal = app.filesDir,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.onpath.app.debug.problemsHandler("data", "Storage check failed")),
        )
    }
}

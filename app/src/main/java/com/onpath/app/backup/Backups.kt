package com.onpath.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.onpath.app.data.Tour
import com.onpath.app.data.TourDb
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tour & plan backup of the app (owner 2026-10-21, docs/core-features.md C11): the hooks call [tourChanged] /
 * [purgeAndSync]; files go to Documents/OnPath or the folder chosen in Settings → Backup. One background thread –
 * recording and the UI never wait for it. A failure is reported to Problems and kept as a notice ([failure]) until
 * the user has seen it.
 */
object Backups {
    const val KEY_DOCUMENTS = "documents"
    private const val PREFS = "backup" // own file: not in Android's backup (folder access does not survive a reinstall)
    const val INDEX_FILE = "backup-index.json"

    fun treeKey(tree: Uri) = "tree:$tree"

    data class Status(val folder: String, val lastMs: Long, val count: Int, val lastError: String?, val custom: Boolean)

    private val _status = MutableStateFlow(Status("Documents/OnPath", 0L, 0, null, false))
    val status: StateFlow<Status> = _status
    /** "Backup to Documents/OnPath failed – <reason>": shown until OK (owner: no self-dismissing messages). */
    val failure = MutableStateFlow<String?>(null)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "onpath-backup").apply { isDaemon = true } }
    @Volatile private var app: Context? = null
    @Volatile private var index: BackupIndex? = null
    @Volatile private var queue: BackupQueue? = null

    private fun ctx(context: Context): Context = app ?: synchronized(this) {
        app ?: context.applicationContext.also { a ->
            app = a
            index = BackupIndex(File(a.filesDir, INDEX_FILE))
            val p = prefs(a)
            failure.value = p.getString("pendingFailure", null)
            queue = BackupQueue(DbSource(TourDb.get(a)), { TourBackup(store(a), index!!) }, { executor.execute(it) }, Listener(a))
            refreshStatus(a)
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The folder backups go to now. */
    internal fun store(c: Context): BackupStore {
        val tree = prefs(c).getString("tree", null)?.let(Uri::parse)
        return when {
            // the folder's name was read once when it was chosen (a cloud provider may answer slowly)
            tree != null -> TreeBackupStore(c, tree, prefs(c).getString("treeLabel", null) ?: "chosen folder")
            Build.VERSION.SDK_INT >= 29 -> MediaStoreBackupStore(c)
            else -> LegacyBackupStore(c)
        }
    }

    fun init(context: Context) { ctx(context) }

    /** A tour / plan was finished, saved, renamed, moved to or from the recycle bin. Never blocks, never throws. */
    fun tourChanged(context: Context, id: Long) {
        try { ctx(context); queue?.tourChanged(id) } catch (e: Exception) { Problems.warn("backup", "Backup not started", e) }
    }

    /** Recycle bin → Delete now: the files of these tours go too. */
    fun purge(context: Context, uuids: List<String>) {
        try { ctx(context); queue?.purged(uuids.filter { it.isNotEmpty() }) } catch (e: Exception) { Problems.warn("backup", "Backup file not removed", e) }
    }

    /** App start: empty the recycle bin of entries older than 30 days, then back up tours that have no file yet. */
    fun purgeAndSync(context: Context) {
        val a = ctx(context)
        executor.execute {
            try {
                val gone = TourDb.get(a).purgeDeleted(RecycleBin.cutoff(System.currentTimeMillis()))
                if (gone.isNotEmpty()) Problems.info("backup", "Recycle bin: ${gone.size} entries older than ${RecycleBin.DAYS} days removed")
                queue?.purged(gone)
            } catch (e: Exception) { Problems.warn("backup", "Recycle bin could not be emptied", e) }
            queue?.syncAll(missingOnly = true)
        }
    }

    /** Settings → Backup → "Back up all now". */
    fun backupAll(context: Context, done: (Int) -> Unit = {}) { ctx(context); queue?.syncAll(missingOnly = false, done) }

    /** Settings → Backup → choose a folder (null = back to Documents/OnPath). Writes every tour there. */
    fun setFolder(context: Context, tree: Uri?) {
        val a = ctx(context)
        val old = prefs(a).getString("tree", null)
        if (tree != null) try {
            a.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { Problems.warn("backup", "Folder access could not be kept – it may stop after a restart", e) }
        if (old != null && old != tree?.toString()) try {
            a.contentResolver.releasePersistableUriPermission(Uri.parse(old),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { Problems.info("backup", "Old folder access not released", e) }
        executor.execute {
            val label = tree?.let { BackupFolder.label(a, it) }
            prefs(a).edit().putString("tree", tree?.toString()).putString("treeLabel", label).remove("lastError").commit()
            refreshStatus(a)
        }
        queue?.syncAll(missingOnly = true)
    }

    /** The user saw the failure notice. */
    fun failureSeen(context: Context) {
        failure.value = null
        prefs(ctx(context)).edit().remove("pendingFailure").apply()
    }

    private class Listener(private val a: Context) : BackupQueue.Listener {
        override fun saved(fileName: String, count: Int) {
            prefs(a).edit().putLong("lastMs", System.currentTimeMillis()).remove("lastError").apply()
            refreshStatus(a)
        }

        override fun failed(reason: String, e: Throwable?) {
            val folder = try { store(a).label } catch (x: Exception) { Problems.info("backup", "Backup folder name unknown", x); "the backup folder" }
            val text = "Backup to $folder failed – $reason"
            Problems.warn("backup", text, e)
            prefs(a).edit().putString("lastError", reason).putString("pendingFailure", text).apply()
            failure.value = text
            refreshStatus(a)
        }
    }

    private fun refreshStatus(a: Context) {
        val p = prefs(a)
        val label = try { store(a).label } catch (e: Exception) { Problems.info("backup", "Backup folder name unknown", e); "?" }
        val key = try { store(a).key } catch (e: Exception) { Problems.info("backup", "Backup folder unknown", e); KEY_DOCUMENTS }
        _status.value = Status(label, p.getLong("lastMs", 0L), index?.count(key) ?: 0, p.getString("lastError", null),
            p.getString("tree", null) != null)
    }

    // ------------------------------------------------------------------------------------------------ restore

    /**
     * Restores every OnPath backup in the picked folder (tours and plans, recycle bin not), skipping tours that are
     * already on the phone. Runs on the caller's (background) thread; [progress] gets a line like "Reading backup files 3 / 40".
     */
    fun restore(context: Context, tree: Uri, progress: (String) -> Unit): BackupRestore.Result {
        val a = ctx(context)
        try {
            a.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { Problems.info("backup", "Restore folder access not kept (read once)", e) }
        val files = BackupFolder.gpxFiles(a.contentResolver, tree)
        var failed = 0
        val found = ArrayList<FoundBackup>()
        files.forEachIndexed { i, d ->
            progress("Reading backup files ${i + 1} / ${files.size}")
            try {
                if (d.size > com.onpath.app.gpx.GpxParser.MAX_BYTES) { failed++; return@forEachIndexed }
                val text = a.contentResolver.openInputStream(d.uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: run { failed++; return@forEachIndexed }
                if (!BackupCodec.hasRecord(text)) return@forEachIndexed // a GPX from elsewhere: not ours, left alone
                val e = BackupCodec.decode(text, d.name)
                if (e == null) failed++ else found += FoundBackup(e, d.uri.toString(), d.name)
            } catch (e: Exception) {
                failed++; Problems.warn("backup", "Backup file ${d.name} could not be read", e)
            }
        }
        val db = TourDb.get(a)
        val todo = BackupRestore.plan(found, db.allUuids())
        val skipped = found.map { it.entry.meta }.filter { it.deletedMs == null }.map { it.uuid }.distinct().size - todo.size
        val current = store(a)
        val adopt = (current.key == KEY_DOCUMENTS && BackupFolder.isDocumentsOnPath(tree)) || current.key == treeKey(tree)
        var tours = 0; var plans = 0
        todo.forEachIndexed { i, f ->
            progress("Restoring ${i + 1} / ${todo.size}")
            val m = f.entry.meta
            try {
                db.insertTour(m.kind, m.name, m.activity, m.sac, m.distanceM, m.ascentM, m.descentM, m.durationS,
                    f.entry.segments, m.waypoints, "done", m.pinned, m.uuid, m.createdMs)
                if (BackupRestore.isPlan(m.kind)) plans++ else tours++
                if (adopt && f.fileName.indexOf('/') < 0) TourBackup(current, index!!).adopt(m.uuid, f.handle, f.fileName)
            } catch (e: Exception) { failed++; Problems.error("backup", "Restoring ${f.fileName} failed", e) }
        }
        refreshStatus(a)
        val r = BackupRestore.Result(tours, plans, skipped.coerceAtLeast(0), failed)
        Problems.info("backup", "Restore: ${BackupRestore.summary(r)}")
        return r
    }

    /**
     * Forgets which file belongs to which tour and the folder settings – what a reinstall does (emulator test of the
     * restore flow, CoreBackupE2ETest). The files stay in the folder.
     */
    fun forgetLikeReinstall(context: Context) {
        val a = ctx(context)
        File(a.filesDir, INDEX_FILE).delete()
        prefs(a).edit().clear().putBoolean("restoreOffered", true).apply()
        index = BackupIndex(File(a.filesDir, INDEX_FILE))
        failure.value = null
        refreshStatus(a)
    }

    /** Restore was offered once on an empty database (first start after a reinstall / on a new phone). */
    fun restoreOffered(context: Context): Boolean = prefs(ctx(context)).getBoolean("restoreOffered", false)
    fun setRestoreOffered(context: Context) { prefs(ctx(context)).edit().putBoolean("restoreOffered", true).apply() }

    // ------------------------------------------------------------------------------------------------ export all

    /** My routes → Export all: one ZIP with a GPX per tour / plan (with the backup record – restorable). */
    fun exportZip(context: Context, dir: File, dateText: String): File {
        val db = TourDb.get(context)
        val out = File(dir.apply { mkdirs() }, "OnPath tours $dateText.zip")
        val names = ArrayList<String>()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (t in db.listTours().filter { it.state == "done" }) {
                val segs = db.loadSegments(t.id)
                val meta = BackupMeta.of(t, segs)
                val name = BackupNames.fileName(meta, names)
                names += name
                zip.putNextEntry(ZipEntry(name))
                zip.write(BackupCodec.encode(meta, segs).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        if (names.isEmpty()) { out.delete(); throw BackupException("nothing to export yet") }
        return out
    }

    private class DbSource(private val db: TourDb) : BackupSource {
        override fun tour(id: Long): Tour? = db.getTour(id)
        override fun segments(id: Long): List<List<TrackPoint>> = db.loadSegments(id)
        override fun all(): List<Tour> = db.listTours() + db.listDeleted()
    }
}

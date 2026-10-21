package com.onpath.app.backup

import com.onpath.app.debug.Problems
import com.onpath.app.gpx.TrackPoint
import org.json.JSONObject
import java.io.File
import java.time.ZoneId

/** A backup failed in a way the user must hear about ("Backup to Documents/OnPath failed – <reason>"). */
class BackupException(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

/**
 * Where backup files go: Documents/OnPath (MediaStore / legacy folder) or a folder the user picked (SAF tree).
 * Files are addressed by a handle (a content:// or file:// URI string) returned on [create].
 * Throws [BackupException] with a plain reason when the folder cannot be written.
 */
interface BackupStore {
    /** Stable id of the folder ("documents" or "tree:<uri>"): index entries of another folder do not count. */
    val key: String
    /** Shown to the user: "Documents/OnPath", or the picked folder's name. */
    val label: String
    /** Writes a new file in the folder (or its `Deleted/` subfolder). The folder may change the name (duplicate). */
    fun create(fileName: String, deleted: Boolean, bytes: ByteArray): Created
    /** Replaces the content; false when the file is gone (deleted by the user, app reinstalled). */
    fun overwrite(handle: String, bytes: ByteArray): Boolean
    /** Renames in place: the new handle, or null when the folder cannot rename it (then a new file is written). */
    fun rename(handle: String, fileName: String): String?
    /** Removes the file; false when it could not be removed (gone already counts as removed). */
    fun delete(handle: String): Boolean

    data class Created(val handle: String, val fileName: String)
}

/**
 * Which backup file belongs to which tour (uuid → folder, handle, file name, in Deleted/). Kept in the app's files
 * (not in the backup itself): the next save of the same tour overwrites its file instead of writing a second one.
 */
class BackupIndex(private val file: File?) {
    data class Entry(val store: String, val handle: String, val fileName: String, val deleted: Boolean)

    private val map = LinkedHashMap<String, Entry>()

    init { load() }

    @Synchronized fun get(uuid: String): Entry? = map[uuid]
    @Synchronized fun all(): Map<String, Entry> = LinkedHashMap(map)
    @Synchronized fun put(uuid: String, e: Entry) { map[uuid] = e; save() }
    @Synchronized fun remove(uuid: String) { if (map.remove(uuid) != null) save() }

    /** File names used in [store]'s folder (or its Deleted/ subfolder) by entries other than [except]. */
    @Synchronized fun namesInUse(store: String, deleted: Boolean, except: String): List<String> =
        map.filter { (u, e) -> u != except && e.store == store && e.deleted == deleted }.map { it.value.fileName }

    @Synchronized fun count(store: String, deleted: Boolean = false) = map.values.count { it.store == store && it.deleted == deleted }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                map[k] = Entry(e.getString("store"), e.getString("handle"), e.getString("file"), e.optBoolean("deleted"))
            }
        } catch (e: Exception) {
            // the files are still in the folder; the next save writes each tour again (a duplicate at worst)
            Problems.warn("backup", "Backup index unreadable – files are written again on the next backup", e)
            map.clear()
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            val o = JSONObject()
            map.forEach { (k, e) ->
                o.put(k, JSONObject().put("store", e.store).put("handle", e.handle).put("file", e.fileName).put("deleted", e.deleted))
            }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("rename failed") }
        } catch (e: Exception) {
            Problems.warn("backup", "Backup index not saved – a tour may be written twice next time", e)
        }
    }
}

/**
 * The backup rules, independent of Android (JVM tested): one file per tour / plan, named `YYYY-MM-DD Name.gpx`
 * (unique in its folder); saving again (re-save, rename, finished) overwrites the same file, a new name renames it;
 * the recycle bin moves it to `Deleted/`, restoring moves it back, the purge removes it.
 */
class TourBackup(private val store: BackupStore, private val index: BackupIndex, private val zone: ZoneId = ZoneId.systemDefault()) {

    /** Writes / updates the file of [meta]; returns the file name. Throws [BackupException]. */
    fun save(meta: BackupMeta, segments: List<List<TrackPoint>>): String {
        val bytes = BackupCodec.encode(meta, segments).toByteArray(Charsets.UTF_8)
        val deleted = meta.deletedMs != null
        val want = BackupNames.fileName(meta, index.namesInUse(store.key, deleted, meta.uuid), zone)
        val old = index.get(meta.uuid)?.takeIf { it.store == store.key }
        var result: BackupIndex.Entry? = null
        if (old != null && old.deleted == deleted) {
            var handle = old.handle
            var name = old.fileName
            if (old.fileName != want) store.rename(old.handle, want)?.let { handle = it; name = want }
            if (store.overwrite(handle, bytes)) {
                result = BackupIndex.Entry(store.key, handle, name, deleted)
                // could not rename (e.g. not our file any more): write under the new name, drop the old one
                if (name != want) result = null
            }
        }
        if (result == null) {
            val c = store.create(want, deleted, bytes)
            result = BackupIndex.Entry(store.key, c.handle, c.fileName, deleted)
            // moved (bin ⇄ folder) or renamed by writing anew: the old file goes, so the folder holds it once
            if (old != null && old.handle != c.handle && !store.delete(old.handle))
                Problems.warn("backup", "Old backup file ${old.fileName} could not be removed (it stays in the folder)")
        }
        index.put(meta.uuid, result)
        return result.fileName
    }

    /** Recycle bin emptied for [uuid]: its file is removed for good. */
    fun purge(uuid: String) {
        val e = index.get(uuid) ?: return
        if (e.store == store.key && !store.delete(e.handle))
            Problems.warn("backup", "Backup file ${e.fileName} of an emptied tour could not be removed")
        index.remove(uuid)
    }

    /** Has a current file in this folder. */
    fun has(uuid: String) = index.get(uuid)?.store == store.key

    /** Restored from this folder: remember the files, so the next save overwrites them instead of writing a copy. */
    fun adopt(uuid: String, handle: String, fileName: String) {
        if (index.get(uuid)?.store == store.key) return
        index.put(uuid, BackupIndex.Entry(store.key, handle, fileName, false))
    }

    fun count() = index.count(store.key)
}

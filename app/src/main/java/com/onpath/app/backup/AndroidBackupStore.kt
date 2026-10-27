package com.onpath.app.backup

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.onpath.app.debug.Problems
import java.io.File
import java.io.FileNotFoundException

/**
 * Operations on one backup file, whatever folder it lies in – a MediaStore entry (Documents/OnPath on Android 10+),
 * a document of a picked folder (SAF) or a plain file (Android 8–9). Handles are URI strings.
 */
internal class FileOps(private val resolver: ContentResolver) {

    fun overwrite(handle: String, bytes: ByteArray): Boolean {
        val uri = Uri.parse(handle)
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return false)
            if (!f.isFile) return false
            writeFile(f, bytes); return true
        }
        return try {
            val out = try { resolver.openOutputStream(uri, "wt") } catch (e: IllegalArgumentException) {
                // expected: some providers (cloud folders) have no "wt" – "w" truncates there too
                Problems.info("backup", "Folder does not support mode wt, using w", e); resolver.openOutputStream(uri, "w")
            } ?: return false
            out.use { it.write(bytes) }
            true
        } catch (e: FileNotFoundException) {
            Problems.info("backup", "Backup file gone (deleted in the folder) – written anew", e); false
        } catch (e: SecurityException) {
            // expected after a reinstall: the old file is no longer ours
            Problems.info("backup", "Backup file not writable any more (app reinstalled?) – written anew", e); false
        } catch (e: IllegalStateException) {
            Problems.info("backup", "Backup file gone – written anew", e); false
        }
    }

    fun rename(handle: String, fileName: String): String? = try {
        val uri = Uri.parse(handle)
        when {
            uri.scheme == "file" -> {
                val f = File(uri.path!!)
                val to = File(f.parentFile, fileName)
                if (!to.exists() && f.renameTo(to)) Uri.fromFile(to).toString() else null
            }
            uri.authority == MediaStore.AUTHORITY -> {
                val n = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName) }, null, null)
                if (n > 0 && displayName(uri) == fileName) handle else null
            }
            else -> DocumentsContract.renameDocument(resolver, uri, fileName)?.toString()
        }
    } catch (e: Exception) {
        Problems.info("backup", "Backup file not renamed in place – written under the new name", e); null
    }

    fun delete(handle: String): Boolean = try {
        val uri = Uri.parse(handle)
        when {
            uri.scheme == "file" -> File(uri.path!!).let { !it.exists() || it.delete() }
            uri.authority == MediaStore.AUTHORITY -> { resolver.delete(uri, null, null); true }
            else -> DocumentsContract.deleteDocument(resolver, uri)
        }
    } catch (e: FileNotFoundException) {
        Problems.info("backup", "Backup file already gone", e); true
    } catch (e: Exception) {
        Problems.warn("backup", "Backup file could not be removed", e); false
    }

    fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { Problems.info("backup", "Backup file name not readable", e); null }

    companion object {
        fun writeFile(f: File, bytes: ByteArray) {
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); throw java.io.IOException("could not write ${f.name}") } }
        }
    }
}

/** Android 10+: Documents/OnPath through MediaStore – no permission needed for the app's own files. */
internal class MediaStoreBackupStore(context: Context) : BackupStore {
    private val resolver = context.contentResolver
    private val ops = FileOps(resolver)
    override val key = Backups.KEY_DOCUMENTS
    override val label = "Documents/OnPath"

    override fun create(fileName: String, deleted: Boolean, bytes: ByteArray): BackupStore.Created {
        if (Build.VERSION.SDK_INT < 29) throw BackupException("needs Android 10")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            // generic type: MediaStore keeps the ".gpx" name (a type it does not know could get another extension)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/OnPath/" + if (deleted) "Deleted/" else "")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try { resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) } catch (e: Exception) {
            throw BackupException("the Documents folder cannot be written", e)
        } ?: throw BackupException("the Documents folder cannot be written (storage not available)")
        try {
            (resolver.openOutputStream(uri, "w") ?: throw java.io.IOException("no output stream")).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (d: Exception) { Problems.info("backup", "Half-written backup file not removed", d) }
            throw BackupException(if (e.message?.contains("ENOSPC") == true) "storage full" else "the file could not be written – the phone's storage may be full", e)
        }
        return BackupStore.Created(uri.toString(), ops.displayName(uri) ?: fileName)
    }

    override fun overwrite(handle: String, bytes: ByteArray) = ops.overwrite(handle, bytes)
    override fun rename(handle: String, fileName: String) = ops.rename(handle, fileName)
    override fun delete(handle: String) = ops.delete(handle)
}

/** Android 8–9: Documents/OnPath as a plain folder; Android needs the storage permission for it there. */
internal class LegacyBackupStore(private val context: Context) : BackupStore {
    private val ops = FileOps(context.contentResolver)
    override val key = Backups.KEY_DOCUMENTS
    override val label = "Documents/OnPath"

    @Suppress("DEPRECATION")
    private fun dir(deleted: Boolean): File {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            throw BackupException("storage access not allowed – Settings → Backup → Allow")
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) throw BackupException("storage not available")
        val d = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "OnPath"), if (deleted) "Deleted" else "")
        if (!d.isDirectory && !d.mkdirs()) throw BackupException("the backup folder cannot be created")
        return d
    }

    override fun create(fileName: String, deleted: Boolean, bytes: ByteArray): BackupStore.Created {
        val d = dir(deleted)
        var f = File(d, fileName)
        var n = 1
        while (f.exists()) f = File(d, fileName.removeSuffix(".gpx") + " (${n++}).gpx") // a file from an earlier install
        try { FileOps.writeFile(f, bytes) } catch (e: Exception) {
            throw BackupException(if (d.usableSpace < bytes.size) "storage full" else "the file could not be written – the phone's storage may be full", e)
        }
        return BackupStore.Created(Uri.fromFile(f).toString(), f.name)
    }

    override fun overwrite(handle: String, bytes: ByteArray) = ops.overwrite(handle, bytes)
    override fun rename(handle: String, fileName: String) = ops.rename(handle, fileName)
    override fun delete(handle: String) = ops.delete(handle)
}

/** A folder the user picked (Settings → Backup → Choose folder, e.g. a synced Drive / Nextcloud folder). */
internal class TreeBackupStore(context: Context, private val tree: Uri, override val label: String) : BackupStore {
    private val resolver = context.contentResolver
    private val ops = FileOps(resolver)
    override val key = Backups.treeKey(tree)

    private fun parent(deleted: Boolean): Uri {
        val root = try { BackupFolder.rootDoc(tree) } catch (e: Exception) { throw BackupException("the chosen folder is not available", e) }
        if (!deleted) return root
        return BackupFolder.children(resolver, tree, DocumentsContract.getDocumentId(root))
            .firstOrNull { it.isDir && it.name == "Deleted" }?.uri
            ?: try {
                DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, "Deleted")
            } catch (e: Exception) { throw BackupException("the chosen folder cannot be written", e) }
            ?: throw BackupException("the chosen folder cannot be written")
    }

    override fun create(fileName: String, deleted: Boolean, bytes: ByteArray): BackupStore.Created {
        val p = parent(deleted)
        val uri = try { DocumentsContract.createDocument(resolver, p, "application/octet-stream", fileName) } catch (e: SecurityException) {
            throw BackupException("no access to the chosen folder any more – choose it again in Settings → Backup", e)
        } catch (e: Exception) {
            throw BackupException("the chosen folder cannot be written", e)
        } ?: throw BackupException("the chosen folder cannot be written")
        try {
            (resolver.openOutputStream(uri, "w") ?: throw java.io.IOException("no output stream")).use { it.write(bytes) }
        } catch (e: Exception) {
            ops.delete(uri.toString())
            throw BackupException("the file could not be written – the folder may be full or offline", e)
        }
        return BackupStore.Created(uri.toString(), ops.displayName(uri) ?: fileName)
    }

    override fun overwrite(handle: String, bytes: ByteArray) = ops.overwrite(handle, bytes)
    override fun rename(handle: String, fileName: String) = ops.rename(handle, fileName)
    override fun delete(handle: String) = ops.delete(handle)
}

/** Reading a picked folder (restore) with the Storage Access Framework. */
object BackupFolder {
    data class Doc(val uri: Uri, val name: String, val isDir: Boolean, val size: Long)

    /** Where the folder picker opens: Documents/OnPath on the phone's storage. */
    val initialUri: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/OnPath")

    fun rootDoc(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** The picked folder is the default Documents/OnPath (restored files then count as this folder's files). */
    fun isDocumentsOnPath(tree: Uri): Boolean = try {
        tree.authority == "com.android.externalstorage.documents" &&
            DocumentsContract.getTreeDocumentId(tree).equals("primary:Documents/OnPath", ignoreCase = true)
    } catch (e: Exception) { Problems.info("backup", "Picked folder id unreadable", e); false }

    fun label(context: Context, tree: Uri): String = try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        if (tree.authority == "com.android.externalstorage.documents") id.substringAfter(':').ifBlank { "Phone storage" }
        else FileOps(context.contentResolver).displayName(rootDoc(tree)) ?: id
    } catch (e: Exception) { Problems.info("backup", "Picked folder name unreadable", e); "chosen folder" }

    fun children(resolver: ContentResolver, tree: Uri, parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    add(Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getString(1) ?: "",
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) -1 else c.getLong(3)))
                }
            }
        } ?: emptyList()
    }

    /**
     * GPX files in the picked folder – and in its "OnPath" subfolder when the user picked Documents itself.
     * Deleted/ (the recycle bin) is not restored.
     */
    fun gpxFiles(resolver: ContentResolver, tree: Uri): List<Doc> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val top = children(resolver, tree, rootId)
        val sub = top.firstOrNull { it.isDir && it.name.equals("OnPath", ignoreCase = true) }
            ?.let { children(resolver, tree, DocumentsContract.getDocumentId(it.uri)) }.orEmpty()
        return (top + sub).filter { !it.isDir && it.name.endsWith(".gpx", ignoreCase = true) }
    }
}

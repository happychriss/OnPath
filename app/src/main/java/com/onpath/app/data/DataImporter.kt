package com.onpath.app.data

import com.onpath.strings.*
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Imports map data on the phone itself – no computer or adb needed (picked from Downloads, e.g. a zip
 * received in a chat app). Accepts the `onpath-data` zip or single files.
 *
 * Robust by design (requirements R-02, R-11):
 * - only known file types land in known places; anything else (or "../" tricks) is skipped;
 * - each file is written to a temp file first and renamed only when complete, so a broken or cancelled
 *   import never leaves a half-written map file behind – existing data keeps working;
 * - free space is checked before writing each file.
 */
object DataImporter {

    data class Result(val imported: List<String>, val skipped: List<String>) {
        val summary: String
            get() = listOfNotNull(
                if (imported.isEmpty()) Txt.s(Res.string.maps_import_nothing)
                else Txt.s(Res.string.maps_import_done, imported.size, imported.joinToString()),
                if (skipped.isEmpty()) null
                else Txt.s(Res.string.maps_import_skipped, skipped.take(5).joinToString() + if (skipped.size > 5) " …" else ""),
            ).joinToString(" ")
    }

    class ImportException(message: String) : Exception(message)

    /**
     * Where a file from the zip (or a single picked file) belongs inside the data dir, or null if it is not map data.
     * Leading folders like "onpath-data/" are ignored; only the file name and type count.
     */
    fun targetPath(entryName: String): String? {
        val clean = entryName.replace('\\', '/').trim('/')
        if (clean.isEmpty() || clean.split('/').any { it == ".." }) return null
        val name = clean.substringAfterLast('/')
        if (name.startsWith(".") || name.isBlank()) return null
        val parent = clean.substringBeforeLast('/', "").substringAfterLast('/')
        return when {
            name.endsWith(".rd5") -> "segments/$name"
            name.endsWith(".pmtiles") || name.endsWith(".geojson") -> name
            // offline address search: addresses.sqlite, or one file per map cell in addresses/
            name.endsWith(".sqlite") && parent == "addresses" -> "addresses/$name"
            name == "addresses.sqlite" -> name
            name == DataFiles.PACKAGE_FILE -> name // which package the files belong to (consistency check)
            name.endsWith(".json") && (parent == "styles" || name.startsWith("onpath")) -> "styles/$name"
            else -> null
        }
    }

    /** True if [name] looks like a zip (by name); otherwise it is treated as a single file. */
    fun isZip(name: String) = name.lowercase().endsWith(".zip")

    /**
     * Imports from [input]. [displayName] is the picked file's name (decides zip vs single file).
     * [onProgress] gets the name of the file being written.
     */
    fun import(
        input: InputStream,
        displayName: String,
        dataDir: File,
        onProgress: (String) -> Unit = {},
    ): Result {
        dataDir.mkdirs()
        val imported = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        if (isZip(displayName)) {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val target = targetPath(e.name)
                    if (target == null) { skipped += e.name.substringAfterLast('/'); continue }
                    onProgress(target)
                    write(zip, File(dataDir, target), e.size)
                    imported += target
                }
            }
        } else {
            val target = targetPath(displayName) ?: throw ImportException(
                Txt.s(Res.string.maps_import_not_map_data, displayName))
            onProgress(target)
            input.use { write(it, File(dataDir, target), -1) }
            imported += target
        }
        if (imported.isEmpty()) throw ImportException(Txt.s(Res.string.maps_import_none_found, displayName))
        return Result(imported, skipped)
    }

    private fun write(source: InputStream, dest: File, expectedSize: Long) {
        dest.parentFile?.mkdirs()
        val free = dest.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (expectedSize > 0 && free < expectedSize + 50L * 1024 * 1024) {
            throw ImportException(Txt.s(Res.string.maps_import_no_space, dest.name, expectedSize / 1_048_576))
        }
        val tmp = File(dest.parentFile, ".${dest.name}.importing")
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.fd.sync()
            }
            if (tmp.length() == 0L) throw ImportException(Txt.s(Res.string.maps_import_empty, dest.name))
            // rename replaces the old file atomically: the existing data is never gone without the new one in place
            if (!tmp.renameTo(dest)) {
                if (dest.exists() && !dest.delete()) throw ImportException(Txt.s(Res.string.maps_import_cant_replace, dest.name))
                if (!tmp.renameTo(dest)) throw ImportException(Txt.s(Res.string.maps_import_cant_save, dest.name))
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

package com.onpath.app.data

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
            get() = buildString {
                append(if (imported.isEmpty()) "Nothing imported." else "Imported ${imported.size} file(s): ${imported.joinToString()}.")
                if (skipped.isNotEmpty()) append(" Skipped: ${skipped.take(5).joinToString()}${if (skipped.size > 5) " …" else ""}.")
            }
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
                "“$displayName” is not OnPath map data. Pick the onpath-data zip, or a .pmtiles, .geojson, .rd5 or style .json file.")
            onProgress(target)
            input.use { write(it, File(dataDir, target), -1) }
            imported += target
        }
        if (imported.isEmpty()) throw ImportException("No map data found in “$displayName”.")
        return Result(imported, skipped)
    }

    private fun write(source: InputStream, dest: File, expectedSize: Long) {
        dest.parentFile?.mkdirs()
        val free = dest.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (expectedSize > 0 && free < expectedSize + 50L * 1024 * 1024) {
            throw ImportException("Not enough free storage for ${dest.name} (needs ${expectedSize / 1_048_576} MB).")
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
            if (tmp.length() == 0L) throw ImportException("${dest.name} is empty.")
            if (dest.exists() && !dest.delete()) throw ImportException("Could not replace ${dest.name}.")
            if (!tmp.renameTo(dest)) throw ImportException("Could not save ${dest.name}.")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

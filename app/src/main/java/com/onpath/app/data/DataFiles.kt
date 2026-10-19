package com.onpath.app.data

import android.content.Context
import java.io.File

/** Location and presence check of the big offline data files (pushed via adb). */
class DataFiles(context: Context) {

    /** = /sdcard/Android/data/com.onpath.app/files/onpath */
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "onpath")
    val segmentsDir: File get() = File(dir, "segments")

    data class Check(val name: String, val present: Boolean, val hint: String)

    init {
        // Create the folder so `adb push` has a target and the user can see where it is.
        try { dir.mkdirs() } catch (_: Exception) { }
    }

    fun check(): List<Check> {
        fun f(name: String, hint: String) = Check(name, File(dir, name).let { it.isFile && it.length() > 0 }, hint)
        val rd5 = segmentsDir.listFiles { file -> file.name.endsWith(".rd5") && file.length() > 0 }.orEmpty()
        return listOf(
            f("base.pmtiles", "Base map"),
            f("terrain.pmtiles", "Relief / elevation"),
            f("routes.geojson", "Marked route networks"),
            f("trails.geojson", "Trail difficulty"),
            Check("segments/*.rd5", rd5.isNotEmpty(), "Routing data"),
        )
    }

    fun allPresent() = check().all { it.present }

    fun has(name: String) = File(dir, name).isFile

    /**
     * The installed map package (package.json from the zip) and the files that do not belong to it: a file whose
     * size differs from the package's list came from another import (old package, laptop push) – the map then has
     * gaps at that package's edge. Null when there is no package.json (older data). Never throws.
     */
    fun packageCheck(): PackageCheck? = try {
        val f = File(dir, PACKAGE_FILE)
        if (!f.isFile) null else PackageCheck.parse(f.readText()) { name -> File(dir, name).let { if (it.isFile) it.length() else -1L } }
    } catch (_: Exception) { null }

    /** Pure part of [packageCheck] (unit-tested): [sizeOf] = file size on the phone, -1 = missing. */
    data class PackageCheck(val region: String, val bbox: List<Double>, val osmDate: String?, val mismatched: List<String>) {
        val ok: Boolean get() = mismatched.isEmpty()

        /** "10.70–11.60 E · 46.30–46.85 N" */
        val area: String get() = if (bbox.size == 4)
            "%.2f–%.2f E · %.2f–%.2f N".format(java.util.Locale.ROOT, bbox[0], bbox[2], bbox[1], bbox[3]) else ""

        companion object {
            fun parse(json: String, sizeOf: (String) -> Long): PackageCheck {
                val o = org.json.JSONObject(json)
                val files = o.optJSONObject("files")
                val bad = mutableListOf<String>()
                files?.keys()?.forEach { name ->
                    val size = sizeOf(name)
                    // missing optional files are fine (the missing-data check covers required ones)
                    if (size >= 0 && size != files.optLong(name, -2)) bad += name
                }
                val b = o.optJSONArray("bbox")
                return PackageCheck(
                    o.optString("region", "?"),
                    if (b == null) emptyList() else (0 until b.length()).map { b.optDouble(it) },
                    o.optString("osm_date").takeIf { it.isNotBlank() },
                    bad.sorted(),
                )
            }
        }
    }

    companion object {
        /** Written by tools/build_region.sh into the package: region, bbox, OSM date, size of every file. */
        const val PACKAGE_FILE = "package.json"
        const val ADB_COMMAND = "adb push onpath-data/. /sdcard/Android/data/com.onpath.app/files/onpath/"
    }
}

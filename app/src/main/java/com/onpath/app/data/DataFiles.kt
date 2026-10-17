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

    companion object {
        const val ADB_COMMAND = "adb push onpath-data/. /sdcard/Android/data/com.onpath.app/files/onpath/"
    }
}

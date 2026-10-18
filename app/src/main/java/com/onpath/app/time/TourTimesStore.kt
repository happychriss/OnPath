package com.onpath.app.time

import org.json.JSONObject
import java.io.File

/**
 * Moving time / breaks / planned vs. needed per recorded tour id (`tour-times.json`, atomic tmp + rename). Written by
 * the recording service when a tour ends; read by the tour summary. Never throws: a broken file = nothing known
 * (the summary then falls back to [BreakDetector.breaksIn] on the stored points).
 */
class TourTimesStore(private val file: File) {

    fun get(tourId: Long): TourTimes? = try {
        read().optJSONObject(tourId.toString())?.let { TourTimes.fromJson(it) }
    } catch (_: Exception) { null }

    fun put(tourId: Long, t: TourTimes) {
        try {
            val o = read()
            o.put(tourId.toString(), t.toJson())
            // keep the file small: the newest 500 tours
            val keys = o.keys().asSequence().mapNotNull { it.toLongOrNull() }.sorted().toList()
            keys.take((keys.size - MAX).coerceAtLeast(0)).forEach { o.remove(it.toString()) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) { }
    }

    private fun read(): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (_: Exception) { JSONObject() }

    companion object {
        const val FILE = "tour-times.json"
        private const val MAX = 500
    }
}

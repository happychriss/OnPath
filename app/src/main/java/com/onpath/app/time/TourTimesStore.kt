package com.onpath.app.time

import com.onpath.app.data.AtomicFiles
import com.onpath.app.debug.Problems
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
    } catch (e: Exception) {
        Problems.warn("time", "Tour times file (tour-times.json) entry unreadable; breaks computed from the stored points", e)
        null
    }

    fun put(tourId: Long, t: TourTimes) {
        try {
            val o = read()
            o.put(tourId.toString(), t.toJson())
            // keep the file small: the newest [MAX] tours
            val keys = o.keys().asSequence().mapNotNull { it.toLongOrNull() }.sorted().toList()
            keys.take((keys.size - MAX).coerceAtLeast(0)).forEach { o.remove(it.toString()) }
            AtomicFiles.writeText(file, o.toString(), File(file.path + ".tmp"))
        } catch (e: Exception) {
            Problems.error("time", "Tour times file (tour-times.json) could not be saved", e)
        }
    }

    private fun read(): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        Problems.warn("time", "Tour times file (tour-times.json) unreadable; starting empty", e)
        JSONObject()
    }

    companion object {
        const val FILE = "tour-times.json"
        private const val MAX = 500
    }
}

package com.onpath.app.cells

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Merges point GeoJSON files (pois.geojson of the region package and of every cell) into one FeatureCollection,
 * streaming feature by feature (a few MB, never the whole file as a tree). Like [PmTiles.merge]: each input says which
 * features it keeps (by the owner cell of the point, [CellId.at]).
 */
object GeoJsonMerge {

    class Input(val file: File, val keep: (lat: Double, lon: Double) -> Boolean)

    /** [features] written, bytes taken from each input, features [skipped] for lack of coordinates. */
    data class Result(val features: Int, val bytesPerInput: List<Long>, val skipped: Int)

    /** Writes [out]. Broken JSON throws (the caller keeps the old store); a feature without coordinates is skipped. */
    fun merge(inputs: List<Input>, out: File): Result {
        out.parentFile?.mkdirs()
        var n = 0
        var skipped = 0
        val perInput = LongArray(inputs.size)
        FileOutputStream(out).use { fo ->
            val w = OutputStreamWriter(fo.buffered(1 shl 16), Charsets.UTF_8)
            w.write("""{"type":"FeatureCollection","features":[""")
            inputs.forEachIndexed { idx, input ->
                forEachFeature(input.file) { f ->
                    val (lat, lon) = firstPosition(f) ?: run { skipped++; return@forEachFeature }
                    if (!input.keep(lat, lon)) return@forEachFeature
                    val s = f.toString()
                    if (n > 0) w.write(",")
                    w.write(s); n++
                    perInput[idx] += s.length.toLong()
                }
            }
            w.write("]}\n")
            w.flush()
            fo.fd.sync()
        }
        return Result(n, perInput.toList(), skipped)
    }

    /** Calls [onFeature] for every element of "features". Throws on broken JSON (the caller keeps the old store). */
    fun forEachFeature(f: File, onFeature: (JsonElement) -> Unit) {
        if (!f.isFile) return
        JsonReader(InputStreamReader(f.inputStream().buffered(1 shl 16), Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "features" || r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); continue }
                r.beginArray()
                while (r.hasNext()) onFeature(JsonParser.parseReader(r))
                r.endArray()
            }
            r.endObject()
        }
    }

    /** (lat, lon) of the feature's first coordinate pair, null without usable geometry. */
    fun firstPosition(f: JsonElement): Pair<Double, Double>? = try {
        var c: JsonElement? = f.asJsonObject.getAsJsonObject("geometry")?.get("coordinates")
        while (c != null && c.isJsonArray && c.asJsonArray.size() > 0 && c.asJsonArray[0].isJsonArray) c = c.asJsonArray[0]
        val a = c?.takeIf { it.isJsonArray }?.asJsonArray
        if (a == null || a.size() < 2) null else a[1].asDouble to a[0].asDouble
    } catch (e: RuntimeException) {
        // expected: a feature that is not an object / has non-numeric coordinates – counted as skipped by merge()
        null
    }
}

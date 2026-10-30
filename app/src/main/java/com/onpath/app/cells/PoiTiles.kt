package com.onpath.app.cells

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * POIs as vector tiles (`pois.pmtiles`, one MVT layer "pois"), built on the phone from the cells' `pois.geojson` when
 * a generation is written ([CellStore.apply]).
 *
 * Why: as one GeoJSON source MapLibre's geojson-vt keeps an index of every POI with its properties in every tile it ever
 * cut – ~5.5 KB per POI, 0.8 GB native heap for 145 k POIs (19 cells), more after each join. From PMTiles MapLibre only
 * holds the tiles on screen. `pois.geojson` stays in the generation (search index, the GeoJSON fallback).
 * Why on the phone and not in the cell builder: every installed cell gets it at once (no new cell schema, no rebuild),
 * and region packages / older cells need no second path.
 *
 * Tiles z10–14 (the style over-zooms 14), extent [EXTENT]; a POI goes into the tiles from [minZoom] of its kind on – the
 * zoom the style starts drawing it (PoiTilesStyleTest checks it against the generated styles). Every property is kept
 * (tap → details sheet reads them from the rendered feature). A point's tiles all belong to its owner cell
 * ([CellId.at]), so [PmTiles.merge] can join POI tiles of different cells by tile ownership like the other layers.
 */
object PoiTiles {
    const val FILE = "pois.pmtiles"
    const val LAYER = "pois"
    const val MIN_ZOOM = 10
    const val MAX_ZOOM = 14
    const val EXTENT = 8192

    /** Kinds the style draws from z12 (tools/make_style.py POI_TIERS tier 1). */
    private val FROM_Z12 = setOf("hut", "bivouac", "restaurant", "cafe", "viewpoint", "spring", "waterfall", "lift", "castle")

    /** Lowest tile zoom a POI of [kind] is in: train stations from z10, tier 1 from z12, everything else z14. */
    fun minZoom(kind: String?): Int = when (kind) {
        "train" -> 10
        in FROM_Z12 -> 12
        else -> MAX_ZOOM
    }

    /** Point input: a GeoJSON file and which points of it to keep (by owner cell, as for [GeoJsonMerge]). */
    class Input(val file: File, val keep: (lat: Double, lon: Double) -> Boolean)

    data class Result(val features: Int, val tiles: Int, val skipped: Int)

    val METADATA = """{"name":"OnPath POIs","format":"pbf","vector_layers":[{"id":"$LAYER","minzoom":$MIN_ZOOM,"maxzoom":$MAX_ZOOM,"fields":{}}]}"""

    /**
     * Writes [out] (PMTiles, gzip MVT) with the kept points of [inputs]. Returns null and writes nothing when no point is
     * kept. Streams: the points are first sorted into one file per owner cell in [work] (deleted afterwards), then each
     * cell's tiles are encoded on their own and the cells joined with [PmTiles.merge] – memory stays at one cell's POIs.
     * Broken JSON throws (the caller decides).
     */
    fun build(inputs: List<Input>, out: File, work: File): Result? {
        work.deleteRecursively()
        if (!work.mkdirs()) throw IOException("could not create ${work.path}")
        try {
            var n = 0; var skipped = 0
            val parts = LinkedHashMap<CellId, java.io.Writer>()
            try {
                for (input in inputs) GeoJsonMerge.forEachFeature(input.file) { f ->
                    val (lat, lon) = GeoJsonMerge.firstPosition(f) ?: run { skipped++; return@forEachFeature }
                    if (!input.keep(lat, lon)) return@forEachFeature
                    val cell = CellId.at(lat, lon) ?: run { skipped++; return@forEachFeature }
                    val w = parts.getOrPut(cell) { File(work, "${cell.id}.jsonl").bufferedWriter(Charsets.UTF_8) }
                    w.write(f.toString()); w.write("\n"); n++
                }
            } finally {
                parts.values.forEach { it.close() }
            }
            if (n == 0) return null
            val cellFiles = ArrayList<PmTiles.Input>()
            var tiles = 0
            for (cell in parts.keys) {
                val tilesOfCell = encodeCell(cell, File(work, "${cell.id}.jsonl"))
                if (tilesOfCell.isEmpty()) continue
                tiles += tilesOfCell.size
                val f = File(work, "${cell.id}.pmtiles")
                PmTiles.write(tilesOfCell, f, PmTiles.TYPE_MVT, PmTiles.COMPRESSION_GZIP, METADATA)
                cellFiles += PmTiles.Input(f) { _, _, _ -> true }
            }
            if (cellFiles.isEmpty()) return null
            PmTiles.merge(cellFiles, out, metadataJson = METADATA) ?: return null
            return Result(n, tiles, skipped)
        } finally {
            work.deleteRecursively()
        }
    }

    /** All tiles of one cell's POIs ("z/x/y" → gzip MVT). */
    private fun encodeCell(cell: CellId, jsonl: File): Map<Triple<Int, Int, Int>, ByteArray> {
        val layers = HashMap<Triple<Int, Int, Int>, LayerBuilder>()
        jsonl.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val f = JsonParser.parseString(line)
                val (lat, lon) = GeoJsonMerge.firstPosition(f) ?: continue
                val props = properties(f)
                val kind = props.firstOrNull { it.first == "kind" }?.second as? String
                val mx = mercX(lon); val my = mercY(lat)
                for (z in maxOf(MIN_ZOOM, minZoom(kind))..MAX_ZOOM) {
                    val s = 1 shl (z - CellId.Z)
                    val n = (1 shl z).toDouble()
                    // the tile inside the owner cell (a point on the cell's edge stays in its cell's tiles)
                    val tx = Math.floor(mx * n).toInt().coerceIn(cell.x * s, cell.x * s + s - 1)
                    val ty = Math.floor(my * n).toInt().coerceIn(cell.y * s, cell.y * s + s - 1)
                    val px = Math.round((mx * n - tx) * EXTENT).toInt().coerceIn(0, EXTENT - 1)
                    val py = Math.round((my * n - ty) * EXTENT).toInt().coerceIn(0, EXTENT - 1)
                    layers.getOrPut(Triple(z, tx, ty)) { LayerBuilder() }.add(px, py, props)
                }
            }
        }
        return layers.mapValues { (_, l) -> PmTiles.gzip(l.tile()) }
    }

    /** Properties in file order; null values left out, numbers as Long when whole, objects / arrays as JSON text. */
    internal fun properties(f: JsonElement): List<Pair<String, Any>> {
        val p = f.asJsonObject.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val out = ArrayList<Pair<String, Any>>(p.size())
        for ((k, v) in p.entrySet()) {
            val value: Any = when {
                v == null || v.isJsonNull -> continue
                v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
                v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asString.toLongOrNull() ?: v.asDouble
                v.isJsonPrimitive -> v.asString
                else -> v.toString()
            }
            out += k to value
        }
        return out
    }

    fun mercX(lon: Double): Double = (lon + 180.0) / 360.0

    fun mercY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    }

    // ------------------------------------------------------------------------------------------- MVT encoding

    /** One tile's "pois" layer (Mapbox Vector Tile 2.1): keys / values tables + point features. */
    private class LayerBuilder {
        private val keys = LinkedHashMap<String, Int>()
        private val values = LinkedHashMap<Any, Int>()
        private val features = ArrayList<ByteArray>()

        fun add(px: Int, py: Int, props: List<Pair<String, Any>>) {
            val f = Pb()
            val tags = Pb()
            for ((k, v) in props) {
                tags.varint(keys.getOrPut(k) { keys.size }.toLong())
                tags.varint(values.getOrPut(v) { values.size }.toLong())
            }
            if (tags.size() > 0) f.bytes(2, tags.toByteArray())
            f.tag(3, 0); f.varint(1) // type POINT
            val g = Pb()
            g.varint((1L and 7) or (1L shl 3)) // MoveTo, count 1
            g.varint(zigzag(px.toLong())); g.varint(zigzag(py.toLong()))
            f.bytes(4, g.toByteArray())
            features += f.toByteArray()
        }

        fun tile(): ByteArray {
            val l = Pb()
            l.tag(15, 0); l.varint(2) // version
            l.bytes(1, LAYER.toByteArray(Charsets.UTF_8))
            for (f in features) l.bytes(2, f)
            for (k in keys.keys) l.bytes(3, k.toByteArray(Charsets.UTF_8))
            for (v in values.keys) l.bytes(4, value(v))
            l.tag(5, 0); l.varint(EXTENT.toLong())
            return Pb().apply { bytes(3, l.toByteArray()) }.toByteArray()
        }

        private fun value(v: Any): ByteArray = Pb().apply {
            when (v) {
                is String -> bytes(1, v.toByteArray(Charsets.UTF_8))
                is Long -> { tag(6, 0); varint(zigzag(v)) }
                is Double -> { tag(3, 1); fixed64(java.lang.Double.doubleToRawLongBits(v)) }
                is Boolean -> { tag(7, 0); varint(if (v) 1 else 0) }
                else -> bytes(1, v.toString().toByteArray(Charsets.UTF_8))
            }
        }.toByteArray()
    }

    private fun zigzag(n: Long): Long = (n shl 1) xor (n shr 63)

    /** Minimal protobuf writer. */
    private class Pb : ByteArrayOutputStream() {
        fun varint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) { write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            write(x.toInt())
        }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); write(b) }
        fun fixed64(v: Long) { for (i in 0 until 8) write(((v ushr (8 * i)) and 0xFF).toInt()) }
    }
}

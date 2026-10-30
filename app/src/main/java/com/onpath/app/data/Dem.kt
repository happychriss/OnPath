package com.onpath.app.data

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/** One decoded terrain tile: [size] × [size] heights in metres, row by row from the top (north-west corner first). */
class DemTile(val size: Int, val h: FloatArray)

/** Terrain tiles of the map (terrain.pmtiles); [heights] null = no such tile. Implementations may throw. */
interface DemTiles {
    val minZoom: Int
    val maxZoom: Int
    fun heights(z: Int, x: Int, y: Int): DemTile?
}

/**
 * Terrarium encoding of the map's terrain tiles (AWS / Mapzen, tools/build_terrain.py; PNG in format 1, lossless WebP
 * in format 2): height = R·256 + G + B/256 − 32768 m.
 */
object Terrarium {
    fun height(argb: Int): Float =
        ((argb shr 16 and 0xff) * 256 + (argb shr 8 and 0xff)).toFloat() + (argb and 0xff) / 256f - 32768f

    /** Pixels (ARGB, row by row) of a square tile → heights; null when not square. */
    fun tile(pixels: IntArray): DemTile? {
        val size = kotlin.math.sqrt(pixels.size.toDouble()).toInt()
        if (size < 2 || size * size != pixels.size) return null
        return DemTile(size, FloatArray(pixels.size) { height(pixels[it]) })
    }
}

/**
 * Height of the ground at a place from the map's terrain tiles (the same DEM the hill shading draws). Pure, unit-tested.
 * Bilinear between pixel centres at the finest zoom there is (≤ [MAX_ZOOM]: z13 ≈ 13 m per pixel in South Tyrol);
 * a missing tile falls back to up to [FALLBACK_ZOOMS] coarser zooms; outside the terrain → null. The last
 * [cacheTiles] decoded tiles are kept (a tile is ~2–3 km wide: one decode per tile walked into). Not thread-safe.
 */
class Dem(private val tiles: DemTiles, private val cacheTiles: Int = 12) {
    companion object {
        const val MAX_ZOOM = 13
        const val FALLBACK_ZOOMS = 3
        private val NONE = DemTile(0, FloatArray(0))
    }

    private val cache = object : LinkedHashMap<Long, DemTile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, DemTile>?) = size > cacheTiles
    }

    private fun tile(z: Int, x: Int, y: Int): DemTile? {
        val n = 1 shl z
        if (x < 0 || y < 0 || x >= n || y >= n) return null
        val key = (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()
        val t = cache[key] ?: (tiles.heights(z, x, y) ?: NONE).also { cache[key] = it }
        return if (t === NONE) null else t
    }

    /** Ground height in metres, or null (no terrain here / not a number). */
    fun heightAt(lat: Double, lon: Double): Double? {
        if (lat.isNaN() || lon.isNaN() || lat > 85.05 || lat < -85.05) return null
        val top = minOf(MAX_ZOOM, tiles.maxZoom)
        val bottom = maxOf(tiles.minZoom, top - FALLBACK_ZOOMS)
        for (z in top downTo bottom) at(z, lat, lon)?.let { return it }
        return null
    }

    private fun at(z: Int, lat: Double, lon: Double): Double? {
        val n = (1 shl z).toDouble()
        val tx = (lon + 180.0) / 360.0 * n
        val ty = (1.0 - asinh(tan(lat * PI / 180.0)) / PI) / 2.0 * n
        val home = tile(z, floor(tx).toInt(), floor(ty).toInt()) ?: return null
        val size = home.size
        // pixel centres: global pixel coordinate − 0.5
        val px = tx * size - 0.5; val py = ty * size - 0.5
        val x0 = floor(px).toLong(); val y0 = floor(py).toLong()
        val fx = px - x0; val fy = py - y0
        fun v(X: Long, Y: Long): Float? {
            val t = tile(z, Math.floorDiv(X, size.toLong()).toInt(), Math.floorDiv(Y, size.toLong()).toInt()) ?: return null
            if (t.size != size) return null
            return t.h[(Math.floorMod(Y, size.toLong()) * size + Math.floorMod(X, size.toLong())).toInt()]
        }
        val a = v(x0, y0); val b = v(x0 + 1, y0); val c = v(x0, y0 + 1); val d = v(x0 + 1, y0 + 1)
        val h = if (a != null && b != null && c != null && d != null)
            (a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + d * fx * fy)
        else {
            // at the edge of the terrain: the nearest pixel of the tile the place is in
            val nx = Math.round(px - floor(tx) * size).toInt().coerceIn(0, size - 1)
            val ny = Math.round(py - floor(ty) * size).toInt().coerceIn(0, size - 1)
            home.h[ny * size + nx].toDouble()
        }
        return if (h.isNaN() || h < -500 || h > 9000) null else h
    }
}

package com.onpath.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.onpath.app.cells.PmTiles
import com.onpath.app.debug.Problems
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * The map's terrain.pmtiles as [DemTiles]: Terrarium tiles (PNG in format 1, lossless WebP in format 2) decoded by
 * Android's BitmapFactory. The directory is read once when opened (off the main thread, [TerrainHeights]); a tile is
 * found by binary search. Thread-safe; after [close] there are no tiles.
 */
class PmTilesDemTiles(file: File) : DemTiles, Closeable {
    private val reader = PmTiles.Reader(file)
    private val entries = try { reader.entries().sortedBy { it.tileId } } catch (e: Exception) { reader.close(); throw e }
    private var closed = false
    override val minZoom = reader.header.minZoom
    override val maxZoom = reader.header.maxZoom

    @Synchronized override fun heights(z: Int, x: Int, y: Int): DemTile? {
        if (closed) return null
        val id = PmTiles.zxyToTileId(z, x, y)
        var lo = 0; var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = entries[mid]
            when {
                id < e.tileId -> hi = mid - 1
                id >= e.tileId + e.runLength -> lo = mid + 1
                else -> return decode(PmTiles.decompress(reader.tileBytes(e), reader.header.tileCompression))
            }
        }
        return null
    }

    private fun decode(b: ByteArray): DemTile? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false }
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, opts) ?: return null
        return try {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            Terrarium.tile(px)
        } finally { bmp.recycle() }
    }

    @Synchronized override fun close() { closed = true; reader.close() }
}

/**
 * Ground height under a recorded point (recording/AscentCounter) from the terrain of the map in use – the downloaded
 * map cells or the region package ([DataFiles.mapDir]). [heightAt] is cheap and never throws (main thread): it only
 * uses a terrain already opened. Opening, and noticing a new map generation (cells downloaded during the tour), runs
 * on its own thread at most every [RECHECK_MS]. No terrain → null (the recording then counts GPS altitude).
 */
class TerrainHeights(private val mapDir: () -> File, private val now: () -> Long = System::currentTimeMillis) : Closeable {
    companion object {
        const val RECHECK_MS = 60_000L
        const val FILE = "terrain.pmtiles"
    }

    private class Open(val key: String, val tiles: PmTilesDemTiles, val dem: Dem)

    @Volatile private var open: Open? = null
    @Volatile private var checking = false
    @Volatile private var closed = false
    @Volatile private var checkedOnce = false
    private var checkedAt = Long.MIN_VALUE
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "terrain").apply { isDaemon = true } }

    /** Opens the terrain now (start of a recording), so the first points already have heights. */
    fun prepare() = recheck()

    fun heightAt(lat: Double, lon: Double): Double? {
        recheck()
        val o = open ?: return null
        return try {
            synchronized(o.dem) { o.dem.heightAt(lat, lon) }
        } catch (e: Exception) {
            Problems.warn("elevation", "Terrain height not read; ascent from GPS altitude for this point", e); null
        }
    }

    private fun recheck() {
        val t = now()
        if (closed || checking || (checkedAt != Long.MIN_VALUE && t - checkedAt < RECHECK_MS)) return
        checkedAt = t
        checking = true
        try {
            worker.execute {
                try { refresh() } catch (e: Exception) {
                    Problems.warn("elevation", "Terrain for the ascent not opened; ascent from GPS altitude", e)
                } finally { checking = false }
            }
        } catch (e: Exception) { checking = false; Problems.warn("elevation", "Terrain check not started", e) }
    }

    private fun refresh() {
        val f = File(mapDir(), FILE)
        val key = if (f.isFile) "${f.absolutePath}:${f.length()}:${f.lastModified()}" else ""
        val old = open
        if (old?.key == key) return
        if (key.isEmpty()) {
            if (old != null || checkedOnce.not()) Problems.info("elevation", "No terrain data in the map in use – ascent from GPS altitude")
            open = null
        } else {
            val tiles = PmTilesDemTiles(f)
            open = Open(key, tiles, Dem(tiles))
            Problems.trace("elevation", "Ascent from the terrain: ${f.parentFile?.name}/$FILE, z${tiles.minZoom}–${tiles.maxZoom}")
        }
        checkedOnce = true
        old?.let { o -> try { synchronized(o.dem) { o.tiles.close() } } catch (e: Exception) { Problems.warn("elevation", "Old terrain file not closed", e) } }
        if (closed) open?.tiles?.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.execute {
            try { open?.tiles?.close() } catch (e: Exception) { Problems.warn("elevation", "Terrain file not closed", e) }
            open = null
        }
        worker.shutdown()
    }
}

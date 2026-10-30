package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.tan

/** Ground heights from Terrarium terrain tiles (the map's terrain.pmtiles) – ascent of a recording comes from here. */
class DemTest {
    private fun argb(h: Double): Int {
        val v = h + 32768.0
        val r = (v / 256).toInt(); val g = v.toInt() % 256; val b = ((v - v.toInt()) * 256).toInt()
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test fun terrariumDecodes() {
        for (h in listOf(0.0, 1.5, 335.25, 2270.75, 3905.0, -12.5)) assertEquals(h, Terrarium.height(argb(h)).toDouble(), 1.0 / 256)
    }

    /** Tiles of size 4 whose height is a plane in global pixel coordinates: h = 1000 + 0.01·X + 0.02·Y (pixel centres). */
    private class Plane(override val minZoom: Int = 10, override val maxZoom: Int = 13, val missing: Set<Triple<Int, Int, Int>> = emptySet()) : DemTiles {
        val reads = mutableListOf<Triple<Int, Int, Int>>()
        override fun heights(z: Int, x: Int, y: Int): DemTile? {
            reads += Triple(z, x, y)
            if (Triple(z, x, y) in missing) return null
            val s = 4
            return DemTile(s, FloatArray(s * s) { i -> (1000.0 + 0.01 * (x * s + i % s) + 0.02 * (y * s + i / s)).toFloat() })
        }
    }

    private fun tilePos(z: Int, lat: Double, lon: Double): Pair<Double, Double> {
        val n = (1 shl z).toDouble()
        return (lon + 180) / 360 * n to (1 - asinh(tan(lat * PI / 180)) / PI) / 2 * n
    }

    @Test fun bilinearBetweenPixelCentresAlsoAcrossTileEdges() {
        val plane = Plane()
        val dem = Dem(plane)
        for ((lat, lon) in listOf(46.67 to 11.16, 46.6123 to 11.1049, 46.70 to 11.25)) {
            val (tx, ty) = tilePos(13, lat, lon)
            val want = 1000.0 + 0.01 * (tx * 4 - 0.5) + 0.02 * (ty * 4 - 0.5)
            assertEquals(want, dem.heightAt(lat, lon)!!, 1e-3)
        }
        // exactly on a tile edge: the neighbour tile is read for the other pixel column
        val (tx, ty) = tilePos(13, 46.67, 11.16)
        val x = kotlin.math.floor(tx) + 1.0          // western edge of the next tile
        val lon = x / (1 shl 13) * 360 - 180
        assertEquals(1000.0 + 0.01 * (x * 4 - 0.5) + 0.02 * (ty * 4 - 0.5), dem.heightAt(46.67, lon)!!, 1e-3)
    }

    @Test fun missingFinestTileFallsBackToACoarserZoom() {
        val (tx, ty) = tilePos(13, 46.67, 11.16)
        val gone = Triple(13, tx.toInt(), ty.toInt())
        val dem = Dem(Plane(missing = setOf(gone)))
        val (x12, y12) = tilePos(12, 46.67, 11.16)
        assertEquals(1000.0 + 0.01 * (x12 * 4 - 0.5) + 0.02 * (y12 * 4 - 0.5), dem.heightAt(46.67, 11.16)!!, 1e-3)
    }

    @Test fun noTerrainIsNull() {
        val dem = Dem(object : DemTiles {
            override val minZoom = 5; override val maxZoom = 13
            override fun heights(z: Int, x: Int, y: Int): DemTile? = null
        })
        assertNull(dem.heightAt(46.67, 11.16))
        assertNull(dem.heightAt(Double.NaN, 11.16))
        assertNull(dem.heightAt(89.0, 11.16))
    }

    @Test fun edgeOfTheTerrainTakesTheNearestPixel() {
        val (tx, ty) = tilePos(13, 46.67, 11.16)
        // the eastern neighbour is missing; a place in the eastern half of the last pixel column
        val missing = setOf(Triple(13, tx.toInt() + 1, ty.toInt()), Triple(13, tx.toInt() + 1, ty.toInt() + 1),
            Triple(13, tx.toInt() + 1, ty.toInt() - 1))
        val dem = Dem(Plane(missing = missing))
        val x = kotlin.math.floor(tx) + 0.99
        val lon = x / (1 shl 13) * 360 - 180
        val h = dem.heightAt(46.67, lon)!!
        val col = 3; val row = ((ty - kotlin.math.floor(ty)) * 4 - 0.5).let { Math.round(it).toInt().coerceIn(0, 3) }
        assertEquals(1000.0 + 0.01 * (tx.toInt() * 4 + col) + 0.02 * (ty.toInt() * 4 + row), h, 1e-3)
    }

    @Test fun decodedTilesAreCached() {
        val plane = Plane()
        val dem = Dem(plane)
        repeat(100) { dem.heightAt(46.67 + it * 1e-6, 11.16) }
        assertEquals(plane.reads.size, plane.reads.toSet().size)
    }
}

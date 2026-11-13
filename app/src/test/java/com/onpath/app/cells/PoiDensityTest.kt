package com.onpath.app.cells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The density limit for city POIs (owner 2026-11-13, central London: "in einer Riesenstadt sucht man keine Restaurants"). */
class PoiDensityTest {
    private val lat = 51.5136; private val lon = -0.1365 // Soho, London

    /** [n] POIs of [kind] packed into ~40 m (one z16 tile is ~300 m wide here), pr rising with the index. */
    private fun cluster(n: Int, kind: String, named: Boolean = true, prBase: Long = 0) = (0 until n).map { i ->
        PoiDensity.Point(PoiTiles.mercX(lon + (i % 10) * 0.00005), PoiTiles.mercY(lat + (i / 10) * 0.00005), kind, named, prBase + i)
    }

    @Test fun villageKeepsEveryPoiAtItsKindsZoom() {
        // an Alpine village: 10 restaurants, 6 cafés, 5 bars, 3 shops – all from their kind's zoom, as before
        val pts = cluster(10, "restaurant") + cluster(6, "cafe") + cluster(5, "bar") + cluster(3, "shop") + cluster(4, "hut")
        val z = PoiDensity.firstZooms(pts)
        pts.forEachIndexed { i, p -> assertEquals(p.kind, PoiDensity.KINDS[p.kind] ?: 0, z[i]) }
    }

    @Test fun bigCityShowsTheMostNotableFirstAndEverythingFromZ17() {
        val pts = cluster(200, "restaurant")
        val z = PoiDensity.firstZooms(pts)
        // one tile at every zoom up to z16: the 24 with the highest priority from z12, the rest only from z17
        val top = pts.indices.sortedByDescending { pts[it].pr }.take(PoiDensity.CAP).toSet()
        pts.indices.forEach { i -> assertEquals("poi $i", if (i in top) 12 else PoiDensity.ALL_FROM, z[i]) }
    }

    @Test fun namedBeforeUnnamedAndOtherKindsNeverLimited() {
        val unnamed = cluster(30, "cafe", named = false, prBase = 40_000)
        val named = cluster(30, "cafe", named = true)
        val others = cluster(50, "toilets") + cluster(50, "hut") + cluster(50, "bus") + cluster(50, "water")
        val pts = unnamed + named + others
        val z = PoiDensity.firstZooms(pts)
        // 24 of the 30 named win although every unnamed one has a higher "pr"
        assertEquals(PoiDensity.CAP, pts.indices.count { pts[it].named && pts[it].kind == "cafe" && z[it] == 12 })
        assertTrue(unnamed.indices.all { z[it] == PoiDensity.ALL_FROM })
        (unnamed.size + named.size until pts.size).forEach { assertEquals(0, z[it]) }
    }

    @Test fun kindsCountOnlyFromTheZoomTheyAreDrawn() {
        // 30 shops (drawn from z15) do not push restaurants out at z12–14; from z15 all compete for the 24 places
        val rest = cluster(20, "restaurant", prBase = 10_000)
        val shops = cluster(30, "shop")
        val z = PoiDensity.firstZooms(rest + shops)
        // restaurants (higher pr) keep z12: at z15/z16 they are still among the top 24
        rest.indices.forEach { assertEquals(12, z[it]) }
        // 4 shops fit beside the 20 restaurants, the other 26 wait for z17
        assertEquals(4, shops.indices.count { z[rest.size + it] == 15 })
        assertEquals(26, shops.indices.count { z[rest.size + it] == PoiDensity.ALL_FROM })
    }

    @Test fun neverPopsInAndOutWhileZooming() {
        // restaurants spread over a z14 tile: at z14 they compete as one group, at z15 / z16 in smaller tiles. A POI
        // shown at z13 that loses its place at z14 must not show at z13 either – first zoom = start of a run up to z17.
        val pts = (0 until 400).map { i ->
            PoiDensity.Point(PoiTiles.mercX(lon + (i % 20) * 0.0006), PoiTiles.mercY(lat + (i / 20) * 0.0004), "restaurant", true,
                (i * 7919L) % 1000)
        }
        val z = PoiDensity.firstZooms(pts)
        for (zoom in 12..16) {
            val n = (1L shl zoom).toDouble()
            val perTile = pts.indices.filter { z[it] <= zoom }.groupBy { (pts[it].mx * n).toLong() to (pts[it].my * n).toLong() }
            perTile.values.forEach { assertTrue("z$zoom: ${it.size} in one tile", it.size <= PoiDensity.CAP) }
        }
        assertTrue("some limited", z.any { it == PoiDensity.ALL_FROM })
        assertTrue("some early", z.any { it == 12 })
    }
}

package com.onpath.app.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tap / hold finds the POI under the finger from what the map showed at its last idle moment ([ShownPois]) – never
 * with queryRenderedFeatures on the main thread (it waited 1447 ms for the busy render thread in central London,
 * owner's Pixel 2026-11-13).
 */
class PoiHitTest {
    private val dp = 2.75f // Pixel
    private val box = 24f * dp
    private fun poi(id: String, name: String? = null) = Poi(id, "restaurant", name, 51.5, -0.13)
    private fun at(p: Poi, x: Float, y: Float, icon: Boolean = true, name: Boolean = false) =
        PoiHit.Candidate(p, x, y, icon, if (name) p.name?.length ?: 0 else 0)

    @Test fun iconUnderTheFingerBoxIsHit() {
        val a = poi("a")
        val shown = listOf(at(a, 500f, 500f))
        assertEquals(a, PoiHit.pick(shown, 500f, 500f, box, dp, 16.0))
        // finger box half 12 dp + icon half 11 dp
        assertEquals(a, PoiHit.pick(shown, 500f + 22f * dp, 500f - 22f * dp, box, dp, 16.0))
        assertNull(PoiHit.pick(shown, 500f + 24f * dp, 500f, box, dp, 16.0))
        assertNull(PoiHit.pick(emptyList(), 500f, 500f, box, dp, 16.0))
    }

    @Test fun nearestPoiWins() {
        val a = poi("a"); val b = poi("b")
        val shown = listOf(at(a, 500f, 500f), at(b, 500f + 16f * dp, 500f))
        assertEquals(b, PoiHit.pick(shown, 500f + 10f * dp, 500f, box, dp, 16.0))
        assertEquals(a, PoiHit.pick(shown, 500f + 6f * dp, 500f, box, dp, 16.0))
    }

    @Test fun placedNameBesideTheIconIsHit() {
        val a = poi("a", "Pret A Manger") // 13 characters ≈ 7 em wide
        val placed = listOf(at(a, 500f, 500f, name = true))
        val notPlaced = listOf(at(a, 500f, 500f, name = false))
        val em = PoiHit.nameSizeDp(16.0) * dp
        // on the name, right of the icon (anchor "left") and left of it (anchor "right")
        assertEquals(a, PoiHit.pick(placed, 500f + 5f * em, 500f, box, dp, 16.0))
        assertEquals(a, PoiHit.pick(placed, 500f - 5f * em, 500f, box, dp, 16.0))
        assertNull(PoiHit.pick(notPlaced, 500f + 5f * em, 500f, box, dp, 16.0))
        // past the name's end, or well above it
        assertNull(PoiHit.pick(placed, 500f + 10f * em, 500f, box, dp, 16.0))
        assertNull(PoiHit.pick(placed, 500f + 5f * em, 500f - 3f * em, box, dp, 16.0))
    }

    @Test fun nameShownWithoutItsIconIsHitOnlyOnTheName() {
        val a = poi("a", "Zum Löwen")
        val shown = listOf(at(a, 500f, 500f, icon = false, name = true))
        assertNull(PoiHit.pick(shown, 500f, 500f, 4f * dp, dp, 16.0))
        val em = PoiHit.nameSizeDp(16.0) * dp
        assertEquals(a, PoiHit.pick(shown, 500f + 3f * em, 500f, 4f * dp, dp, 16.0))
    }

    @Test fun nameSizeFollowsTheStyle() {
        assertEquals(10.5f, PoiHit.nameSizeDp(12.0), 0.01f)
        assertEquals(10.5f, PoiHit.nameSizeDp(14.0), 0.01f)
        assertEquals(12.5f, PoiHit.nameSizeDp(17.0), 0.01f)
        assertEquals(12.5f, PoiHit.nameSizeDp(18.0), 0.01f)
    }

    @Test fun readAgainAtOnceOnlyAfterARealMove() {
        val c = PoiHit.Cam(16.0, 0.0, 0.0)
        assertTrue(PoiHit.moved(null, c, 0f, 2400f))
        assertFalse("same view", PoiHit.moved(c, c, 0f, 2400f))
        assertFalse("GPS drift while following", PoiHit.moved(c, c, 100f, 2400f))
        assertTrue("panned", PoiHit.moved(c, c, 300f, 2400f))
        assertTrue("zoomed", PoiHit.moved(c, c.copy(zoom = 16.5), 0f, 2400f))
        assertFalse("turned 2° over north", PoiHit.moved(c.copy(bearing = 359.0), c.copy(bearing = 1.0), 0f, 2400f))
        assertTrue("turned", PoiHit.moved(c, c.copy(bearing = 10.0), 0f, 2400f))
    }

    /** No tap / hold path asks MapLibre for rendered features on the main thread. */
    @Test fun tapAndHoldNeverQueryTheRenderedMap() {
        val host = File("src/main/java/com/onpath/app/ui/MapHost.kt").readText()
        assertFalse(host.contains("queryRenderedFeatures"))
        assertFalse(host.contains("findPoiAt"))
        assertTrue(host.contains("shownPois.at("))
        val tap = File("src/main/java/com/onpath/app/poi/PoiTap.kt").readText()
        val lookup = tap.substringAfter("fun at(").substringBefore("private fun read(")
        assertFalse("ShownPois.at reads only its own list", lookup.contains("queryRenderedFeatures"))
    }
}

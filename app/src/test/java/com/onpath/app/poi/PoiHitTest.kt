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
    private fun at(p: Poi, x: Float, y: Float, icon: Boolean = true) = PoiHit.Candidate(p, x, y, icon)

    /** The style's POI name size in dp at z16–17 (tools/make_style.py pois(): 10.5 at z14 → 12.5 at z17). */
    private val em = 12f * dp

    @Test fun iconUnderTheFingerBoxIsHit() {
        val a = poi("a")
        val shown = listOf(at(a, 500f, 500f))
        assertEquals(a, PoiHit.pick(shown, 500f, 500f, box, dp))
        // finger box half 12 dp + icon half 11 dp
        assertEquals(a, PoiHit.pick(shown, 500f + 22f * dp, 500f - 22f * dp, box, dp))
        assertNull(PoiHit.pick(shown, 500f + 24f * dp, 500f, box, dp))
        assertNull(PoiHit.pick(emptyList(), 500f, 500f, box, dp))
    }

    @Test fun nearestPoiWins() {
        val a = poi("a"); val b = poi("b")
        val shown = listOf(at(a, 500f, 500f), at(b, 500f + 16f * dp, 500f))
        assertEquals(b, PoiHit.pick(shown, 500f + 10f * dp, 500f, box, dp))
        assertEquals(a, PoiHit.pick(shown, 500f + 6f * dp, 500f, box, dp))
    }

    /**
     * The map does not tell which names it placed (the name and its symbol are one symbol since 2026-11-16), so a name
     * beside its symbol is no target of its own: only the finger box on the symbol counts – its first ~10 dp still do.
     */
    @Test fun aNameBesideItsSymbolIsHitOnlyNearTheSymbol() {
        val a = poi("a", "Pret A Manger")
        val shown = listOf(at(a, 500f, 500f))
        assertEquals(a, PoiHit.pick(shown, 500f + 20f * dp, 500f, box, dp))
        assertNull(PoiHit.pick(shown, 500f + 5f * em, 500f, box, dp))
        assertNull(PoiHit.pick(shown, 500f - 5f * em, 500f, box, dp))
    }

    /**
     * Owner's Pixel 2026-11-15 / 16, Hilden, z ~16.8: the bus stop pair „Dorothea-Erxleben-Straße" on Gerresheimer
     * Straße. The north stop is drawn with its bus symbol (it is route point 3); the south stop, ~120 m away, lost its
     * symbol in the collision with the street name beside it, but its name was placed – grey text that reads like a
     * street name. Two holds on the street there opened the place menu. A name without its symbol is no POI: the hold
     * is a plain map hold. Only a POI whose symbol is drawn is hit.
     */
    @Test fun hildenBusStopNameWithoutItsSymbolIsNoPoi() {
        val stopName = "Dorothea-Erxleben-Straße"
        val north = Poi("n1", "bus", stopName, 51.1702, 6.9408)
        val south = Poi("n2", "bus", stopName, 51.1691, 6.9414)
        val pxPerM = dp / 0.86f // z16.8 at 51.17° N: ~0.86 m per dp
        val nx = 600f; val ny = 1000f
        val sx = nx + 40f * pxPerM; val sy = ny + 120f * pxPerM
        val shown = listOf(at(north, nx, ny, icon = true), at(south, sx, sy, icon = false))
        // on the south stop's bare name (left of its point, where it was drawn), its start, and where its symbol would be
        assertNull("hold on the bare name", PoiHit.pick(shown, sx - 5f * em, sy, box, dp))
        assertNull("hold on the bare name's start", PoiHit.pick(shown, sx - 2f * em, sy, box, dp))
        assertNull("hold where the symbol would be", PoiHit.pick(shown, sx, sy, box, dp))
        // the north stop's symbol is drawn: a hold on it is the POI
        assertEquals(north, PoiHit.pick(shown, nx, ny, box, dp))
        // once the south stop's symbol is drawn too, it is hit like any POI
        val both = shown + at(south, sx, sy, icon = true)
        assertEquals(south, PoiHit.pick(both, sx + 4f * dp, sy, box, dp))
    }

    /** Read once per camera stop / change, never again while the map just sits there (battery, owner's Pixel 2026-11-13). */
    @Test fun readsOnceAfterACameraStopOrChangeNeverOnAStillMap() {
        val t = PoiHit.ReadTrigger()
        assertTrue("first idle after the map came up", t.mapIdle())
        // a still map: the position dot redraws it every second – no read
        repeat(600) { assertFalse(t.mapIdle()) }
        t.cameraIdle()
        assertTrue("first idle after a pan", t.mapIdle())
        assertFalse(t.mapIdle())
        // a camera stop while tiles still load: the read waits for the map's idle (all tiles in), once
        t.cameraIdle(); t.cameraIdle()
        assertTrue(t.mapIdle()); assertFalse(t.mapIdle())
        t.changed()
        assertTrue("new style / data / POI settings", t.mapIdle())
        assertFalse(t.mapIdle())
    }

    /**
     * Owner's Pixel 2026-11-21 (0.18.6): an idle while the new style is still loading must not use up the wish to read –
     * else the list stays empty (every drawn symbol "nothing") until the next camera move.
     */
    @Test fun anIdleWhileTheStyleLoadsKeepsTheWishToRead() {
        val t = PoiHit.ReadTrigger()
        assertTrue(t.mapIdle())
        t.changed() // style reload
        assertFalse("idle, style not fully loaded: no read", t.mapIdle(ready = false))
        assertFalse(t.mapIdle(ready = false))
        assertTrue("the first idle with the style loaded reads", t.mapIdle(ready = true))
        assertFalse("and only once", t.mapIdle(ready = true))
        t.cameraIdle()
        assertFalse(t.mapIdle(ready = false))
        assertTrue(t.mapIdle(ready = true))
    }

    @Test fun theMapIsAskedOnlyWhenTheListMissesAndTheMapIsIdle() {
        val t = PoiHit.IdleTracker()
        assertFalse("before the first idle", t.idleNow())
        t.became(); assertTrue(t.idleNow())
        t.moved(); assertFalse("a camera move: busy until the next idle", t.idleNow())
        t.became(); assertTrue(t.idleNow())
        assertTrue(PoiHit.mayQueryMap(listHit = false, mapIdle = true))
        assertFalse("the list has it", PoiHit.mayQueryMap(listHit = true, mapIdle = true))
        assertFalse("a moving map is never queried", PoiHit.mayQueryMap(listHit = false, mapIdle = false))
    }

    @Test fun readRunsNoTimer() {
        val tap = File("src/main/java/com/onpath/app/poi/PoiTap.kt").readText()
        for (timer in listOf("postDelayed", "Timer(", "schedule", "delay(")) assertFalse(timer, tap.contains(timer))
    }

    /** No tap / hold path asks MapLibre for rendered features on the main thread. */
    @Test fun tapAndHoldNeverQueryTheRenderedMap() {
        val host = File("src/main/java/com/onpath/app/ui/MapHost.kt").readText()
        assertFalse(host.contains("queryRenderedFeatures"))
        assertFalse(host.contains("findPoiAt"))
        assertTrue("the finger-down buzz (every touch) stays on the list", host.contains("shownPois.at(map, at.x, at.y)"))
        assertFalse(host.contains("shownPois.atOrQuery(map, at.x"))
        val tap = File("src/main/java/com/onpath/app/poi/PoiTap.kt").readText()
        val lookup = tap.substringAfter("fun at(").substringBefore("fun atOrQuery(")
        assertFalse("ShownPois.at reads only its own list", lookup.contains("queryRenderedFeatures"))
    }

    /**
     * Field bug 2026-11-16 23:35 (owner's Pixel, Hilden, restaurants drawn with their symbol): a tap showed nothing and a
     * hold set no point – the symbol layers were told apart by reading each layer's icon image at runtime, which found no
     * POI layer. The bundled styles' layer ids decide: every poi- icon layer is a symbol layer, a -name layer never is.
     */
    @Test fun everyPoiIconLayerOfTheBundledStylesIsASymbolLayer() {
        val styles = File("src/main/assets/style").walkTopDown().filter { it.name == "onpath.json" }.toList()
        assertTrue("bundled styles found", styles.isNotEmpty())
        for (f in styles) {
            val ids = Regex("\"id\"\\s*:\\s*\"(poi-[^\"]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toList()
            val symbols = ids.filter { PoiHit.symbolLayer(it) }
            assertTrue("${f.path}: symbol layers $ids", symbols.any { it == "poi-tier1" })
            for (id in ids) assertEquals("${f.path}: $id", !id.endsWith("-name"), PoiHit.symbolLayer(id))
            // owner 2026-11-17: from z17 icon + name are one symbol in "poi-<x>-named" – a symbol layer like the others
            assertTrue("${f.path}: the z17 layers with names are symbol layers $ids", symbols.any { it == "poi-tier1-named" })
            assertEquals("${f.path}: every POI layer draws its symbol", ids, symbols)
        }
        assertTrue(PoiHit.symbolLayer("poi-station-named"))
        assertFalse("a text-only -name layer (older style)", PoiHit.symbolLayer("poi-station-name"))
        assertFalse(PoiHit.symbolLayer("route-line"))
    }

    /** The rule is the layer id, never the layer's icon image read at runtime (that read failed on the phone). */
    @Test fun symbolLayersAreToldByTheirId() {
        val tap = File("src/main/java/com/onpath/app/poi/PoiTap.kt").readText()
        assertTrue(tap.contains("partition { PoiHit.symbolLayer(it) }"))
        assertFalse(tap.contains("iconImage"))
    }
}

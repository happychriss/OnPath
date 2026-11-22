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

    /** A press the list missed waits once; whoever comes first finishes it, and it is finished exactly once. */
    @Test fun aWaitingPressIsFinishedExactlyOnce() {
        val w = PoiHit.PressWait<String>()
        assertNull("nothing waits", w.take())
        val (t1, none) = w.put("hold")
        assertNull(none)
        assertEquals("the idle comes first", "hold", w.take())
        assertNull("the time-out after it finds nothing", w.expire(t1))

        val (t2, _) = w.put("tap")
        assertEquals("no idle within the bound: a plain press", "tap", w.expire(t2))
        assertNull(w.take())

        val (t3, _) = w.put("first")
        val (t4, older) = w.put("second")
        assertEquals("a newer press hands back the older one", "first", older)
        assertNull("the older press's time-out does not end the newer one", w.expire(t3))
        assertEquals("second", w.expire(t4))
    }

    /** The press's time runs out while a read is under way: it waits for that read (slow emulator: 3 ms short), bounded. */
    @Test fun aPressWaitsForAReadUnderWayButNotForever() {
        assertTrue(PoiHit.keepWaitingForRead(readRunning = true, waitedMs = 0))
        assertTrue(PoiHit.keepWaitingForRead(readRunning = true, waitedMs = PoiHit.READ_LAND_MS - 1))
        assertFalse("bounded", PoiHit.keepWaitingForRead(readRunning = true, waitedMs = PoiHit.READ_LAND_MS))
        assertFalse("no read: a plain press at once", PoiHit.keepWaitingForRead(readRunning = false, waitedMs = 0))
        val w = PoiHit.PressWait<String>()
        val (t, _) = w.put("hold")
        assertTrue(w.waits(t))
        w.take()
        assertFalse("finished: nothing to keep waiting for", w.waits(t))
    }

    /** A camera stop asks for one repaint, none on a follow step, and a lost idle cannot block repaints for good. */
    @Test fun repaintAfterACameraStopNotOnAFollowStep() {
        val g = PoiHit.RepaintGate(staleMs = 1_000)
        assertFalse("a follow step repaints by itself", g.mayAskAfterCameraStop(0, following = true))
        assertTrue(g.mayAskAfterCameraStop(0, following = false))
        assertFalse("one is on its way", g.mayAskAfterCameraStop(10, following = false))
        g.idleCame()
        assertTrue("its idle came", g.mayAskAfterCameraStop(20, following = false))
        assertFalse(g.mayAskAfterCameraStop(1_019, following = false))
        assertTrue("no idle came for a second: not stuck", g.mayAskAfterCameraStop(1_020, following = false))
        g.idleCame()
        assertFalse("a style load while following", g.mayAskAfterStyleLoad(2_000, following = true))
        assertTrue(g.mayAskAfterStyleLoad(2_000, following = false))
    }

    /** Review 2026-11-21: a still follow camera draws nothing by itself, so a press must get its one frame. */
    @Test fun aPressGetsItsFrameWhileTheCameraFollows() {
        val g = PoiHit.RepaintGate(staleMs = 1_000)
        assertFalse(g.mayAskAfterCameraStop(0, following = true))
        assertTrue("the press asks although the camera follows", g.mayAskForPress(10))
        assertFalse("but not twice before its idle", g.mayAskForPress(20))
        g.idleCame()
        assertTrue(g.mayAskForPress(30))
    }

    /** Review 2026-11-21: a pinch keeps following; the map then stands still and the list of the old zoom must be read again. */
    @Test fun aFingerOnAFollowingCameraStillGetsARepaintForTheRead() {
        val g = PoiHit.RepaintGate(staleMs = 1_000)
        g.fingerMoved()
        assertTrue("after the pinch", g.mayAskAfterCameraStop(0, following = true))
        g.idleCame()
        assertFalse("the next follow step is no finger", g.mayAskAfterCameraStop(10, following = true))
    }

    /** Review 2026-11-21: what a waiting press does was decided at the press; a hold that closes a menu never adds a point. */
    @Test fun aWaitingPressRunsWhatWasDecidedAtThePress() {
        fun decide(menuOpen: Boolean, poi: Boolean) =
            com.onpath.app.domain.MapGestures.decide(true, com.onpath.app.domain.MapGestures.Press.HOLD, menuOpen, -1, poi, planEmpty = false)
        val plan = PoiHit.PressPlan(true, decide(menuOpen = true, poi = false), decide(menuOpen = true, poi = true))
        assertEquals(com.onpath.app.domain.MapGestures.Action.CloseMenu, plan.action(found = false, canEditNow = true))
        assertEquals("the live rule would add a point now", com.onpath.app.domain.MapGestures.Action.AddPoint, decide(menuOpen = false, poi = false))
        assertEquals(com.onpath.app.domain.MapGestures.Action.PoiMenu, plan.action(found = true, canEditNow = true))
        assertNull("the map stopped being editable while it waited: dropped", plan.action(found = false, canEditNow = false))
    }

    /** GitHub's emulator 2026-11-21: the idle came after up to 650 ms; a shorter bound made a hold on a symbol a route point. */
    @Test fun aPressOutwaitsASlowMapIdle() {
        assertTrue(PoiHit.PRESS_WAIT_MS >= 1_000L)
    }

    @Test fun readRunsNoTimer() {
        val tap = File("src/main/java/com/onpath/app/poi/PoiTap.kt").readText()
        for (timer in listOf("postDelayed", "Timer(", "schedule", "delay(")) assertFalse(timer, tap.contains(timer))
    }

    /** No tap / hold path asks MapLibre for rendered features: a list miss waits, the map is asked on its idle only. */
    @Test fun tapAndHoldNeverQueryTheRenderedMap() {
        val host = File("src/main/java/com/onpath/app/ui/MapHost.kt").readText()
        assertFalse(host.contains("queryRenderedFeatures"))
        assertFalse(host.contains("findPoiAt"))
        assertTrue(host.contains("shownPois.at("))
        val tap = File("src/main/java/com/onpath/app/poi/PoiTap.kt").readText()
        val press = tap.substringAfter("fun at(").substringBefore("private fun resolveWaitingPress(")
        assertTrue("the press path is there", press.contains("fun askMapAtNextIdle("))
        assertFalse("a press reads only the list", press.contains("queryRenderedFeatures") || press.contains("queryAtFinger"))
        val onIdle = tap.substringAfter("fun onMapIdle(").substringBefore("fun at(")
        assertTrue("the map is asked on its idle", onIdle.contains("resolveWaitingPress(map, view)"))
        assertEquals("and from nowhere else", 2, Regex("resolveWaitingPress\\(").findAll(tap).count())
        assertFalse("no test hook in production", tap.contains("ForTest") || tap.contains("var current"))
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

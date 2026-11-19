package com.onpath.app.e2e

import androidx.test.uiautomator.By
import com.onpath.app.domain.AppMode
import com.onpath.app.ui.AppViewModel
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.plan_add_as_stop
import com.onpath.strings.plan_make_destination
import com.onpath.strings.plan_start_here
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * CORE FEATURE C19: a POI whose symbol the map DRAWS answers a real finger – a tap shows its details, a hold in Plan
 * opens its menu; a hold beside it is a plain map hold. Field bug 2026-11-16 23:35 (owner's Pixel, Hilden: a tap on a
 * restaurant showed nothing, a hold set no point – „That's an essential functionality“): after 2797bb7f `ShownPois`
 * told the POI symbol layers by each layer's icon image read at runtime, which (43091d6b) said "no icon" on the Pixel.
 * JVM tests have no MapLibre and no E2E test put a finger on a drawn POI. Here the bundled style draws the POI and the
 * finger goes onto the drawn symbol, in both map formats: [format] 1 = the region package every E2E test uses
 * (`pois.geojson`, style `style/onpath.json`), 2 = what map cells bring – the owner's phone (`overlays.pmtiles` + the
 * POI vector tiles `pois.pmtiles` built on the phone by `PoiTiles`, style `style/f2/onpath.json`).
 * Not reproduced here (donald 2026-11-16): on the emulator (API 35, x86_64, MapLibre 13.7.1) 2797bb7f's runtime read
 * found the icon layers and all six cases passed with it too – this test guards the whole tap / hold path on the real
 * map, not that one read.
 */
@RunWith(Parameterized::class)
class CorePoiTapE2ETest(private val format: Int) : E2EBase() {

    private lateinit var symbol: Pair<Float, Float>

    @Before fun setUp() {
        launch(extraData = { dir -> writePois(dir) })
        assertEquals("the app opens in Plan", AppMode.PLAN, read { it.tourSession.mode })
        symbol = drawnPoiSymbol(POI_ID, POI_LAT, POI_LON, ICON_LAYER)
    }

    /** a) a real tap on the drawn symbol → the POI's details (sheet with its name). */
    @Test fun tapOnADrawnPoiSymbolShowsItsDetails() {
        press(symbol.first, symbol.second, 60)
        waitFor("the POI details after a tap on its symbol at ${at(symbol)}") { read { it.selectedPoi?.id } == POI_ID }
        assertNotNull("the details sheet shows the name", findFresh(By.text(POI_NAME), 5_000))
        assertNull("a tap sets no point", read { vm -> vm.plan.points.firstOrNull() })
        shot("details")
    }

    /** b) a real hold on the drawn symbol in Plan → the POI menu; „Start here“ there sets the plan's start on the POI. */
    @Test fun holdOnADrawnPoiSymbolOpensItsMenu() {
        hold(symbol.first, symbol.second)
        waitFor("the POI menu after a hold on its symbol at ${at(symbol)}") { read { it.placeMenus.poiMenu?.id } == POI_ID }
        assertNull("the POI menu, not the plain first-point menu", read { it.placeAdd.firstPointMenu })
        assertNotNull("the menu shows the POI's name", findFresh(By.text(POI_NAME), 5_000))
        shot("menu")
        clickText(Txt.s(Res.string.plan_start_here))
        waitUntil("the start on the POI") { waypointCount() == 1 }
        val p = read { it.plan.points.first() }
        assertTrue("the start is the POI ($p)", dist(p.lat, p.lon, POI_LAT, POI_LON) < 5.0)
    }

    /**
     * b2) while PLANNING (owner 2026-11-17: „wenn ich da nicht einfügen muss, dann braucht das auch im Menü nicht angezeigt
     * zu werden“): a plan coming from the south ends 300 m short of the POI – a hold on the symbol measures on the paths
     * (ring on the POI), then the menu opens with only „Neues Ziel“ (appending clearly shorter, on the paths and on straight
     * lines alike); taken, the plan goes on to the POI.
     */
    @Test fun holdOnADrawnPoiSymbolWhilePlanningShowsOnlyTheMatchingRow() {
        val a = com.onpath.app.routing.GeoPoint(POI_LAT - 0.018, POI_LON)   // ~2 km south
        val b = com.onpath.app.routing.GeoPoint(POI_LAT - 0.0027, POI_LON)  // ~300 m south: the goal
        scenario.onActivity { vm(it).placeAdd.addPoint(a); vm(it).placeAdd.addPoint(b) }
        waitUntil("a plan of two points") { waypointCount() == 2 }
        waitFor("the route drawn") { read { !it.planRun.routing && it.route != null } }
        val s = drawnPoiSymbol(POI_ID, POI_LAT, POI_LON, ICON_LAYER)
        com.onpath.app.debug.Problems.clear()
        hold(s.first, s.second, takeAddMenu = false) // waits for the measure (waitHoldMeasured)
        waitFor("the POI menu after the measure") { read { it.placeMenus.poiMenu?.id } == POI_ID }
        val rows = read { vm -> vm.placeMenus.poiMenu?.let(vm.placeMenus::placeMenu)?.main }
        val trace = com.onpath.app.debug.Problems.events().map { it.message }
        val line = trace.firstOrNull { it.startsWith("Place menu of a") }
        android.util.Log.i("OnPathE2E", "POI hold while planning: rows $rows, realRouting $realRouting: $line")
        assertEquals("only „Neues Ziel“ – $line", listOf(com.onpath.app.domain.PlaceAddMenu.Item.NEW_DESTINATION), rows)
        assertTrue("the decision says how it measured – $trace", line != null && (line.contains("paths: ") || line.contains("paths failed")))
        if (realRouting) assertTrue("measured on the paths with real routing data: $line", line!!.contains("paths: insert"))
        assertNotNull("„Neues Ziel“ shown", findFresh(By.text(Txt.s(Res.string.plan_make_destination)), 5_000))
        assertNull("no „Einfügen“", device.findObject(By.text(Txt.s(Res.string.plan_add_as_stop))))
        shot("planning-menu")
        clickText(Txt.s(Res.string.plan_make_destination))
        waitUntil("the plan goes on to the POI") { waypointCount() == 3 }
        val last = read { it.plan.points.last() }
        assertTrue("the new goal is the POI ($last)", dist(last.lat, last.lon, POI_LAT, POI_LON) < 5.0)
    }

    /** c) a hold on the empty map beside the symbol (beyond the finger box + icon) → the plain hold, no POI menu. */
    @Test fun holdBesideADrawnPoiSymbolIsAPlainMapHold() {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val beside = (symbol.first + 90 * dp) to symbol.second
        hold(beside.first, beside.second)
        waitFor("the plain hold's first-point menu at ${at(beside)}") { read { it.placeAdd.firstPointMenu } != null }
        assertNull("no POI menu beside the symbol", read { it.placeMenus.poiMenu })
        shot("plain-hold")
        clickText(Txt.s(Res.string.plan_start_here))
        waitUntil("a point by the plain hold") { waypointCount() == 1 }
        val p = read { it.plan.points.first() }
        assertTrue("the point is where the finger was, not on the POI ($p)", dist(p.lat, p.lon, POI_LAT, POI_LON) > 30.0)
    }

    /**
     * d) From z 17 the style draws the POI with its name in one symbol (layer "poi-tier1-named", owner 2026-11-17): a tap
     * on the symbol shows the details; a hold on the drawn NAME beside it is a plain map hold – a name is never a target.
     */
    @Test fun fromZoom17TheSymbolIsHitTheNameBesideItIsNot() {
        val s = drawnPoiSymbol(POI_ID, POI_LAT, POI_LON, NAMED_LAYER, zoom = 17.5)
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        // the name right of the symbol (first variable anchor "left"), beyond the finger box + icon (23 dp)
        val onName = (s.first + 55 * dp) to s.second
        assertTrue("the name is drawn at ${at(onName)} beside the symbol at ${at(s)}", drawnAround(onName, NAMED_LAYER))
        shot("named")
        hold(onName.first, onName.second)
        waitFor("the plain hold's first-point menu on the name at ${at(onName)}") { read { it.placeAdd.firstPointMenu } != null }
        assertNull("no POI menu on the name", read { it.placeMenus.poiMenu })
        device.pressBack()
        waitFor("the menu closed") { read { it.placeAdd.firstPointMenu } == null }
        press(s.first, s.second, 60)
        waitFor("the POI details after a tap on its symbol at ${at(s)} (z 17.5)") { read { it.selectedPoi?.id } == POI_ID }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Whether MapLibre drew a feature of [layer] within 4 dp of the screen point [p] (device pixels). */
    private fun drawnAround(p: Pair<Float, Float>, layer: String): Boolean {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        var hit = false
        val done = java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { a ->
            val mv = mapView(a)!!
            val loc = IntArray(2).also { mv.getLocationOnScreen(it) }
            mv.getMapAsync { m ->
                val x = p.first - loc[0]; val y = p.second - loc[1]
                hit = m.queryRenderedFeatures(android.graphics.RectF(x - 4 * dp, y - 4 * dp, x + 4 * dp, y + 4 * dp), layer)
                    .any { it.getStringProperty("id") == POI_ID }
                done.countDown()
            }
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return hit
    }

    /** The POI data of [format]: `pois.geojson`; format 2 adds the cells' overlays file and POI tiles. */
    private fun writePois(dir: java.io.File) {
        val geojson = java.io.File(dir, "pois.geojson").apply { writeText(POIS) }
        if (format == 2) writeCellPois(dir, geojson)
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        waitUntil(what, 10_000, onTimeout = {
            shot("fail")
            "POI ${read { it.selectedPoi?.id }} / menu ${read { it.placeMenus.poiMenu?.id }} / first-point menu ${read { it.placeAdd.firstPointMenu }} – ${state()}"
        }, cond)
    }

    private fun at(p: Pair<Float, Float>) = "(${p.first.toInt()}, ${p.second.toInt()})"

    private fun <T> read(f: (AppViewModel) -> T): T {
        var v: T? = null
        scenario.onActivity { v = f(vm(it)) }
        @Suppress("UNCHECKED_CAST") return v as T
    }

    private fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat1 - lat2) * 111_320.0
        val dx = (lon1 - lon2) * 111_320.0 * Math.cos(Math.toRadians(lat1))
        return Math.hypot(dx, dy)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "format {0}") fun formats(): List<Int> = listOf(1, 2)

        const val POI_ID = "poi-tap-1"
        const val POI_NAME = "Restaurant Sissi"
        const val POI_LAT = 46.6700
        const val POI_LON = 11.1655
        /** The style's symbol layer that draws restaurants (tools/make_style.py pois(): tier 1, from z 12). */
        const val ICON_LAYER = "poi-tier1"
        /** From z 17: the restaurant's symbol with its name in one symbol (owner 2026-11-17). */
        const val NAMED_LAYER = "poi-tier1-named"
        val POIS = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[$POI_LON,$POI_LAT]},"properties":{"kind":"restaurant","name":"$POI_NAME","id":"$POI_ID"}}
        ]}"""
    }
}

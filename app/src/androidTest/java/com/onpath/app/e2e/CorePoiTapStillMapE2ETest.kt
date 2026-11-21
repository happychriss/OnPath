package com.onpath.app.e2e

import org.junit.Before
import org.junit.Test

/**
 * CORE FEATURE C19, owner 2026-11-21 (0.18.6): after a pan with a real finger the map stands still (no repaint at
 * all); a tap on the drawn POI symbol at its NEW place must still show the details – the list of shown POIs is read
 * once after every camera stop, also when the map's idle comes before the camera's idle.
 */
class CorePoiTapStillMapE2ETest : E2EBase() {
    @Before fun setUp() {
        launch(extraData = { dir -> java.io.File(dir, "pois.geojson").writeText(CorePoiTapE2ETest.POIS) })
    }

    @Test fun tapOnADrawnPoiSymbolAfterARealPanOnAStillMap() {
        val s = drawnPoiSymbol(CorePoiTapE2ETest.POI_ID, CorePoiTapE2ETest.POI_LAT, CorePoiTapE2ETest.POI_LON, CorePoiTapE2ETest.ICON_LAYER)
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val y = s.second.toInt() + 250
        val dx = (90 * dp).toInt()
        val x0 = s.first.toInt() - dx
        for (i in 1..8) { // pans back and forth over cached tiles, slow and fast; each ends on a map that stands still
            val steps = if (i % 2 == 0) 4 else 30
            val right = symbolOnScreen().first < mapSize().first / 2 // always toward the middle, the symbol stays on screen
            android.util.Log.i("OnPathE2E", "TEST swipe $i steps $steps right $right")
            if (right) device.swipe(x0, y, x0 + dx, y, steps) else device.swipe(x0 + dx, y, x0, y, steps)
            pause(2_500, "the map stands still after the pan, nothing repaints it")
            val p = symbolOnScreen()
            if (p.first < mapSize().first * 0.1f || p.first > mapSize().first * 0.9f) continue // flung to the edge: no finger there
            android.util.Log.i("OnPathE2E", "TEST tap at $p")
            press(p.first, p.second, 60)
            waitUntil("swipe $i: the POI details after a tap on the symbol at ${p.first.toInt()}, ${p.second.toInt()}", 6_000) { poiId() == CorePoiTapE2ETest.POI_ID }
            device.pressBack()
            waitUntil("swipe $i: the details closed", 5_000) { poiId() == null }
        }
    }

    private fun symbolOnScreen(): Pair<Float, Float> {
        var r = 0f to 0f
        scenario.onActivity { a ->
            val mv = mapView(a)!!
            val loc = IntArray(2).also { mv.getLocationOnScreen(it) }
            mv.getMapAsync { m ->
                m.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(CorePoiTapE2ETest.POI_LAT, CorePoiTapE2ETest.POI_LON))
                    .let { r = (loc[0] + it.x) to (loc[1] + it.y) }
            }
        }
        pause(200, "the projection is read on the main thread")
        return r
    }

    private fun poiId(): String? { var v: String? = null; scenario.onActivity { v = vm(it).selectedPoi?.id }; return v }
}

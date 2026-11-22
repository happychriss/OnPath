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

    /**
     * a2) a symbol the map draws but the list does not have (cause on the owner's phone 2026-11-21 unproven): drawn after
     * the last read, with no camera move, so nothing reads the list again. A tap and a hold on it still work – the press
     * waits for the map's next idle and asks the map there.
     */
    @Test fun aSymbolMissingFromTheListStillAnswersATapAndAHold() {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        com.onpath.app.debug.Problems.clear()
        val first = drawUnlistedPoi("unlisted-1", (symbol.first + 80 * dp) to symbol.second)
        press(first.first, first.second, 60)
        waitFor("the details of a symbol the list lacks, tap at ${at(first)}") { read { it.selectedPoi?.id } == "unlisted-1" }
        assertTrue("the field log says the list missed it", listMissedLogged())
        assertNotNull("the sheet is up before Back", findFresh(By.text("Unlisted unlisted-1"), 5_000))
        device.pressBack()
        waitFor("the details closed") { read { it.selectedPoi } == null }
        val second = drawUnlistedPoi("unlisted-2", (symbol.first + 80 * dp) to (symbol.second + 90 * dp))
        com.onpath.app.debug.Problems.clear()
        hold(second.first, second.second)
        waitFor("the POI menu of a symbol the list lacks, hold at ${at(second)}") { read { it.placeMenus.poiMenu?.id } == "unlisted-2" }
        assertNull("the POI menu, not the plain first-point menu", read { it.placeAdd.firstPointMenu })
        assertTrue("the hold came through the map, the list did not have it", listMissedLogged())
    }

    /**
     * a3) the same while the camera follows north up and stands still (review 2026-11-21): nothing repaints such a map and
     * a finger does not end this following, so the press itself must ask for its one frame – else no idle comes and the
     * hold sets a route point. The list cannot learn the symbol first, so the hold must come through the map.
     */
    @Test fun aHoldOnASymbolMissingFromTheListWorksWhileTheCameraFollows() {
        holdWhileFollowing(headingUp = false) { assertTrue("the hold came through the map, the list did not have the symbol", listMissedLogged()) }
    }

    /**
     * a4) following with the walking direction up: the finger-down ends following in Plan, so the camera stops under the
     * hold and a read is due (GitHub's slow emulator 2026-11-22: no idle within the press's second, the hold opened the
     * first-point menu on a drawn symbol). That read may land before the hold or after it, both are right: what counts is
     * the POI's menu.
     */
    @Test fun aHoldJustAfterTheFingerEndedFollowingOpensThePoiMenu() {
        holdWhileFollowing(headingUp = true) { }
    }

    /**
     * a5) field bug 2026-11-22 19:16 (owner's export: 8 presses in 22 s, no map idle for 30 s, smooth frames, an empty list):
     * every compass value on the position dot keeps the map repainting for ~300 ms, so a phone in the hand can keep MapLibre
     * from ever going idle. A tap and a hold on a drawn symbol must answer all the same.
     */
    @Test fun aTapAndAHoldOnASymbolAnswerWhileTheCompassKeepsTheMapFromIdle() {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        gpsAt(POI_LAT, POI_LON)
        waitUntil("a GPS fix in the app", 20_000) { read { it.tracker.location.value != null } }
        // both layers first: the camera move of the second one would let a read learn the first symbol
        addUnlistedLayer("unlisted-compass-1")
        addUnlistedLayer("unlisted-compass-2")
        val first = showUnlistedPoi("unlisted-compass-1", (symbol.first + 80 * dp) to symbol.second)
        val idles = java.util.concurrent.atomic.AtomicInteger()
        val idle = org.maplibre.android.maps.MapView.OnDidBecomeIdleListener { idles.incrementAndGet() }
        scenario.onActivity { mapView(it)!!.addOnDidBecomeIdleListener(idle) }
        val turning = java.util.concurrent.atomic.AtomicBoolean(true)
        var listeners: List<org.maplibre.android.location.CompassListener> = emptyList()
        onMap { m ->
            val engine = m.locationComponent.compassEngine as com.onpath.app.map.HeadingEngine
            val field = com.onpath.app.map.HeadingEngine::class.java.getDeclaredField("listeners").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") run { listeners = (field.get(engine) as Set<org.maplibre.android.location.CompassListener>).toList() }
        }
        assertTrue("the compass reaches the position dot", listeners.isNotEmpty())
        val main = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val compass = Thread {
            val t0 = System.currentTimeMillis()
            while (turning.get()) {
                val deg = 90 + 6 * kotlin.math.sin((System.currentTimeMillis() - t0) / 180.0)
                main.runOnMainSync { listeners.forEach { it.onCompassChanged(deg.toFloat()) } }
                Thread.sleep(16)
            }
        }.apply { isDaemon = true; start() }
        try {
            pause(1_000, "the turning dot has taken over the map's frames")
            idles.set(0)
            pause(1_500, "no idle while the compass turns")
            assertEquals("the case: the map does not go idle", 0, idles.get())
            com.onpath.app.debug.Problems.clear()
            press(first.first, first.second, 60)
            waitFor("the details of a symbol the list lacks, tap at ${at(first)}") { read { it.selectedPoi?.id } == "unlisted-compass-1" }
            assertTrue("the tap came through the map, the list did not have it", listMissedLogged())
            assertNotNull("the sheet is up before Back", findFresh(By.text("Unlisted unlisted-compass-1"), 5_000))
            device.pressBack()
            waitFor("the details closed") { read { it.selectedPoi } == null }
            // drawn only now: the read the tap's hit caused cannot know it
            val second = showUnlistedPoi("unlisted-compass-2", (symbol.first + 80 * dp) to (symbol.second + 90 * dp))
            com.onpath.app.debug.Problems.clear()
            hold(second.first, second.second)
            waitFor("the POI menu of a symbol the list lacks, hold at ${at(second)}") { read { it.placeMenus.poiMenu?.id } == "unlisted-compass-2" }
            assertNull("the POI menu, not the plain first-point menu", read { it.placeAdd.firstPointMenu })
            assertEquals("the hold set no route point", 0, waypointCount())
            assertTrue("the hold came through the map, the list did not have it", listMissedLogged())
        } finally {
            turning.set(false)
            compass.join(2_000)
            scenario.onActivity { mapView(it)?.removeOnDidBecomeIdleListener(idle) }
            device.executeShellCommand("cmd location providers remove-test-provider gps")
        }
    }

    private fun holdWhileFollowing(headingUp: Boolean, thenCheck: () -> Unit) {
        val id = "unlisted-follow-1"
        var before: com.onpath.app.data.AppSettings? = null
        scenario.onActivity { before = vm(it).settingsSlice.settings; vm(it).settingsSlice.updateSettings(vm(it).settingsSlice.settings.copy(navHeadingUp = headingUp)) }
        addUnlistedLayer(id)
        try {
            gpsAt(POI_LAT, POI_LON)
            waitUntil("a GPS fix in the app", 20_000) { read { it.tracker.location.value != null } }
            val (w, h) = mapSize()
            scenario.onActivity { vm(it).map.followMe = true }
            waitUntil("the camera follows", 10_000) { cameraMode() != org.maplibre.android.location.modes.CameraMode.NONE }
            pause(2_500, "the follow camera landed and stands still")
            // gpsAt gave one fix, so no follow step moves the camera now. The test provider stays until the end: without it
            // the emulator's own GPS sends a far-away fix and the following camera flies there (seen 2026-11-22).
            val p = showUnlistedPoi(id, (w * 0.35f) to (h * 0.25f))
            com.onpath.app.debug.Problems.clear()
            assertTrue("the camera follows at the press", cameraMode() != org.maplibre.android.location.modes.CameraMode.NONE)
            hold(p.first, p.second)
            waitFor("the POI menu of a symbol the list lacks, following, hold at ${at(p)}") { read { it.placeMenus.poiMenu?.id } == id }
            assertNull("the POI menu, not the plain first-point menu", read { it.placeAdd.firstPointMenu })
            assertEquals("the hold set no route point", 0, waypointCount())
            thenCheck()
        } finally {
            device.executeShellCommand("cmd location providers remove-test-provider gps")
            scenario.onActivity { a -> before?.let { vm(a).settingsSlice.updateSettings(it) } }
        }
    }

    private fun cameraMode(): Int {
        var mode = org.maplibre.android.location.modes.CameraMode.NONE
        val done = java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { a ->
            mapView(a)!!.getMapAsync { m ->
                val c = m.locationComponent
                if (c.isLocationComponentActivated) mode = c.cameraMode
                done.countDown()
            }
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return mode
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
        fun holdAndRead(): Triple<List<com.onpath.app.domain.PlaceAddMenu.Item>?, List<String>, String?> {
            com.onpath.app.debug.Problems.clear()
            hold(s.first, s.second, takeAddMenu = false) // waits for the measure (waitHoldMeasured)
            waitFor("the POI menu after the measure") { read { it.placeMenus.poiMenu?.id } == POI_ID }
            val rows = read { vm -> vm.placeMenus.poiMenu?.let(vm.placeMenus::placeMenu)?.main }
            val trace = com.onpath.app.debug.Problems.events().map { it.message }
            return Triple(rows, trace, trace.firstOrNull { it.startsWith("Place menu of a") })
        }
        var (rows, trace, line) = holdAndRead()
        // GitHub's emulator is sometimes ~5× slower (2026-11-20: the route 5.4 s instead of 1.1 s) and the app's 1.5 s limit
        // then falls back to straight lines – by design. Held once more (the legs are cached now) so the paths are still checked.
        if (realRouting && line?.contains("paths failed: timeout") == true) {
            android.util.Log.i("OnPathE2E", "POI hold while planning: the first measure timed out on a slow emulator – held once more: $line")
            scenario.onActivity { vm(it).placeMenus.closeMapOverlays("e2e: hold once more") }
            waitUntil("the menu closed") { read { it.placeMenus.poiMenu } == null }
            val again = holdAndRead(); rows = again.first; trace = again.second; line = again.third
        }
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
    private fun drawnAround(p: Pair<Float, Float>, layer: String, id: String = POI_ID): Boolean {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        var hit = false
        val done = java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { a ->
            val mv = mapView(a)!!
            val loc = IntArray(2).also { mv.getLocationOnScreen(it) }
            mv.getMapAsync { m ->
                val x = p.first - loc[0]; val y = p.second - loc[1]
                hit = m.queryRenderedFeatures(android.graphics.RectF(x - 4 * dp, y - 4 * dp, x + 4 * dp, y + 4 * dp), layer)
                    .any { it.getStringProperty("id") == id }
                done.countDown()
            }
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return hit
    }

    /**
     * Draws one more POI symbol at the screen point [p] in a layer of its own named like the style's POI layers – no camera
     * move and no style change after the layer is known, so the app's list of shown POIs does not learn of it.
     */
    private fun drawUnlistedPoi(id: String, p: Pair<Float, Float>): Pair<Float, Float> {
        addUnlistedLayer(id)
        return showUnlistedPoi(id, p)
    }

    /** The empty layer of [id]; one small camera move lets the app's next read see that the layer exists. */
    private fun addUnlistedLayer(id: String) {
        onMap { m ->
            val style = m.style!!
            val icon = "e2e-$id"
            style.addImage(icon, android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) })
            style.addSource(org.maplibre.android.style.sources.GeoJsonSource("e2e-src-$id", org.maplibre.geojson.FeatureCollection.fromFeatures(emptyArray<org.maplibre.geojson.Feature>())))
            style.addLayer(org.maplibre.android.style.layers.SymbolLayer("poi-e2e-$id", "e2e-src-$id").withProperties(
                org.maplibre.android.style.layers.PropertyFactory.iconImage(icon),
                org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true)))
            m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomBy(0.001)) // scrollBy reports no camera idle
        }
        pause(1_500, "the camera stop's read has seen the new, still empty layer")
    }

    /** Puts the symbol of [id] at the screen point [p]: the map draws it, nothing reads the list. */
    private fun showUnlistedPoi(id: String, p: Pair<Float, Float>): Pair<Float, Float> {
        scenario.onActivity { a ->
            val mv = mapView(a)!!
            val loc = IntArray(2).also { mv.getLocationOnScreen(it) }
            mv.getMapAsync { m ->
                val ll = m.projection.fromScreenLocation(android.graphics.PointF(p.first - loc[0], p.second - loc[1]))
                val f = org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(ll.longitude, ll.latitude)).apply {
                    addStringProperty("id", id); addStringProperty("kind", "restaurant"); addStringProperty("name", "Unlisted $id")
                }
                m.style!!.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("e2e-src-$id")!!.setGeoJson(f)
            }
        }
        waitFor("the extra symbol $id drawn at ${at(p)}") { drawnAround(p, "poi-e2e-$id", id) }
        pause(1_500, "the map is idle again; nothing read the list")
        return p
    }

    private fun listMissedLogged(): Boolean = com.onpath.app.debug.Problems.events().any { it.message.startsWith(com.onpath.app.poi.PoiHit.LIST_MISSED) } ||
        com.onpath.app.debug.Problems.snapshot().any { it.message.startsWith(com.onpath.app.poi.PoiHit.LIST_MISSED) }

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

package com.onpath.app.e2e

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.onpath.app.MainActivity
import com.onpath.app.cells.PmTiles
import com.onpath.app.ui.AppViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CORE FEATURES C1 / C2 end to end (docs/core-features.md): the real app, the real MapLibre map, a finger held on the
 * screen (UiAutomator). Runs on an emulator before every release (.github/workflows/e2e.yml, release.yml) – owner
 * 2026-10-21: "not good to find out during walking".
 *
 * Map data: a tiny generated package (empty base tile, flat terrain, empty overlays, a dummy routing file → straight
 * lines). Enough for the gestures, which do not depend on what the map shows.
 */
@RunWith(AndroidJUnit4::class)
class CorePlanE2ETest {
    @get:Rule val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS)
    else GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("onpath", 0).edit().clear().commit() // empty plan, default camera (Meran)
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "onpath")
        dir.deleteRecursively(); dir.mkdirs()
        writeTinyMapPackage(dir)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForMap()
    }

    @After fun tearDown() { if (::scenario.isInitialized) scenario.close() }

    // ------------------------------------------------------------------------------------------------ tests

    /** C2 + C1: hold the empty map → Start here; hold elsewhere → point added; hold that point → menu → Delete. */
    @Test fun holdAddsPointsAndHoldOnAPointOpensItsMenuToDelete() {
        startPlanWithTwoPoints()
        holdOnWaypoint(1)
        clickText("Delete")
        waitUntil("point deleted") { waypointCount() == 1 }
    }

    /** C1 after the map style reloads (a map update installs a new generation → the style is loaded again). */
    @Test fun holdOnAPointWorksAfterTheMapReloaded() {
        startPlanWithTwoPoints()
        scenario.onActivity { vm(it).reloadMapStyleForTest() }
        waitForMap(reloaded = true)
        holdOnWaypoint(1)
        assertNotNull("point menu after a style reload", device.wait(Until.findObject(By.text("Delete")), 5_000))
        clickText("Delete")
        waitUntil("point deleted after reload") { waypointCount() == 1 }
    }

    /** C1 on a turned map (compass, hand rotation – the 0.7.19 bug class). */
    @Test fun holdOnAPointWorksOnATurnedMap() {
        startPlanWithTwoPoints()
        // turn around the middle of the route, so both points stay above the plan sheet
        var mid: LatLng? = null
        scenario.onActivity { a -> vm(a).waypoints.let { w -> mid = LatLng((w[0].lat + w[1].lat) / 2, (w[0].lon + w[1].lon) / 2) } }
        onMap { map ->
            map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                org.maplibre.android.camera.CameraPosition.Builder().target(mid).bearing(137.0).build()))
        }
        Thread.sleep(500)
        holdOnWaypoint(0)
        assertNotNull("point menu on a turned map", device.wait(Until.findObject(By.text("Delete")), 5_000))
    }

    // ------------------------------------------------------------------------------------------------ steps

    private fun startPlanWithTwoPoints() {
        val (w, h) = mapSize()
        hold(w * 0.35f, h * 0.40f)
        clickText("Start here")
        waitUntil("first point") { waypointCount() == 1 }
        hold(w * 0.65f, h * 0.45f)
        waitUntil("second point by a hold") { waypointCount() == 2 }
        Thread.sleep(800) // route (straight line) + camera settle
    }

    private fun holdOnWaypoint(index: Int) {
        val p = waypointOnScreen(index)
        shot("before-hold-${index + 1}")
        hold(p.first, p.second)
        if (device.wait(Until.findObject(By.text("Delete")), 5_000) == null) {
            shot("after-hold-${index + 1}")
            fail("hold on point ${index + 1} at (${p.first.toInt()}, ${p.second.toInt()}) did not open its menu – ${state()}")
        }
    }

    /** Screenshot into /sdcard/e2e (pulled by the workflow as an artifact). */
    private fun shot(name: String) {
        val test = Thread.currentThread().stackTrace.firstOrNull { it.className == javaClass.name && it.methodName.startsWith("hold") && it.methodName != "hold" && it.methodName != "holdOnWaypoint" }?.methodName ?: "test"
        device.executeShellCommand("mkdir -p /sdcard/e2e")
        device.executeShellCommand("screencap -p /sdcard/e2e/$test-$name.png")
    }

    /** What the app thinks right now (failure messages). */
    private fun state(): String {
        var s = ""
        scenario.onActivity { a ->
            val vm = vm(a)
            val mv = mapView(a)
            s = "points ${vm.waypoints.size}, mode ${vm.mode}, menu ${vm.pointMenu}, overlayOpen ${vm.mapOverlayOpen}, " +
                "canEdit ${vm.canEditMap}, peek ${vm.routePeek}, map ${mv?.width}x${mv?.height}"
        }
        val problems = com.onpath.app.debug.Problems.snapshot().take(8).joinToString(" | ") { "${it.level} ${it.area}: ${it.message}" }
        return "$s; problems: $problems"
    }

    /** A finger resting [ms] at a screen point (the app's hold is 0.3 s; MapLibre's long press ~0.5 s). */
    private fun hold(x: Float, y: Float, ms: Int = 900) {
        device.swipe(x.toInt(), y.toInt(), x.toInt(), y.toInt(), ms / 5) // one step ≈ 5 ms
        Thread.sleep(400)
    }

    private fun clickText(text: String) {
        val o = device.wait(Until.findObject(By.text(text)), 5_000) ?: fail("\"$text\" not shown").let { return }
        o.click()
        Thread.sleep(300)
    }

    // ------------------------------------------------------------------------------------------------ app access

    private fun vm(a: MainActivity) = ViewModelProvider(a)[AppViewModel::class.java]

    private fun waypointCount(): Int { var n = -1; scenario.onActivity { n = vm(it).waypoints.size }; return n }

    private fun mapView(a: MainActivity): MapView? {
        fun find(v: View): MapView? = when (v) {
            is MapView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(a.window.decorView)
    }

    private fun onMap(block: (org.maplibre.android.maps.MapLibreMap) -> Unit) {
        val done = CountDownLatch(1)
        scenario.onActivity { a -> mapView(a)!!.getMapAsync { m -> block(m); done.countDown() } }
        done.await(5, TimeUnit.SECONDS)
    }

    private fun mapSize(): Pair<Float, Float> {
        var r = 0f to 0f
        scenario.onActivity { a -> mapView(a)!!.let { r = it.width.toFloat() to it.height.toFloat() } }
        return r
    }

    /** Screen position (device pixels) of route point [index], as MapLibre projects it now. */
    private fun waypointOnScreen(index: Int): Pair<Float, Float> {
        var r = 0f to 0f
        val done = CountDownLatch(1)
        scenario.onActivity { a ->
            val p = vm(a).waypoints[index]
            val mv = mapView(a)!!
            val loc = IntArray(2).also { mv.getLocationOnScreen(it) }
            mv.getMapAsync { m ->
                val s = m.projection.toScreenLocation(LatLng(p.lat, p.lon))
                r = (loc[0] + s.x) to (loc[1] + s.y); done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        return r
    }

    private fun waitForMap(reloaded: Boolean = false) {
        waitUntil("map shown", 30_000) {
            var ok = false
            scenario.onActivity { a -> ok = mapView(a)?.let { it.width > 0 } == true }
            ok
        }
        Thread.sleep(if (reloaded) 2_500 else 3_000) // style + app layers
    }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timeout: $what (points: ${waypointCount()})")
            Thread.sleep(100)
        }
    }

    // ------------------------------------------------------------------------------------------------ test data

    /** The smallest map package the app accepts (format 1): see DataFiles.check. */
    private fun writeTinyMapPackage(dir: File) {
        val emptyMvt = PmTiles.gzip(ByteArray(0))
        PmTiles.write(mapOf(Triple(0, 0, 0) to emptyMvt), File(dir, "base.pmtiles"))
        val flat = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 0, 0)) } // terrarium 0 m
        val png = ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        PmTiles.write(mapOf(Triple(0, 0, 0) to png), File(dir, "terrain.pmtiles"), tileType = 2 /* PNG */,
            tileCompression = PmTiles.COMPRESSION_NONE)
        val empty = """{"type":"FeatureCollection","features":[]}"""
        File(dir, "routes.geojson").writeText(empty)
        File(dir, "trails.geojson").writeText(empty)
        File(dir, "segments").mkdirs()
        File(dir, "segments/E10_N45.rd5").writeBytes(ByteArray(1024)) // not real data: routing falls back to straight lines
    }
}

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

/** Shared steps of the emulator tests (finger holds, app state, generated map data). */
abstract class E2EBase {
    @get:Rule val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS)
    else GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @get:Rule val testName = org.junit.rules.TestName()
    protected val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    protected lateinit var scenario: ActivityScenario<MainActivity>
    protected val dataDir: File get() = InstrumentationRegistry.getInstrumentation().targetContext.let { File(it.getExternalFilesDir(null) ?: it.filesDir, "onpath") }

    /** Clean app state + the tiny map package; [beforeLaunch] runs before the app starts (e.g. a GPS fix). */
    protected fun launch(beforeLaunch: () -> Unit = {}) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // empty plan, default camera (Meran); the one-time "Allow all the time" question already answered (it would
        // cover the map on the first tour)
        ctx.getSharedPreferences("onpath", 0).edit().clear().putBoolean("backgroundAsked", true).commit()
        // the one-time restore offer on an empty tour list (backup) would cover the map too
        ctx.getSharedPreferences("backup", 0).edit().putBoolean("restoreOffered", true).commit()
        val dir = dataDir
        dir.deleteRecursively(); dir.mkdirs()
        writeTinyMapPackage(dir)
        gpsAt(MERAN_LAT, MERAN_LON) // the CI emulator sits in California: the app would load that area first (modal)
        beforeLaunch()
        wakeScreen()
        dismissSystemDialogs()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        showOverLockScreen()
        waitForMap()
        dismissSystemDialogs()
        waitNoLoadModal()
    }

    /** A GPS fix from a test provider (shell `cmd location`, Android 12+). */
    protected fun gpsAt(lat: Double, lon: Double) {
        device.executeShellCommand("appops set com.android.shell android:mock_location allow")
        device.executeShellCommand("cmd location providers add-test-provider gps")
        device.executeShellCommand("cmd location providers set-test-provider-enabled gps true")
        device.executeShellCommand("cmd location providers set-test-provider-location gps --location $lat,$lon --accuracy 4")
    }

    /**
     * The loading modal ("Loading the map for your area") lies over the map and takes every touch – on a fresh app it
     * opens for the own position (correct in the field). Tests that are not about loading wait until it is gone.
     */
    protected fun waitNoLoadModal(timeoutMs: Long = 90_000) {
        waitUntil("no loading window over the map", timeoutMs) {
            var none = false
            scenario.onActivity { a -> val vm = vm(a); none = vm.mapLoadUi(vm.cells.states.value, android.os.SystemClock.uptimeMillis()) == null }
            none
        }
    }

    /**
     * A real phone (owner's Pixel, 2026-10-21) dozes and keeps a PIN/fingerprint lock: every injected touch then went
     * to a black, locked screen ("Start here" never shown, no hold event at all). Wake it and keep it awake for the run.
     * A secure lock cannot be dismissed from adb, so the activity is shown over it instead (see [showOverLockScreen]).
     */
    protected fun wakeScreen() {
        if (!device.isScreenOn) device.wakeUp()
        device.executeShellCommand("svc power stayon true")
        // Swipe-only lock: dismiss it. Never with a PIN/fingerprint lock: there "dismiss" opens the fingerprint bouncer,
        // which lies over our (shown-when-locked) window and swallows every touch – the 2026-10-21 phone failure.
        val km = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isDeviceSecure == false) device.executeShellCommand("wm dismiss-keyguard")
        Thread.sleep(300)
    }

    /** The app's window over the (secure) lock screen, screen kept on – test only, the app itself does not do this. */
    protected fun showOverLockScreen() {
        scenario.onActivity { a ->
            if (Build.VERSION.SDK_INT >= 27) { a.setShowWhenLocked(true); a.setTurnScreenOn(true) }
            else @Suppress("DEPRECATION") a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (!device.isScreenOn) device.wakeUp()
        device.waitForIdle(2_000)
    }

    /** True when the real BRouter data is on the emulator (routes follow ways, points are snapped). */
    protected var realRouting = false

    @After fun closeApp() {
        if (::scenario.isInitialized) scenario.close()
        device.executeShellCommand("cmd location providers remove-test-provider gps")
        device.executeShellCommand("svc power stayon false") // the phone's normal screen timeout again
    }

    // ------------------------------------------------------------------------------------------------ steps

    protected fun startPlanWithTwoPoints() {
        val (w, h) = mapSize()
        hold(w * 0.35f, h * 0.40f)
        clickText("Start here")
        waitUntil("first point") { waypointCount() == 1 }
        hold(w * 0.65f, h * 0.45f)
        waitUntil("second point by a hold") { waypointCount() == 2 }
        Thread.sleep(800) // route (straight line) + camera settle
    }

    protected fun holdOnWaypoint(index: Int) {
        ensureOnOpenMap(index)
        val p = waypointOnScreen(index)
        shot("before-hold-${index + 1}")
        hold(p.first, p.second)
        if (device.wait(Until.findObject(By.text("Delete")), 5_000) == null) {
            shot("after-hold-${index + 1}")
            fail("hold on point ${index + 1} at (${p.first.toInt()}, ${p.second.toInt()}) did not open its menu – ${state()}")
        }
    }

    /**
     * Points snapped onto ways can end up under the search pill (top) or the plan sheet (bottom) – a finger there never
     * reaches the map (CI 2026-10-22: point 3 at y 361). Centre the map on the point first, like a user would.
     */
    protected fun ensureOnOpenMap(index: Int) {
        val (_, h) = mapSize()
        val (_, y) = waypointOnScreen(index)
        if (y in h * 0.25f..h * 0.55f) return
        var p: LatLng? = null
        scenario.onActivity { a -> vm(a).followMe = false; vm(a).waypoints[index].let { p = LatLng(it.lat, it.lon) } }
        onMap { m -> m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLng(p!!)) }
        Thread.sleep(600)
    }

    /** Screenshot into /sdcard/e2e (pulled by the workflow as an artifact). */
    protected fun shot(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/e2e")
        device.executeShellCommand("screencap -p /sdcard/e2e/${javaClass.simpleName}-${testName.methodName}-$name.png")
        // what UiAutomator sees (accessibility tree) – a text on screen it cannot find shows up here
        try { device.dumpWindowHierarchy(java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "${javaClass.simpleName}-${testName.methodName}-$name.xml")) } catch (_: Exception) {} // expected: diagnostics only
        device.executeShellCommand("sh -c 'cp /sdcard/Android/data/com.onpath.app/files/*.xml /sdcard/e2e/ 2>/dev/null'")
    }

    /** What the app thinks right now (failure messages). */
    protected fun state(): String {
        var s = ""
        scenario.onActivity { a ->
            val vm = vm(a)
            val mv = mapView(a)
            s = "points ${vm.waypoints.size}, mode ${vm.mode}, menu ${vm.pointMenu}, overlayOpen ${vm.mapOverlayOpen}, " +
                "canEdit ${vm.canEditMap}, peek ${vm.routePeek}, map ${mv?.width}x${mv?.height}"
        }
        val problems = com.onpath.app.debug.Problems.snapshot().take(8).joinToString(" | ") { "${it.level} ${it.area}: ${it.message}" }
        val events = com.onpath.app.debug.Problems.events().take(8).joinToString(" | ") { "${it.area}: ${it.message}" }
        return "$s; events (newest first): $events; problems: $problems"
    }

    /** A finger resting [ms] at a screen point (the app's hold is 0.3 s; MapLibre's long press ~0.5 s). */
    protected fun hold(x: Float, y: Float, ms: Int = 900) {
        dismissSystemDialogs()
        device.swipe(x.toInt(), y.toInt(), x.toInt(), y.toInt(), ms / 5) // one step ≈ 5 ms
        Thread.sleep(400)
    }

    /**
     * CI emulators under load show "… isn't responding" / system dialogs over the app; they swallow every touch.
     * Close them (Wait), so a test fails only for the app's own reasons.
     */
    protected fun dismissSystemDialogs() {
        device.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        for (label in listOf("Wait", "Close app", "OK")) {
            val anr = device.findObject(By.textContains("isn't responding")) ?: break
            device.findObject(By.text(label))?.click(); Thread.sleep(500)
            if (anr.let { device.findObject(By.textContains("isn't responding")) } == null) break
        }
    }

    protected fun clickText(text: String) {
        var o = device.wait(Until.findObject(By.text(text)), 5_000)
        if (o == null) { dismissSystemDialogs(); o = device.wait(Until.findObject(By.text(text)), 2_000) }
        if (o == null) { shot("missing-${text.filter { it.isLetter() }}"); fail("\"$text\" not shown – ${state()}"); return }
        o.click()
        Thread.sleep(300)
    }

    // ------------------------------------------------------------------------------------------------ app access

    protected fun vm(a: MainActivity) = ViewModelProvider(a)[AppViewModel::class.java]

    protected fun waypointCount(): Int { var n = -1; scenario.onActivity { n = vm(it).waypoints.size }; return n }

    protected fun mapView(a: MainActivity): MapView? {
        fun find(v: View): MapView? = when (v) {
            is MapView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(a.window.decorView)
    }

    protected fun onMap(block: (org.maplibre.android.maps.MapLibreMap) -> Unit) {
        val done = CountDownLatch(1)
        scenario.onActivity { a -> mapView(a)!!.getMapAsync { m -> block(m); done.countDown() } }
        done.await(5, TimeUnit.SECONDS)
    }

    protected fun mapSize(): Pair<Float, Float> {
        var r = 0f to 0f
        scenario.onActivity { a -> mapView(a)!!.let { r = it.width.toFloat() to it.height.toFloat() } }
        return r
    }

    /** Screen position (device pixels) of route point [index], as MapLibre projects it now. */
    protected fun waypointOnScreen(index: Int): Pair<Float, Float> {
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

    protected fun waitForMap(reloaded: Boolean = false) {
        waitUntil("map shown", 30_000) {
            var ok = false
            scenario.onActivity { a -> ok = mapView(a)?.let { it.width > 0 } == true }
            ok
        }
        Thread.sleep(if (reloaded) 2_500 else 3_000) // style + app layers
    }

    protected fun waitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timeout: $what (points: ${waypointCount()})")
            Thread.sleep(100)
        }
    }

    // ------------------------------------------------------------------------------------------------ test data

    /** The smallest map package the app accepts (format 1): see DataFiles.check. */
    protected fun writeTinyMapPackage(dir: File) {
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
        // Real routing data when the workflow pushed it (/data/local/tmp, cached per week), else a dummy file:
        // routing then falls back to straight lines.
        val real = "/data/local/tmp/E10_N45.rd5"
        val rd5 = File(dir, "segments/E10_N45.rd5")
        if (device.executeShellCommand("ls $real").trim() == real) {
            device.executeShellCommand("cp $real ${rd5.absolutePath}")
        }
        if (!rd5.isFile || rd5.length() < 1_000_000) rd5.writeBytes(ByteArray(1024))
        realRouting = rd5.length() > 1_000_000
    }

    companion object {
        const val MERAN_LAT = 46.6714
        const val MERAN_LON = 11.1700
    }
}

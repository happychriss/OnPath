package com.onpath.app.e2e

import android.Manifest
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
import com.onpath.app.debug.Problems
import com.onpath.app.ui.AppViewModel
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView
import java.io.File

/**
 * Fresh install (phone test 2026-10-21): no map package, no map cell, no routing data. With the overview map in the
 * APK and map downloads in the build the app opens on the map (DataFiles.blocking) and the style must not point
 * MapLibre at missing files ("Failed to load source terrain … path not found" was announced as a problem); without
 * them the "Offline map data is missing" screen shows as before. Both builds pass – the test checks the right one.
 */
@RunWith(AndroidJUnit4::class)
class FreshInstallE2ETest {
    @get:Rule val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS)
    else GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var scenario: ActivityScenario<MainActivity>

    @After fun tearDown() { if (::scenario.isInitialized) scenario.close() }

    @Test fun freshInstallOpensOnTheMapOrSaysWhatIsMissing() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("onpath", 0).edit().clear().commit()
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "onpath")
        dir.deleteRecursively(); dir.mkdirs()
        Problems.clear()
        scenario = ActivityScenario.launch(MainActivity::class.java)

        var blocking = true
        scenario.onActivity { a -> blocking = ViewModelProvider(a)[AppViewModel::class.java].blockingMissing.isNotEmpty() }
        if (blocking) {
            // no overview in this APK or no map server key: the missing-data screen, as before cells
            assertNotNull("missing-data screen", device.wait(Until.findObject(By.text("Offline map data is missing")), 15_000))
            return
        }
        val end = System.currentTimeMillis() + 30_000
        while (true) {
            var shown = false
            scenario.onActivity { a -> shown = mapView(a)?.let { it.width > 0 } == true }
            if (shown) break
            if (System.currentTimeMillis() > end) fail("map not shown on a fresh install")
            Thread.sleep(200)
        }
        Thread.sleep(5_000) // style loaded, MapLibre would have reported missing sources by now
        val mapErrors = Problems.snapshot().filter { it.level == Problems.Level.ERROR && it.area == "map" }
        assertTrue("map errors on a fresh install: ${mapErrors.joinToString { it.message }}", mapErrors.isEmpty())
        assertTrue("missing-data screen must not show", device.findObject(By.text("Offline map data is missing")) == null)
    }

    private fun mapView(a: MainActivity): MapView? {
        fun find(v: View): MapView? = when (v) {
            is MapView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(a.window.decorView)
    }
}

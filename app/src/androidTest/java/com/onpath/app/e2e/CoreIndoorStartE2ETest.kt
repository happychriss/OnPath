package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.onpath.app.data.Geo
import com.onpath.app.data.TourDb
import com.onpath.app.ui.AppMode
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import kotlin.concurrent.thread

/**
 * CORE FEATURE C5 / C4, a tour started indoors (owner): only Wi-Fi / fused fixes (±60 m), no GPS → Navigate shows the
 * amber "Approximate position" line on the Wi-Fi position, nothing is recorded; the first GPS fix (outside, 150 m
 * away) ends it silently and the track holds GPS points only – none at the Wi-Fi position.
 */
@RunWith(AndroidJUnit4::class)
class CoreIndoorStartE2ETest : E2EBase() {
    @Volatile private var feeding = true
    @Volatile private var outside = false
    private val inLat = 46.6714
    private val inLon = 11.1700
    @Volatile private var gpsLat = 46.6714
    private val gpsLon = 11.1720 // ≈ 150 m east of the Wi-Fi position

    private fun sh(cmd: String) = device.executeShellCommand(cmd)

    @Before fun setUp() {
        sh("appops set com.android.shell android:mock_location allow")
        for (p in PROVIDERS) {
            sh("cmd location providers add-test-provider $p")
            sh("cmd location providers set-test-provider-enabled $p true")
        }
        // indoors: Wi-Fi and fused fixes every second; GPS only once "outside"
        thread(isDaemon = true) {
            while (feeding) {
                for (p in listOf("network", "fused")) sh("cmd location providers set-test-provider-location $p --location $inLat,$inLon --accuracy 60")
                if (outside) { gpsLat += 0.00001; sh("cmd location providers set-test-provider-location gps --location $gpsLat,$gpsLon --accuracy 4") }
                Thread.sleep(1_000)
            }
        }
        launch() // its one GPS fix (start area) goes stale after 30 s: then the Wi-Fi position stands in
        waitUntil("Wi-Fi position in the app", 60_000) {
            var ok = false; scenario.onActivity { ok = vm(it).tracker.location.value?.provider.let { p -> p == "network" || p == "fused" } }; ok
        }
    }

    @After fun stopFeeding() {
        feeding = false
        try { scenario.onActivity { if (vm(it).recState.value != null) vm(it).stopRecording() } } catch (_: Exception) {} // expected: app already closed
        for (p in PROVIDERS) sh("cmd location providers remove-test-provider $p")
    }

    private companion object {
        val PROVIDERS = listOf("gps", "network", "fused")
        val APPROX: Pattern = Pattern.compile("(?i).*(approximate position|ungefähre position).*")
    }

    private fun hadGps(): Boolean { var g = true; scenario.onActivity { g = vm(it).recState.value?.hadGpsFix ?: true }; return g }

    @Test fun indoorStartShowsTheWifiPositionRecordsOnlyGps() {
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording started)", 20_000) { var m: AppMode? = null; scenario.onActivity { m = vm(it).mode }; m == AppMode.NAVIGATE }
        if (findFresh(By.text(APPROX), 15_000) == null) { shot("no-approx-line"); throw AssertionError("no \"Approximate position\" line – ${state()}") }
        assertFalse("no GPS fix yet", hadGps())
        Thread.sleep(4_000)
        var tourId = -1L
        scenario.onActivity { tourId = vm(it).recState.value?.tourId ?: -1L }
        val db = TourDb.get(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("no track point from Wi-Fi fixes", db.loadSegments(tourId).sumOf { it.size } == 0)

        outside = true
        waitUntil("first GPS fix in the tour", 30_000) { hadGps() }
        assertTrue("the approximate line goes with the first GPS fix", goneFresh(By.text(APPROX), 15_000))
        Thread.sleep(5_000)
        val pts = db.loadSegments(tourId).flatten()
        assertTrue("GPS points recorded: ${pts.size}", pts.isNotEmpty())
        val nearWifi = pts.count { Geo.distance(it.lat, it.lon, inLat, inLon) < 60 }
        assertTrue("no point at the Wi-Fi position ($nearWifi)", nearWifi == 0)
    }
}

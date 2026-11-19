package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The plan sheet's wait line while a route is calculated, each phase its own progress (owner 2026-11-18,
 * [PercentStallTest]): "Abschnitt 2 von 4 wird berechnet…" with the bar from the router's search; a share that stands
 * (one leg, the search at the goal but not done) = the running bar; every leg routed = "Route wird geprüft…" with the
 * running bar. EN, DE, DE at the largest font. Pictures from [ScreenshotTest].
 */
abstract class RouteWaitScreenshotTest(private val locale: Locale, fontScale: Float) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = fontScale),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun lang() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun legs() = shots.planRoutingSlowProgress()
    @Test fun finishing() = shots.planRoutingSlowFinishing()
}

class RouteWaitEnglishTest : RouteWaitScreenshotTest(Locale.US, 1f)
class RouteWaitGermanTest : RouteWaitScreenshotTest(Locale.GERMANY, 1f)
class RouteWaitLargeFontGermanTest : RouteWaitScreenshotTest(Locale.GERMANY, 2f)

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
 * Owner 2026-11-08 (father's feedback on a Galaxy S10): the plan screen with the new undo pill ("↶ Rückgängig |
 * ↷ Wiederholen" under the map column, never over the scale bar) at the S10's width (≈ 411 dp) and at 360 dp, German.
 * Same pictures as [ScreenshotTest.plan], drawn with this class's Paparazzi.
 */
abstract class PlanSmallPhone(widthDp411: Boolean) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2280,
            xdpi = if (widthDp411) 420 else 480, ydpi = if (widthDp411) 420 else 480,
            density = if (widthDp411) Density.create(420) else Density.XXHIGH),
        showSystemUi = false,
    )

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun plan() = shots.plan()
}

class PlanS10GermanTest : PlanSmallPhone(widthDp411 = true)
class Plan360GermanTest : PlanSmallPhone(widthDp411 = false)

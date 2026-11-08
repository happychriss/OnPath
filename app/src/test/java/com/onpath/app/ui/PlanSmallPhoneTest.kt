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
 * Owner 2026-11-08 (father's feedback on a Galaxy S10): the plan screen with the undo pill ("↶ Rückgängig |
 * ↷ Wiederholen" right above the map column, evening: not in the middle of the map) at the S10's width (≈ 411 dp) and at
 * 360 dp, font 1.0 and 1.3, German.
 * Same pictures as [ScreenshotTest.plan], drawn with this class's Paparazzi.
 */
abstract class PlanSmallPhone(widthDp411: Boolean, font: Float = 1.0f) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2280,
            xdpi = if (widthDp411) 420 else 480, ydpi = if (widthDp411) 420 else 480,
            density = if (widthDp411) Density.create(420) else Density.XXHIGH, fontScale = font),
        showSystemUi = false,
    )

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun plan() = shots.plan()
    @Test fun planSummaryStep() = shots.planPointsSmallShot()
}

class PlanS10GermanTest : PlanSmallPhone(widthDp411 = true)
class Plan360GermanTest : PlanSmallPhone(widthDp411 = false)
class PlanS10LargeFontGermanTest : PlanSmallPhone(widthDp411 = true, font = 1.3f)
class Plan360LargeFontGermanTest : PlanSmallPhone(widthDp411 = false, font = 1.3f)

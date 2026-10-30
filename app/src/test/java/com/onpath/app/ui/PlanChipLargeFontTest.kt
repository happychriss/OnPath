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
 * The plan sheet's first line at the phone's largest font (2.0): the activity chip ("Hike · T2" / "Wandern · T2") beside
 * time and distance. Same pictures as the English tests of the same name, drawn with this class's Paparazzi.
 */
open class PlanChipLargeFontTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420), fontScale = 2.0f,
        ),
        showSystemUi = false,
    )

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun plan() = shots.plan()
    @Test fun planChipLongHike() = shots.planChipLongHike()
    @Test fun planChipLongBikeTour() = shots.planChipLongBikeTour()
}

/** The same in German. */
class PlanChipLargeFontGermanTest : PlanChipLargeFontTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

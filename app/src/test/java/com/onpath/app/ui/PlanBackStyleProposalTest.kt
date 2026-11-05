package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Proposal 2026-11-05 (owner, HOLD): "back to the tour" in one row – R1 round "←", R2 "‹ Tour" chip, the two-row one. German. */
open class PlanBackStyleProposalTest {
    @get:Rule
    open val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun r1Round() = shots.planBackStyle(BackToTourStyle.ROUND)
    @Test fun r2Chip() = shots.planBackStyle(BackToTourStyle.CHIP)
    @Test fun twoRows() = shots.planBackStyle(BackToTourStyle.TWO_ROWS)
}

/** The same at the phone's largest font (2.0). */
class PlanBackStyleProposalLargeFontTest : PlanBackStyleProposalTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = 2.0f),
        showSystemUi = false,
    )
}

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
 * Proposal 2026-11-05 (owner, no decision yet): Plan's top area as a band behind the search pill and ☰ – (a) plan blue,
 * white field + ☰; (b) light blue, today's pill; (c) light blue fading into the map. Profile step, German. Pictures for
 * the owner only – remove when decided.
 */
open class PlanTopBandProposalTest {
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

    @Test fun bandA() = shots.planTopBand('a')
    @Test fun bandB() = shots.planTopBand('b')
    @Test fun bandC() = shots.planTopBand('c')
}

/** The same at the phone's largest font (2.0). */
class PlanTopBandProposalLargeFontTest : PlanTopBandProposalTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = 2.0f),
        showSystemUi = false,
    )
}

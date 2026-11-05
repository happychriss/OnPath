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
 * Design proposal 2026-11-05 (owner: the plan sheet's bottom row compact – Material 3 + Google Maps logic): today's bar
 * and four proposals ([PlanBarStyle]), each without a tour and during a tour, in German. Pictures for the owner only.
 */
open class PlanBarProposalTest {
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

    @Test fun todayPlan() = shots.planBarProposal(PlanBarStyle.TODAY, tour = false)
    @Test fun todayTour() = shots.planBarProposal(PlanBarStyle.TODAY, tour = true)
    @Test fun v1GroupPlan() = shots.planBarProposal(PlanBarStyle.F_GROUP, tour = false)
    @Test fun v1GroupTour() = shots.planBarProposal(PlanBarStyle.F_GROUP, tour = true)
    @Test fun v2PillsPlan() = shots.planBarProposal(PlanBarStyle.MAPS_PILLS, tour = false)
    @Test fun v2PillsTour() = shots.planBarProposal(PlanBarStyle.MAPS_PILLS, tour = true)
    @Test fun v3IconsPlan() = shots.planBarProposal(PlanBarStyle.ICON_BUTTONS, tour = false)
    @Test fun v3IconsTour() = shots.planBarProposal(PlanBarStyle.ICON_BUTTONS, tour = true)
    @Test fun v4SplitPlan() = shots.planBarProposal(PlanBarStyle.SPLIT, tour = false)
    @Test fun v4SplitTour() = shots.planBarProposal(PlanBarStyle.SPLIT, tour = true)
}

/** The same at the phone's largest font (2.0). */
class PlanBarProposalLargeFontTest : PlanBarProposalTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = 2.0f),
        showSystemUi = false,
    )
}

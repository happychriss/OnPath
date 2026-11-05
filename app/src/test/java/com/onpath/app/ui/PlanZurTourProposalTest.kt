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
 * Proposal 2026-11-05 (owner OK for pictures, no app change yet): Plan with the light blue band (b); during a tour
 * "‹ Zur Tour" top left in the band (Android up navigation) and no green button in the sheet (the profile gets the room,
 * the rolled-up row only time · km); without a tour band b and "▶ Los geht's" as now. German; remove when decided.
 */
open class PlanZurTourProposalTest {
    @get:Rule
    open val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    open val locale: Locale = Locale.GERMANY
    private val before = Locale.getDefault()
    @Before fun lang() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun tourProfile() = shots.planZurTour(tour = true, step = "profile")
    @Test fun tourRolled() = shots.planZurTour(tour = true, step = "rolled")
    @Test fun planProfile() = shots.planZurTour(tour = false, step = "profile")
}

/** The same at the phone's largest font (2.0). */
class PlanZurTourProposalLargeFontTest : PlanZurTourProposalTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = 2.0f),
        showSystemUi = false,
    )
}

/** One English shot of the tour (profile step). */
class PlanZurTourProposalEnglishTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun lang() = Locale.setDefault(Locale.US)
    @After fun back() = Locale.setDefault(before)

    @Test fun tourProfile() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }.planZurTour(tour = true, step = "profile")
}

package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The found place's menu at the phone's largest font (2.0, owner 2026-11-13 "A mit Ausweich B"): it does not fit above
 * the pin, so the same rows show as a card at the bottom with the name and address on top; its rows wrap ("In Google
 * Maps öffnen"), never cut. The name chip under the pin after Back. Same pictures as the [ScreenshotTest] tests of the
 * same name, drawn with this class's Paparazzi; German only (the English twin at this size adds nothing).
 */
class FoundPlaceMenuLargeFontGermanTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f), showSystemUi = false)

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun sharedPlaceMenu() = shots.sharedPlaceMenu(ScreenshotTest.PIN_CARD_Y)
    @Test fun sharedPlaceMenuEmptyPlan() = shots.sharedPlaceMenuEmptyPlan(ScreenshotTest.PIN_CARD_Y)
    @Test fun foundPlacePin() = shots.foundPlacePin()
}

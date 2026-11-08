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
 * Navigate's status card at a real phone's size (1080 × 2400, 420 dpi) with the arrival clock "Ankunft 16:40", moving and
 * stopped, in DE, EN and DE at the largest font (owner 2026-11-08, v6 + v7): the stopped head and its calm line over the
 * full width (0.17.5 hid "Aufzeichnung und Navigation ruhen" here), and at a large font the arrival under the numbers –
 * never "↓…" or "km…" ([CardFit]).
 */
abstract class StatusCardPhoneShots(private val locale: Locale) {
    abstract val paparazzi: Paparazzi
    private val before = Locale.getDefault()
    @Before fun locale() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val shots: ScreenshotTest get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun moving() = shots.navigateArrive()
    @Test fun stopped() = shots.navigatePausedLockedArrive()
}

private val phone = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, xdpi = 420, ydpi = 420, density = Density.create(420))

class StatusCardPhoneScreenshotTest : StatusCardPhoneShots(Locale.US) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = phone, showSystemUi = false)
}

class StatusCardPhoneGermanScreenshotTest : StatusCardPhoneShots(Locale.GERMANY) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = phone, showSystemUi = false)
}

class StatusCardPhoneLargeFontTest : StatusCardPhoneShots(Locale.GERMANY) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = phone.copy(fontScale = 2.0f), showSystemUi = false)
}

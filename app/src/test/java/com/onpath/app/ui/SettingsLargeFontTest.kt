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
 * Settings in value rows (owner 2026-11-06 23:05) at a large phone font (1.3, Android's largest default step): the value
 * word and the title wrap instead of being cut, the indented rows stay readable. Same pictures as [SettingsScreenshotTest],
 * drawn with this class's Paparazzi.
 */
/** English frames dropped (test audit 2026-11-07: the German twin at the same font size is the check); the subclasses draw. */
abstract class SettingsLargeFontTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = 1.3f),
        showSystemUi = false,
    )

    /** [SettingsScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = SettingsScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun navigation() = shots.navigation()
    @Test fun onTheWay() = shots.onTheWay()
    @Test fun navigationPhoneMissing() = shots.navigationPhoneMissing()
    @Test fun navigationChoiceSheet() = shots.navigationChoiceSheet()
    @Test fun pace() = shots.pace()
    @Test fun map() = shots.map()
    /** About with the website links: the longer addresses wrap, the ↗ stays at the right. */
    @Test fun about() = shots.aboutTall(4400, 1.3f)
}

/** The same in German. */
class SettingsLargeFontGermanTest : SettingsLargeFontTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

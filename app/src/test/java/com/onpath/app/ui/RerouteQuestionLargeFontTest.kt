package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * "Off route – re-plan?" at the phone's largest font (2.0) on a normal phone: the buttons are stacked full width,
 * "Yes, skip stop 3" / "Ja, Stopp 3 auslassen" wraps to at most two lines, a long stop name becomes "stop 3". Same
 * pictures as the [ScreenshotTest] tests of the same name, drawn with this class's Paparazzi.
 */
open class RerouteQuestionLargeFontTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f), showSystemUi = false)

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun navigateRerouteSkipStop() = shots.navigateRerouteSkipStop()
    @Test fun navigateRerouteSkipStopNamed() = shots.navigateRerouteSkipStopNamed()
    /** The plain question beside it: its two buttons stay side by side. */
    @Test fun navigateRerouteQuestion() = shots.navigateRerouteQuestion()
}

/** The same in German. */
class RerouteQuestionLargeFontGermanTest : RerouteQuestionLargeFontTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

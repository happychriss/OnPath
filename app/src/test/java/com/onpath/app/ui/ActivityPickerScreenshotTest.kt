package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.routing.Activity
import org.junit.Rule
import org.junit.Test

/** Activity picker: with Hike the T1–T5 row and the level's text; bike activities without it. */
class ActivityPickerScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false)

    @Test fun pickerHikeT2() = paparazzi.snapshot {
        OnPathTheme { ActivityDialog(Activity.HIKE, onSelect = {}, onDismiss = {}, sac = 2) }
    }

    @Test fun pickerMtb() = paparazzi.snapshot {
        OnPathTheme { ActivityDialog(Activity.MTB, onSelect = {}, onDismiss = {}, sac = 2) }
    }
}

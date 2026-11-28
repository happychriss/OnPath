package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The PLANEN tab in the sheet's handle strip (2026-11-28: at the largest font its text was cut at the top – the strip was a
 * fixed 24 dp): the strip grows with the tab, the tab's text shows whole – also the recording tab „● PLANEN · 0:42 ■“.
 * German, normal and largest font (2.0).
 */
abstract class PlanModeTabTest(fontScale: Float) {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = fontScale), showSystemUi = false)
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    private val laid = ArrayList<TextLayoutResult>()

    @Test fun tabInTheHandleStrip() {
        paparazzi.snapshot {
            OnPathTheme {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().background(OnPathColors.PlanTint).padding(horizontal = 16.dp)) {
                    HandleStrip("Größe ändern", OnPathColors.PlanAccent, {}) { PlanModeLabel(editing = false, laidOut = { laid += it }) }
                    HandleStrip("Größe ändern", OnPathColors.PlanAccent, {}) { PlanModeLabel(editing = false, recElapsedS = 42 * 60, laidOut = { laid += it }) }
                }
            }
        }
        assertEquals("both tabs laid out", 2, laid.map { it.layoutInput.text.text }.distinct().size)
        for (r in laid) {
            assertFalse("cut: ${r.layoutInput.text}", r.hasVisualOverflow)
            assertFalse("cut in height: ${r.layoutInput.text}", r.didOverflowHeight)
        }
    }
}

class PlanModeTabGermanTest : PlanModeTabTest(1.0f)
class PlanModeTabLargeFontGermanTest : PlanModeTabTest(2.0f)

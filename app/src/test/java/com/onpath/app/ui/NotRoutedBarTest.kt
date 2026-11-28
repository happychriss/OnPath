package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.domain.NotRouted
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * „Berechnen“ when the plan has no route on purpose (owner 2026-11-27 „muss dicker“): the thick filled plan-blue button beside
 * the sentence, under it from a large font on. Both reasons (a long leg, a stopped run) and a run started (busy). German,
 * normal and largest font (2.0).
 */
abstract class NotRoutedBarTest(fontScale: Float) {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = fontScale), showSystemUi = false)
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    @Test fun notRoutedBar() = paparazzi.snapshot {
        OnPathTheme {
            Column(Modifier.fillMaxWidth().background(OnPathColors.PlanTint).padding(16.dp)) {
                NotRoutedBar(NotRouted.LONG) {}
                NotRoutedBar(NotRouted.STOPPED) {}
                NotRoutedBar(NotRouted.LONG, busy = true) {}
            }
        }
    }
}

class NotRoutedBarGermanTest : NotRoutedBarTest(1.0f)
class NotRoutedBarLargeFontGermanTest : NotRoutedBarTest(2.0f)

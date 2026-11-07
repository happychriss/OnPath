package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The credit line on the map (tester review 2026-11-07: only "© OpenStreetMap" was there, docs/map-pipeline.md §3 asks
 * for OpenMapTiles too): both names, the tap to Settings → About kept, and on a narrow phone at normal and at the
 * largest font (2.0) it stays beside the map buttons – one line, or two broken after the "·", never a cut name (the
 * line grows with the font up to [CREDIT_MAX_FONT]; "DEMO" sits beside the scale). The words are names, the same in
 * DE and EN.
 */
class MapFooterCreditTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = narrow(1.0f), showSystemUi = false)

    /** 360 dp wide (a small phone), [font] = the phone's font size. */
    private fun narrow(font: Float) = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 900, xdpi = 480, ydpi = 480,
        density = Density.XXHIGH, fontScale = font)

    private fun shot(demo: Boolean) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.width(360.dp).background(Color(0xFFE8E4D8))) {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    MapFooter("500 m" to 64.dp, demo = demo) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(MAP_FAB).background(Color.White))
                        }
                    }
                }
            }
        }
    }

    @Test fun creditNamesTheDataAndTheTileSchema() {
        assertTrue(MAP_CREDIT.contains("OpenStreetMap") && MAP_CREDIT.contains("OpenMapTiles"))
        val footer = File("src/main/java/com/onpath/app/ui/MapFooter.kt").readText()
        assertTrue("the tap still opens the full credits", footer.substringAfter("Text(MAP_CREDIT,").take(400).contains("onClick = onCredits"))
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("onCredits = { vm.openSettings(SettingsPage.ABOUT) }"))
    }

    @Test fun normalFont() = shot(demo = false)
    @Test fun normalFontWithDemo() = shot(demo = true)
    @Test fun largestFont() { paparazzi.unsafeUpdateConfig(deviceConfig = narrow(2.0f)); shot(demo = false) }
    @Test fun largestFontWithDemo() { paparazzi.unsafeUpdateConfig(deviceConfig = narrow(2.0f)); shot(demo = true) }
}

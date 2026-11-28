package com.onpath.app.ui

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.data.AppSettings
import com.onpath.app.data.DataFiles
import com.onpath.strings.*
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The pairing window „Mit Browser verbinden“ (owner 2026-11-28 10:54, A-018) on a Pixel 8 (1080 × 2400, 420 dpi): not
 * paired, a scanned code's check digits, paired. Every pairing path lands here, so its one action must be on the first
 * screen: „Kamera öffnen“ / „Verbinden“ is asserted to lie inside the screen – at the normal font and the large ones.
 */
abstract class PairWindowShots(private val locale: Locale, fontScale: Float) {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_8.copy(fontScale = fontScale), showSystemUi = false)

    private val before = Locale.getDefault()
    private val zone = java.util.TimeZone.getDefault()
    @Before fun lang() { Locale.setDefault(locale); java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC")) }
    @After fun back() { Locale.setDefault(before); java.util.TimeZone.setDefault(zone) }

    private val now = 1_761_150_000_000L // 22 Oct 2025, 16:20 UTC

    /**
     * The window as the app draws it: Settings at [SettingsPage.SYNC] (with a backup – it must not show here); the
     * browser part – with its one action at its end ([action], for the message) – lies wholly on the first screen.
     */
    private fun window(sync: SyncUi, action: () -> String) {
        val label = action()
        var content: Rect? = null
        var screen: Rect? = null
        paparazzi.snapshot {
            CompositionLocalProvider(LocalPairWindowPlaced provides { content = it }) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { screen = it.boundsInRoot() }) {
                    OnPathTheme {
                        SettingsScreen(AppSettings(), {}, listOf(DataFiles.Check("base.pmtiles", true, "Base map")), "/x", "0.18.11", {},
                            page = SettingsPage.SYNC, sync = sync, now = now,
                            backup = BackupUi("Documents/OnPath", now - 3 * 3_600_000, 28, null, custom = false))
                    }
                }
            }
        }
        val c = content
        assertNotNull("the browser part is drawn", c)
        assertTrue("the browser part with „$label“ lies on the first screen ($c in $screen)", c!!.bottom <= screen!!.bottom && c.top >= 0f)
    }

    @Test fun notPaired() = window(SyncUi()) { Txt.s(Res.string.sync_open_camera) }
    @Test fun askCheck() = window(SyncUi(askCheck = "395 514")) { Txt.s(Res.string.sync_pair) }
    @Test fun paired() = window(SyncUi(paired = true, peer = "Chrome on Linux", lastOkMs = now - 60_000, sinceMs = now - 3 * 86_400_000L)) {
        Txt.s(Res.string.sync_unpair)
    }
}

class PairWindowGermanTest : PairWindowShots(Locale.GERMANY, 1.0f)
class PairWindowGermanLargeFontTest : PairWindowShots(Locale.GERMANY, 1.3f)
class PairWindowGermanLargestFontTest : PairWindowShots(Locale.GERMANY, 2.0f)
class PairWindowEnglishTest : PairWindowShots(Locale.UK, 1.0f)
class PairWindowEnglishLargeFontTest : PairWindowShots(Locale.UK, 1.3f)

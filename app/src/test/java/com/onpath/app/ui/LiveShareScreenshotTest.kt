package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.ui.live.LiveAction
import com.onpath.app.ui.live.LiveSign
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Live sharing as the first entry of the Settings opened from Navigate (owner 2026-11-20: "in der Navigation bei den
 * Einstellungen, als erster Eintrag mit Symbol"; docs/design/live-tracking.md §2) at a real phone's size – what the
 * owner decides on before it goes to main: the entry "Live teilen", the state live and waiting with the two actions,
 * the name row, the name question. Guards the layout: the card looks like the page's own rows, nothing is cut off in
 * German or at the largest font, the dialog's two actions stay in one row.
 */
abstract class LiveShareShots(private val locale: Locale) {
    abstract val paparazzi: Paparazzi
    private val before = Locale.getDefault()
    @Before fun locale() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val settings get() = SettingsScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }
    private val sharing = listOf(LiveAction.SHARE_AGAIN, LiveAction.STOP)

    /** Not sharing, never shared before: the one row. */
    @Test fun entry() = settings.onTheWayWith { LiveSettingsCard(LiveSign.NONE, listOf(LiveAction.SHARE), {}) }
    /** Sharing, uploads get through; the name was given at the first share. */
    @Test fun live() = settings.onTheWayWith { LiveSettingsCard(LiveSign.LIVE, sharing, {}, name = "Christian") }
    /** Sharing without network. */
    @Test fun waiting() = settings.onTheWayWith { LiveSettingsCard(LiveSign.WAITING, sharing, {}, name = "Christian") }
    /** Not sharing, shared before: the entry and the name row (left empty: no second line). */
    @Test fun entryWithName() = settings.onTheWayWith { LiveSettingsCard(LiveSign.NONE, listOf(LiveAction.SHARE), {}, name = "") }
    /** No tour records: the list is as before (no card, no gap). */
    @Test fun noTour() = settings.onTheWayWith { LiveSettingsCard(LiveSign.NONE, emptyList(), {}) }
    @Test fun nameDialog() = paparazzi.snapshot {
        OnPathTheme { Box(Modifier.fillMaxSize()) { MapPlaceholder(Modifier.fillMaxSize()); LiveNameDialog("", {}, {}) } }
    }
}

private val livePhone = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, xdpi = 420, ydpi = 420, density = Density.create(420))

class LiveShareGermanScreenshotTest : LiveShareShots(Locale.GERMANY) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = livePhone, showSystemUi = false)
}

class LiveShareScreenshotTest : LiveShareShots(Locale.US) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = livePhone, showSystemUi = false)
}

class LiveShareLargeFontTest : LiveShareShots(Locale.GERMANY) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = livePhone.copy(fontScale = 2.0f), showSystemUi = false)
}

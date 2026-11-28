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
 * Live sharing in Settings (Chriss 2026-11-28 14:11, like „Mit Browser verbinden“): the one row „Standort teilen“ with its
 * state first on the way, and its own page with exactly Name · Teilen · Teilen beenden (only while sharing) – at a real
 * phone's size, German, English and the largest font; the name question's two actions stay in one row.
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

    private fun entry(sign: LiveSign, actions: List<LiveAction>, name: String = "Christian", ended: Boolean = false) =
        LiveShareEntry(row = { o -> LiveShareRow(sign, o) }, page = { LiveSharePage(sign, actions, {}, name, {}, ended) })

    // ⚙ on the way (Chriss 2026-11-28 14:11): ONE row „Standort teilen“ with its state, first in the list
    /** Not sharing: „Nicht aktiv“. */
    @Test fun entry() = settings.onTheWayWith { LiveShareRow(LiveSign.NONE) {} }
    /** Sharing, uploads get through: „Aktiv“. */
    @Test fun live() = settings.onTheWayWith { LiveShareRow(LiveSign.LIVE) {} }
    /** Sharing without network. */
    @Test fun waiting() = settings.onTheWayWith { LiveShareRow(LiveSign.WAITING) {} }

    // the page behind it: exactly Name · Standort teilen / Link nochmal teilen · Teilen beenden (only while sharing)
    /** Not sharing during a tour: Name · Standort teilen – no „Teilen beenden“. */
    @Test fun pageOff() = settings.livePage(entry(LiveSign.NONE, listOf(LiveAction.SHARE)))
    /** Sharing: Name · Link nochmal teilen · Teilen beenden. */
    @Test fun pageLive() = settings.livePage(entry(LiveSign.LIVE, sharing))
    /** Not sharing, no name yet: the name row without a second line. */
    @Test fun entryWithName() = settings.livePage(entry(LiveSign.NONE, listOf(LiveAction.SHARE), name = ""))
    /** The server ended the sharing: its grey line first, then a new share is offered. */
    @Test fun endedByServer() = settings.livePage(entry(LiveSign.NONE, listOf(LiveAction.SHARE), ended = true))
    /** No tour records: the name only, and the line that sharing goes during a tour. */
    @Test fun noTour() = settings.livePage(entry(LiveSign.NONE, emptyList()))
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

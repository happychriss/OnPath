package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Every strip / card with an OK or action button (owner 2026-10-30: "bigger OK", one shared [NoticeButton]): the
 * pairing step, "Paired. …", the map questions (here, mobile data), the amber failure, the error card, the Navigate
 * "Notifications off" chip, the grey pill with OK and the update card.
 */
open class MessageButtonsScreenshotTest {
    @get:Rule
    open val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 3000, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    @Test fun stripsAndCards() = paparazzi.snapshot {
        OnPathTheme {
            Column(Modifier.background(Color(0xFFE8EEDC)).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PairHintStrip(onCamera = {}, onCancel = {})
                PairedNoteStrip(onOk = {})
                HereAskStrip(2, 21L * 1_048_576, {}, {})
                MapNoticeStrip(CellCardUi.Working("Load the map along the route?", null, emptyList(), null, null,
                    MobileQuestion(4, 25L * 1_048_576), null), CellCardCallbacks())
                MapNoticeStrip(CellCardUi.Failed("Map couldn't be loaded", null, retryable = true), CellCardCallbacks())
                ErrorCard("Couldn't find this place. Try again when you have internet.", onOk = {})
                NotificationsOffChip(onSetUp = {}, onOk = {})
                QuietPill("No map downloads in this build", Icons.Outlined.CloudOff, action = "OK" to {})
                UpdateBanner(AppViewModel.UpdateState.Available(com.onpath.app.update.AppUpdater.Release(
                    "0.4.1", "", "https://x/onpath.apk", 32_000_000, "https://x")), {}, {})
            }
        }
    }

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    protected val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }
}

/** The same in German. */
class MessageButtonsGermanScreenshotTest : MessageButtonsScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** The phone's largest font (2.0), German (the longer labels): the buttons wrap to their own line, still 48 dp. */
class MessageButtonsLargeFontTest : MessageButtonsScreenshotTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 5200, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = 2.0f),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    /** The first ☰ → Pair with browser on the Plan map: the step line + Open camera, below the search bar. */
    @Test fun planPairHint() = shots.planPairHint()
}

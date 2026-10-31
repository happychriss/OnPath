package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.maps_card_ready_to_download
import com.onpath.strings.maps_card_wait_wifi_mb
import com.onpath.strings.maps_err_area_failed
import com.onpath.strings.msg_shared_place_not_found
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Every message box (docs/design/ui-style.md – the picture there): FRAGE (pairing step, map for here?, mobile data,
 * Wi-Fi wait, new version, the Navigate question with 56 dp buttons), HINWEIS (paired, end the tour first, no downloads,
 * allow location), ⚠ HINWEIS amber (notifications off, map failed) and ⚠ HINWEIS red (an error).
 */
open class MessageCardScreenshotTest {
    @get:Rule
    open val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 4600, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    @Test fun kinds() = paparazzi.snapshot {
        OnPathTheme {
            Column(Modifier.background(Color(0xFFE8EEDC)).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MessageCard(pairHintMessage(onCamera = {}, onCancel = {}))
                MessageCard(hereAskMessage(21L * 1_048_576, {}, {}))
                // the real texts (EN / DE resources) – literal English here showed English boxes in the German picture
                mapNoticeMessage(CellCardUi.Working(Txt.s(Res.string.maps_card_ready_to_download), null, emptyList(), null, null,
                    MobileQuestion(4, 25L * 1_048_576), null), CellCardCallbacks())?.let { MessageCard(it) }
                mapNoticeMessage(CellCardUi.Working(Txt.s(Res.string.maps_card_ready_to_download), null, emptyList(), null, null, null,
                    Txt.p(Res.plurals.maps_card_wait_wifi_mb, 2, 2, 25L), canLoadNow = true), CellCardCallbacks())?.let { MessageCard(it) }
                MessageCard(updateMessage("0.4.1", {}, {}))
                val q = com.onpath.app.navigation.NavPrompt.Question(1, 350.0, 600.0, 20.0, emptyList(), 900.0, 2_000.0)
                MessageCard(rerouteMessage(q, {}, {}))
                MessageCard(syncNoteMessage(SyncNote.PAIRED, onOk = {}))
                MessageCard(tourBlockedMessage {})
                mapNoticeMessage(CellCardUi.NotAvailable, CellCardCallbacks())?.let { MessageCard(it) }
                mapNoticeMessage(CellCardUi.NeedPosition(permission = false), CellCardCallbacks())?.let { MessageCard(it) }
                MessageCard(notificationsOffMessage(onSetUp = {}, onOk = {}))
                mapNoticeMessage(CellCardUi.Failed(Txt.s(Res.string.maps_err_area_failed), null, retryable = true), CellCardCallbacks())
                    ?.let { MessageCard(it) }
                MessageCard(errorMessage(Txt.s(Res.string.msg_shared_place_not_found)) {})
            }
        }
    }

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    protected val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }
}

/** The same in German. */
class MessageCardGermanScreenshotTest : MessageCardScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** The phone's largest font (2.0), German (the longer labels): one button on its own row, nothing cut, still ≥ 48 dp. */
class MessageCardLargeFontTest : MessageCardScreenshotTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 9400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = 2.0f),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    /** The first ☰ → Pair with browser on the Plan map: the question box under the search bar. */
    @Test fun planPairHint() = shots.planPairHint()
}

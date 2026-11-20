package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.routing.Activity
import com.onpath.app.ui.live.LiveAction
import com.onpath.app.ui.live.LiveSign
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Live sharing on Navigate's status card at a real phone's size (docs/design/live-tracking.md §2) – what the owner
 * decides on before it is reachable: the entry "Live teilen", the sign live and waiting, the sign's two actions, the
 * one-time name question. Guards the layout: nothing cut off in German, at the largest font, and the dialog's two
 * actions in one row.
 */
abstract class LiveShareShots(private val locale: Locale) {
    abstract val paparazzi: Paparazzi
    private val before = Locale.getDefault()
    @Before fun locale() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val navUi = NavUi(toGo = ToGo(7_420.0, 512.0, 2.9 * 3600, 12.0, descentM = 310.0),
        recordedM = 5_320.0, elapsedS = 5025, activity = Activity.HIKE, arriveAt = "16:40")
    private val sharing = listOf(LiveAction.SHARE_AGAIN, LiveAction.STOP)

    @Composable
    private fun Card(sign: LiveSign, actions: List<LiveAction>, menu: Boolean = false, ui: NavUi = navUi) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.padding(top = 40.dp, start = 12.dp, end = 12.dp)) {
                    CompositionLocalProvider(LocalNavCardExtra provides { LiveShareRow(sign, actions, {}, menuInitiallyOpen = menu) }) {
                        NavStatusCard(ui, onResume = {}, actions = LocalNavCardExtra.current)
                    }
                }
            }
        }
    }

    @Test fun entry() = paparazzi.snapshot { Card(LiveSign.NONE, listOf(LiveAction.SHARE)) }
    @Test fun live() = paparazzi.snapshot { Card(LiveSign.LIVE, sharing) }
    @Test fun waiting() = paparazzi.snapshot { Card(LiveSign.WAITING, sharing) }
    /** The sign tapped: a drop-down is not drawn by the picture test, so its rows are drawn where it opens. */
    @Test fun menu() = paparazzi.snapshot {
        Box {
            Card(LiveSign.LIVE, sharing)
            OnPathTheme {
                androidx.compose.material3.Surface(shape = androidx.compose.material3.MaterialTheme.shapes.extraSmall, shadowElevation = 3.dp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.padding(top = 172.dp, start = 24.dp).width(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                    LiveMenuItems(sharing) {}
                }
            }
        }
    }
    @Test fun freeTourEntry() = paparazzi.snapshot {
        Card(LiveSign.NONE, listOf(LiveAction.SHARE), ui = NavUi(recordedM = 2_150.0, elapsedS = 1820, paused = false))
    }
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

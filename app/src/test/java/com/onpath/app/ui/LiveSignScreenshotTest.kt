package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.routing.Activity
import com.onpath.app.routing.TurnKind
import com.onpath.app.ui.live.LiveSign
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The sign on Navigate that the tour is shared (owner 2026-11-20: proposal C – inside the turn card; a tour without a
 * turn card gets the pill), live (green) and waiting for the network (grey), at a real phone's size in German, English
 * and the largest font. Guards: the sign never pushes the turn instruction off its line and nothing is cut off.
 */
abstract class LiveSignShots(private val locale: Locale) {
    abstract val paparazzi: Paparazzi

    private val before = Locale.getDefault()
    @Before fun locale() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val route = NavUi(toGo = ToGo(7_420.0, 512.0, 2.9 * 3600, 12.0, descentM = 310.0), recordedM = 5_320.0, elapsedS = 5025,
        activity = Activity.HIKE, arriveAt = "16:40", turn = TurnHint(TurnKind.RIGHT, 120.0))
    private val free = NavUi(recordedM = 2_150.0, elapsedS = 1820, paused = false)

    @Composable
    private fun Frame(ui: NavUi, sign: LiveSign) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    CompositionLocalProvider(
                        LocalTurnCardEnd provides { LiveSignInTurnCard(sign) },
                        LocalNavTop provides { turnCard -> if (!turnCard) LiveSignOnMap(sign) },
                    ) { NavigateOverlay(ui, footer = {}, onSlideToPlan = {}) }
                }
            }
        }
    }

    @Test fun live() = paparazzi.snapshot { Frame(route, LiveSign.LIVE) }
    @Test fun waiting() = paparazzi.snapshot { Frame(route, LiveSign.WAITING) }
    /** A tour without a route has no turn card: the pill. */
    @Test fun freeTourLive() = paparazzi.snapshot { Frame(free, LiveSign.LIVE) }
    @Test fun freeTourWaiting() = paparazzi.snapshot { Frame(free, LiveSign.WAITING) }
    /** Not sharing: Navigate is exactly as before. */
    @Test fun notSharing() = paparazzi.snapshot { Frame(route, LiveSign.NONE) }
}

private val signPhone = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, xdpi = 420, ydpi = 420, density = Density.create(420))

class LiveSignScreenshotTest : LiveSignShots(Locale.GERMANY) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = signPhone, showSystemUi = false)
}

class LiveSignEnglishScreenshotTest : LiveSignShots(Locale.US) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = signPhone, showSystemUi = false)
}

class LiveSignLargeFontTest : LiveSignShots(Locale.GERMANY) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = signPhone.copy(fontScale = 2.0f), showSystemUi = false)
}

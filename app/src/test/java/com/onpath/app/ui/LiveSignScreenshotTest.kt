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
 * The sign on Navigate that the tour is shared (owner 2026-11-20: "oben bei den Richtungen anzeigen – mach Vorschläge"):
 * three proposals, each live (green) and waiting for the network (grey), German, a real phone's size. The app carries
 * [LiveSignLook.IN_TURN_CARD] (C, with the pill on a tour without a turn card); A and B exist for these pictures.
 * Guards: the sign never pushes the turn instruction off its line and nothing is cut off.
 */
class LiveSignScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, xdpi = 420, ydpi = 420,
        density = Density.create(420)), showSystemUi = false)

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    private val route = NavUi(toGo = ToGo(7_420.0, 512.0, 2.9 * 3600, 12.0, descentM = 310.0), recordedM = 5_320.0, elapsedS = 5025,
        activity = Activity.HIKE, arriveAt = "16:40", turn = TurnHint(TurnKind.RIGHT, 120.0))
    private val free = NavUi(recordedM = 2_150.0, elapsedS = 1820, paused = false)

    @Composable
    private fun Frame(ui: NavUi, look: LiveSignLook, sign: LiveSign) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    CompositionLocalProvider(
                        LocalTurnCardEnd provides { if (look == LiveSignLook.IN_TURN_CARD) LiveSignInTurnCard(sign) },
                        LocalNavTop provides { turnCard ->
                            when (look) {
                                LiveSignLook.IN_TURN_CARD -> if (!turnCard) LiveSignOnMap(sign, LiveSignLook.PILL)
                                else -> LiveSignOnMap(sign, look)
                            }
                        },
                    ) { NavigateOverlay(ui, footer = {}, onSlideToPlan = {}) }
                }
            }
        }
    }

    @Test fun aLive() = paparazzi.snapshot { Frame(route, LiveSignLook.PILL, LiveSign.LIVE) }
    @Test fun aWaiting() = paparazzi.snapshot { Frame(route, LiveSignLook.PILL, LiveSign.WAITING) }
    @Test fun bLive() = paparazzi.snapshot { Frame(route, LiveSignLook.DOT, LiveSign.LIVE) }
    @Test fun bWaiting() = paparazzi.snapshot { Frame(route, LiveSignLook.DOT, LiveSign.WAITING) }
    @Test fun cLive() = paparazzi.snapshot { Frame(route, LiveSignLook.IN_TURN_CARD, LiveSign.LIVE) }
    @Test fun cWaiting() = paparazzi.snapshot { Frame(route, LiveSignLook.IN_TURN_CARD, LiveSign.WAITING) }
    /** A tour without a route has no turn card: C falls back to the pill. */
    @Test fun cFreeTourLive() = paparazzi.snapshot { Frame(free, LiveSignLook.IN_TURN_CARD, LiveSign.LIVE) }
    @Test fun cFreeTourWaiting() = paparazzi.snapshot { Frame(free, LiveSignLook.IN_TURN_CARD, LiveSign.WAITING) }
    /** Not sharing: Navigate is exactly as before. */
    @Test fun notSharing() = paparazzi.snapshot { Frame(route, LiveSignLook.IN_TURN_CARD, LiveSign.NONE) }
}
